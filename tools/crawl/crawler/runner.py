"""Collection orchestration; no domain or template branches."""
from collections import Counter
import json
import sys
from http.client import HTTPException
from uuid import uuid4

from .adapters import extract
from .config import source_for
from .http import Transport, Limit, now, save


def run(urls, sources, store, batch, *, refresh=False, reextract=False, inspect_metadata=False, run_id=None):
    if refresh and reextract:
        raise ValueError('CONFLICTING_OPTIONS')
    run_id = run_id or uuid4().hex
    transport = Transport(batch, sources)
    summary = {'run_id': run_id, 'started_at': now(), 'state': 'RUNNING', 'rows': []}
    receipt = store.root / 'runs' / (run_id + '.json')
    save(receipt, summary)
    counts = Counter()
    exit_code = 0
    try:
        for url in urls:
            row = {'url': url, 'source': source_for(url, sources)['id']}
            try:
                source = source_for(url, sources)
                policy = source['policy']
                if policy['mode'] == 'metadata_only':
                    row['status'] = 'METADATA_ONLY'
                    if inspect_metadata and not reextract:
                        response = transport.download(url)
                        document = extract(response['body'].decode(response['charset']), response['final_url'], source)
                        row.update(status='METADATA_INSPECTED', title=document['title'], language=document['language'],
                                   source_updated_at=document['source_updated_at'], block_count=len(document['blocks']))
                else:
                    cached = store.cached(url, latest_raw=reextract)
                    if reextract and not cached:
                        raise ValueError('MISSING_RAW')
                    if cached and not refresh:
                        record = cached
                        body = store.read_raw(record)
                    else:
                        response = transport.download(url)
                        body = response['body']
                        record = store.save_fetch(url, response, policy)
                    document = extract(body.decode(record['charset']), record['final_url'], source)
                    from .storage import identity
                    version = identity(json.dumps(document, sort_keys=True))
                    same = cached and cached.get('extraction_version') == version
                    store.save_extraction(record, document)
                    row.update(status='CACHED' if same and not refresh and not reextract else 'EXTRACTED',
                               blocks=len(document['blocks']), sha256_raw=record['sha256_raw'])
            except (OSError, ValueError, LookupError, TypeError, HTTPException, RecursionError) as exc:
                # Never persist exception text: parser/network errors may contain article data.
                row.update(status='ERROR', error_type=type(exc).__name__)
                if isinstance(exc, ValueError) and str(exc) in {
                    'HOST_BLOCKED', 'ATTEMPT_LIMIT', 'ROBOTS_DENIED', 'REDIRECT_NEEDS_REVIEW',
                    'MISSING_RAW', 'RAW_HASH_MISMATCH', 'CACHE_URL_MISMATCH', 'INVALID_RAW_HASH',
                    'UNSUPPORTED_TEMPLATE', 'AMBIGUOUS_TEMPLATE', 'AMBIGUOUS_CONTENT_ROOT',
                    'INVALID_TITLE', 'EMPTY_CONTENT', 'TEXT_COVERAGE_MISMATCH', 'HTML_DEPTH_LIMIT'}:
                    row['reason'] = str(exc)
                if hasattr(exc, 'code'):
                    row['http_status'] = exc.code
                exit_code = 1
            counts[row['status']] += 1
            summary['rows'].append(row)
            print(json.dumps(row, ensure_ascii=True), flush=True)
    except (Limit, KeyboardInterrupt) as exc:
        summary['stop_reason'] = 'LIMIT' if isinstance(exc, Limit) else 'INTERRUPTED'
        exit_code = 1
    finally:
        crashed = sys.exc_info()[0] is not None
        summary.update(state='FAILED' if crashed else 'FINISHED', finished_at=now(), counts=dict(counts), exit_code=1 if crashed else exit_code,
                       requests_this_run=transport.used)
        save(receipt, summary)
        save(store.root / 'latest.json', summary)
    return summary
