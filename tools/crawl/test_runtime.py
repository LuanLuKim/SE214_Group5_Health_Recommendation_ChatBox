import io
import json
from email.message import Message
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from urllib.error import HTTPError

from crawler import http
from crawler.config import load_sources
from crawler.storage import Store
from crawler.runner import run

HTML = b'<html lang="en"><main><h1>Synthetic</h1><p>Example content.</p></main></html>'
URL = 'https://www.nhs.uk/example'


class Reply(io.BytesIO):
    def __init__(self, body, mime):
        super().__init__(body)
        self.headers = Message()
        self.headers['Content-Type'] = mime
        self.status = 200


class Network:
    def __init__(self):
        self.urls = []
        self.errors = {}

    def open(self, request, timeout):
        url = request.full_url
        self.urls.append(url)
        if url in self.errors:
            raise self.errors[url]
        return Reply(b'User-agent: *\nDisallow: /private' if url.endswith('robots.txt') else HTML,
                     'text/plain' if url.endswith('robots.txt') else 'text/html')


class RuntimeTests(unittest.TestCase):
    def setUp(self):
        self.temp = self.enterContext(tempfile.TemporaryDirectory())
        self.root = Path(self.temp)
        self.net = Network()
        self.enterContext(patch.object(http, 'INTERVAL', 0))
        self.enterContext(patch.object(http.time, 'sleep'))
        (self.root / 'crawl').mkdir()
        self.batch = http.Batch(self.root / 'crawl', self.net)
        self.sources = load_sources()
        self.store = Store(self.root / 'collection')

    def collect(self, **kwargs):
        return run([URL], self.sources, self.store, self.batch, **kwargs)

    def test_cache_and_reextract_never_fetch_again(self):
        self.assertEqual(self.collect()['exit_code'], 0)
        self.assertEqual(self.collect()['counts'], {'CACHED': 1})
        self.assertEqual(self.collect(reextract=True)['counts'], {'EXTRACTED': 1})
        self.assertEqual(self.net.urls, ['https://www.nhs.uk/robots.txt', URL])

    def test_failed_refresh_preserves_valid_cache(self):
        self.collect()
        previous = self.store.cached(URL)
        self.net.errors[URL] = HTTPError(URL, 403, 'stop', {}, None)
        self.assertEqual(self.collect(refresh=True)['exit_code'], 1)
        self.assertEqual(self.store.cached(URL)['sha256_raw'], previous['sha256_raw'])
        before = len(self.net.urls)
        self.collect(refresh=True)
        self.assertEqual(len(self.net.urls), before)

    def test_corrupt_cache_and_query_identity(self):
        self.collect()
        cached = self.store.cached(URL)
        self.store.raw_path(cached).write_bytes(b'corrupt')
        self.assertEqual(self.collect(reextract=True)['exit_code'], 1)
        self.assertIsNone(self.store.cached(URL + '?x=1'))

    def test_metadata_only_never_persists_body(self):
        url = 'https://www.cdc.gov/health-topics/example'
        summary = run([url], self.sources, self.store, self.batch)
        self.assertEqual(summary['counts'], {'METADATA_ONLY': 1})
        self.assertEqual(self.net.urls, [])
        run([url], self.sources, self.store, self.batch, inspect_metadata=True)
        for path in (self.root / 'collection').rglob('*'):
            if path.is_file():
                self.assertNotIn('Example content', path.read_text(encoding='utf-8'))

    def test_redirect_target_robots_checked_before_request(self):
        self.net.errors[URL] = HTTPError(URL, 301, 'moved', {'Location': '/private/new'}, None)
        self.assertEqual(self.collect()['exit_code'], 1)
        self.assertNotIn('https://www.nhs.uk/private/new', self.net.urls)

    def test_request_budget_and_retry_survive_restart(self):
        self.net.errors[URL] = OSError('temporary')
        self.assertEqual(self.collect()['exit_code'], 1)
        self.assertEqual(self.net.urls.count(URL), 2)
        self.collect()
        self.assertEqual(self.net.urls.count(URL), 2)
        self.batch.usage()['requests'] = 200
        with self.assertRaises(http.Limit):
            self.batch.fetch_response(URL, 'text/html')

    def test_each_group_has_its_own_budget(self):
        self.batch.usage()['requests'] = 200
        with self.assertRaises(http.Limit):
            self.batch.fetch_response(URL, 'text/html')
        other = http.Batch(self.root / 'crawl', self.net, 'urls_lifestyle')
        other.state = self.batch.state
        other.fetch_response(URL, 'text/html')
        self.assertEqual(self.batch.state['groups']['urls_lifestyle']['requests'], 1)
        self.assertEqual(self.batch.state['groups']['default']['requests'], 200)
        self.assertEqual(self.batch.state['requests'], 1)
        line = json.loads((self.root / 'crawl' / 'events.jsonl').read_text(encoding='utf-8').splitlines()[-1])
        self.assertEqual((line['group'], line['group_number']), ('urls_lifestyle', 1))
        self.assertLess(self.batch.state['groups']['urls_lifestyle']['charged_bytes'], http.BODY_LIMIT)
        with self.assertRaises(ValueError):
            http.Batch(self.root / 'crawl', self.net, '../x')

    def test_old_checkpoint_without_groups_refuses_to_fetch(self):
        self.batch.state = {'requests': 5, 'charged_bytes': 0, 'attempts': {}, 'last_request': 0, 'blocked': {}}
        with self.assertRaises(http.Limit):
            self.batch.fetch_response(URL, 'text/html')
        self.assertEqual(self.net.urls, [])

    def test_reextract_missing_raw_is_failure_without_network(self):
        self.assertEqual(self.collect(reextract=True)['exit_code'], 1)
        self.assertEqual(self.net.urls, [])

    def test_unexpected_crash_never_leaves_success_receipt(self):
        with patch.object(self.store, 'cached', side_effect=RuntimeError('synthetic crash')):
            with self.assertRaises(RuntimeError):
                self.collect()
        receipt = json.loads((self.store.root / 'latest.json').read_text(encoding='utf-8'))
        self.assertEqual(receipt['exit_code'], 1)
        self.assertEqual(receipt['state'], 'FAILED')

    def test_response_body_limit_is_enforced_while_streaming(self):
        with patch.object(http, 'BODY_LIMIT', 8):
            with self.assertRaises(ValueError):
                self.batch.fetch_response(URL, 'text/html')
        self.assertEqual(self.batch.state['requests'], 1)

    def test_run_budget_produces_failure_receipt(self):
        self.batch.usage()['requests'] = 200
        self.assertEqual(self.collect()['exit_code'], 1)
        self.assertEqual(json.loads((self.store.root / 'latest.json').read_text())['stop_reason'], 'LIMIT')

    def test_failed_extraction_retains_raw_for_offline_recovery(self):
        with patch('crawler.runner.extract', side_effect=ValueError('UNSUPPORTED_TEMPLATE')):
            self.assertEqual(self.collect()['exit_code'], 1)
        before = len(self.net.urls)
        self.assertEqual(self.collect(reextract=True)['exit_code'], 0)
        self.assertEqual(len(self.net.urls), before)

    def test_reextract_uses_fresh_raw_after_failed_refresh(self):
        self.collect()
        new_body = HTML.replace(b'Example content.', b'New content.')
        response = {'body': new_body, 'charset': 'utf-8', 'url': URL, 'final_url': URL,
                    'redirects': [], 'http_status': 200, 'fetched_at': '2026-11-03T09:00:00Z'}
        with patch('crawler.runner.Transport.download', return_value=response), patch('crawler.runner.extract', side_effect=ValueError('UNSUPPORTED_TEMPLATE')):
            self.collect(refresh=True)
        self.assertEqual(self.collect(reextract=True)['exit_code'], 0)
        record = self.store.cached(URL)
        self.assertIn(b'New content.', self.store.read_raw(record))

    def test_old_cache_without_raw_pointer_still_reextracts(self):
        self.collect()
        for path in (self.store.root / 'raw-pages').glob('*.json'):
            path.unlink()
        self.assertEqual(self.collect(reextract=True)['exit_code'], 0)

    def test_startup_configuration_failure_writes_own_receipt(self):
        import run as cli
        (self.root / 'docs').mkdir()
        (self.root / 'docs' / 'urls.txt').write_text(URL, encoding='utf-8')
        with patch.object(cli, 'ROOT', self.root), patch('sys.argv', ['run.py', '--run-id', 'startup-test']), patch.object(cli, 'load_sources', side_effect=ValueError('INVALID_SOURCE_CONFIG')):
            self.assertEqual(cli.main(), 2)
        receipt = json.loads((self.root / '.data/collection/runs/startup-test.json').read_text())
        self.assertEqual(receipt['state'], 'FAILED_TO_START')
        self.assertEqual(receipt['exit_code'], 2)

    def test_404_is_terminal_across_restart(self):
        self.net.errors[URL] = HTTPError(URL, 404, 'missing', {}, None)
        self.collect()
        self.collect()
        self.assertEqual(self.net.urls.count(URL), 1)

    def test_robots_network_failure_is_bounded_across_restart(self):
        robots = 'https://www.nhs.uk/robots.txt'
        self.net.errors[robots] = OSError('offline')
        self.collect()
        self.collect()
        self.assertEqual(self.net.urls.count(robots), 2)


