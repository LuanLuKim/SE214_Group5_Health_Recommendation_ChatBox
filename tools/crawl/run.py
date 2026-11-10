"""Collect explicit HTML URLs using configured source adapters."""
import argparse
from contextlib import closing
import json
from pathlib import Path
import re
import sqlite3
from urllib.request import build_opener
from uuid import uuid4

from crawler.config import ROOT, load_sources, load_urls, source_for
from crawler.http import Batch, NoRedirect, save, now
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
    args = parser.parse_args()
    output = ROOT / '.data' / 'collection'
    if args.status:
        path = output / 'latest.json'
        print(path.read_text(encoding='utf-8') if path.exists() else 'No completed run.')
        return 0
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
        checkpoint = ROOT / '.data' / 'crawl'
        checkpoint.mkdir(parents=True, exist_ok=True)
        with closing(sqlite3.connect(checkpoint / 'lock.sqlite', timeout=0)) as lock:
            lock.execute('BEGIN EXCLUSIVE')
            store = Store(output)
            summary = run(urls, sources, store, Batch(checkpoint, build_opener(NoRedirect())),
                          refresh=args.refresh, reextract=args.reextract, inspect_metadata=args.inspect_metadata, run_id=args.run_id)
            print(json.dumps({k: v for k, v in summary.items() if k != 'rows'}), flush=True)
            return summary['exit_code']
    except (ValueError, OSError, LookupError, TypeError, sqlite3.Error) as exc:
        result = {'run_id': args.run_id, 'state': 'FAILED_TO_START', 'error_type': type(exc).__name__, 'exit_code': 2, 'finished_at': now()}
        if 'receipt' in locals() and str(exc) != 'RUN_ID_ALREADY_EXISTS':
            save(receipt, result)
        print(json.dumps(result))
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
