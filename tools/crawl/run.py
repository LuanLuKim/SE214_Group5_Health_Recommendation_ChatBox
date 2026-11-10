"""Collect explicit HTML URLs using configured source adapters."""
import argparse
from contextlib import closing
import json
from pathlib import Path
import re
import sqlite3
from urllib.request import build_opener
from uuid import uuid4

from crawler.budget import allocate, memberships, split_bytes
from crawler.config import ROOT, load_sources, load_urls, source_for
from crawler.http import MAX_BYTES, MAX_REQUESTS, Batch, NoRedirect, save, now
from crawler.storage import Store
from crawler.runner import run


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--urls', type=Path, default=ROOT / 'docs' / 'urls.txt')
    parser.add_argument('--source')
    modes = parser.add_mutually_exclusive_group()
    modes.add_argument('--refresh', action='store_true')
    modes.add_argument('--reextract', action='store_true')
    parser.add_argument('--inspect-metadata', action='store_true')
    parser.add_argument('--status', action='store_true')
    parser.add_argument('--run-id')
    parser.add_argument('--group', help='budget group (default: file name of --urls, e.g. urls_lifestyle)')
    parser.add_argument('--budget', action='store_true', help='print requests/bytes used per group')
    parser.add_argument('--allocate-history', action='store_true',
                        help='one-off: assign past requests in events.jsonl to groups (backs up state.json)')
    args = parser.parse_args()
    output = ROOT / '.data' / 'collection'
    checkpoint = ROOT / '.data' / 'crawl'
    if args.status:
        path = output / 'latest.json'
        print(path.read_text(encoding='utf-8') if path.exists() else 'No completed run.')
        return 0
    if args.budget:
        return print_budget(checkpoint)
    if args.allocate_history:
        return allocate_history(checkpoint)
    args.group = args.group or args.urls.stem
    if args.run_id and not re.fullmatch(r'[a-zA-Z0-9-]{1,80}', args.run_id):
        parser.error('invalid run id')
    try:
        args.run_id = args.run_id or uuid4().hex
        receipt = output / 'runs' / (args.run_id + '.json')
        receipt.parent.mkdir(parents=True, exist_ok=True)
        if receipt.exists():
            raise ValueError('RUN_ID_ALREADY_EXISTS')
        save(receipt, {'run_id': args.run_id, 'state': 'STARTING', 'started_at': now()})
        sources = load_sources()
        if args.source and args.source not in sources:
            raise ValueError('UNKNOWN_SOURCE')
        urls = load_urls(args.urls, sources)
        urls = [u for u in urls if not args.source or source_for(u, sources)['id'] == args.source]
        checkpoint.mkdir(parents=True, exist_ok=True)
        with closing(sqlite3.connect(checkpoint / 'lock.sqlite', timeout=0)) as lock:
            lock.execute('BEGIN EXCLUSIVE')
            store = Store(output)
            summary = run(urls, sources, store, Batch(checkpoint, build_opener(NoRedirect()), args.group),
                          refresh=args.refresh, reextract=args.reextract, inspect_metadata=args.inspect_metadata, run_id=args.run_id)
            print(json.dumps({k: v for k, v in summary.items() if k != 'rows'}), flush=True)
            return summary['exit_code']
    except (ValueError, OSError, LookupError, TypeError, sqlite3.Error) as exc:
        result = {'run_id': args.run_id, 'state': 'FAILED_TO_START', 'error_type': type(exc).__name__, 'exit_code': 2, 'finished_at': now()}
        if 'receipt' in locals() and str(exc) != 'RUN_ID_ALREADY_EXISTS':
            save(receipt, result)
        print(json.dumps(result))
        return 2


def print_budget(checkpoint):
    path = checkpoint / 'state.json'
    state = json.loads(path.read_text(encoding='utf-8')) if path.exists() else {}
    print(json.dumps({'limits': {'requests_per_group': MAX_REQUESTS, 'bytes_per_group': MAX_BYTES},
                      'total': {'requests': state.get('requests', 0), 'charged_bytes': state.get('charged_bytes', 0)},
                      'groups': state.get('groups', 'NOT_ALLOCATED')}, indent=2))
    return 0


def allocate_history(checkpoint):
    """Assign the request history to groups once; refuses if groups already exist."""
    path = checkpoint / 'state.json'
    state = json.loads(path.read_text(encoding='utf-8'))
    if 'groups' in state:
        print(json.dumps({'error': 'GROUPS_ALREADY_ALLOCATED'}))
        return 2
    events = [json.loads(line) for line in (checkpoint / 'events.jsonl').read_text(encoding='utf-8').splitlines() if line.strip()]
    requests = [e['url'] for e in events if e.get('event') == 'request']
    if len(requests) != state['requests']:
        print(json.dumps({'error': 'EVENT_LOG_MISMATCH', 'events': len(requests), 'state': state['requests']}))
        return 2
    counts = allocate(requests, memberships(sorted((ROOT / 'docs').glob('urls*.txt'))))
    shares = split_bytes(state['charged_bytes'], counts)
    backup = path.with_name('state.before-groups.json')
    if backup.exists():
        print(json.dumps({'error': 'BACKUP_EXISTS', 'path': str(backup)}))
        return 2
    backup.write_text(path.read_text(encoding='utf-8'), encoding='utf-8')
    state['groups'] = {g: {'requests': counts[g], 'charged_bytes': shares[g]} for g in sorted(counts)}
    save(path, state)
    print(json.dumps({'backup': str(backup), 'groups': state['groups']}, indent=2))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