class BudgetAllocationTests(unittest.TestCase):
    def test_history_goes_to_list_robots_to_next_page_redirect_to_previous(self):
        from crawler.budget import allocate, memberships, split_bytes
        with tempfile.TemporaryDirectory() as directory:
            a, b = Path(directory) / 'urls.txt', Path(directory) / 'urls_lifestyle.txt'
            a.write_text('https://www.nhs.uk/a/ topic\n# disabled https://www.nhs.uk/old/\n', encoding='utf-8')
            b.write_text('https://www.nhs.uk/b/#x\nhttps://www.nhs.uk/a/ duplicate\n', encoding='utf-8')
            groups = memberships([a, b])
        self.assertEqual(groups['https://www.nhs.uk/a/'], 'urls')
        self.assertEqual(groups['https://www.nhs.uk/old/'], 'urls')
        requests = ['https://www.nhs.uk/robots.txt', 'https://www.nhs.uk/a/', 'https://www.nhs.uk/moved/',
                    'https://www.nhs.uk/robots.txt', 'https://www.nhs.uk/b/', 'https://www.nhs.uk/b/', 'https://www.nhs.uk/old/']
        self.assertEqual(allocate(requests, groups), {'urls': 4, 'urls_lifestyle': 3})
        self.assertEqual(allocate(['https://www.nhs.uk/unknown/'], groups), {'unassigned': 1})
        self.assertEqual(split_bytes(10, {'urls': 2, 'urls_lifestyle': 1}), {'urls': 7, 'urls_lifestyle': 3})


if __name__ == '__main__':
    unittest.main()
