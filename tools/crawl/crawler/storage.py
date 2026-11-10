"""Immutable raw bytes and versioned extraction, with validated cache pointers."""
from hashlib import sha256
import json
import re
from pathlib import Path

from .http import save


def identity(url):
    return sha256(url.encode('utf-8')).hexdigest()


class Store:
    def __init__(self, root):
        self.root = Path(root)
        for name in ('raw', 'pages', 'raw-pages', 'extracted', 'fetches', 'runs'):
            (self.root / name).mkdir(parents=True, exist_ok=True)

    def raw_path(self, record):
        if not isinstance(record.get('sha256_raw'), str) or not re.fullmatch(r'[0-9a-f]{64}', record['sha256_raw']):
            raise ValueError('INVALID_RAW_HASH')
        return self.root / 'raw' / (record['sha256_raw'] + '.html')

    def read_raw(self, record):
        body = self.raw_path(record).read_bytes()
        if sha256(body).hexdigest() != record['sha256_raw']:
            raise ValueError('RAW_HASH_MISMATCH')
        return body

    def cached(self, url, *, latest_raw=False):
        directories = ('raw-pages', 'pages') if latest_raw else ('pages', 'raw-pages')
        candidates = [self.root / directory / (identity(url) + '.json') for directory in directories]
        path = next((p for p in candidates if p.exists()), candidates[0])
        if not path.exists():
            return None
        record = json.loads(path.read_text(encoding='utf-8'))
        if record['url'] != url:
            raise ValueError('CACHE_URL_MISMATCH')
        self.read_raw(record)
        return record

    def save_fetch(self, url, response, policy):
        body = response['body']
        self.check_space(len(body) * 3)
        record = {key: value for key, value in response.items() if key != 'body'}
        record.update(schema_version=1, id=identity(url), url=url, sha256_raw=sha256(body).hexdigest(), policy=policy,
                      review_status='DRAFT', rag_eligible=False)
        path = self.raw_path(record)
        if path.exists():
            self.read_raw(record)
        else:
            temporary = path.with_suffix('.tmp')
            with temporary.open('wb') as stream:
                stream.write(body)
            temporary.replace(path)
        save(self.root / 'fetches' / (identity(url) + '-' + identity(record['fetched_at'])[:16] + '.json'), record)
        save(self.root / 'raw-pages' / (identity(url) + '.json'), record)
        return record

    def save_extraction(self, record, document):
        self.check_space(len(json.dumps(document, ensure_ascii=False).encode('utf-8')) * 3)
        version = identity(json.dumps(document, sort_keys=True))
        filename = f"{record['id']}-{record['sha256_raw'][:16]}-{version[:16]}.json"
        output = {**document, 'url': record['url'], 'final_url': record['final_url'], 'sha256_raw': record['sha256_raw'], 'fetched_at': record['fetched_at']}
        save(self.root / 'extracted' / filename, output)
        record = {**record, 'extraction_file': filename, 'extraction_version': version}
        save(self.root / 'pages' / (record['id'] + '.json'), record)
        return record

    def check_space(self, required):
        from .http import Limit, MIB
        used = sum(path.stat().st_size for name in ('crawl', 'collection', 'review')
                   for path in (self.root.parent / name).rglob('*') if path.is_file())
        if used + required > 250 * MIB:
            raise Limit('DISK_LIMIT')
