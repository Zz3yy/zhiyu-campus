"""Validate canonical JSON and export a review queue; NEVER creates human approvals.

Run from any directory: python scripts/knowledge-review.py
Optional --export writes docs/knowledge-review.json. Java uses the same compact field order for SHA-256.
"""
import argparse
import hashlib
import json
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RESOURCE = ROOT / 'zhiyu-server/src/main/resources/knowledge'
FIELDS = ['id', 'activityId', 'title', 'content', 'source', 'boundary', 'version', 'category', 'metadata', 'sourceId', 'sourceLocator', 'sourceRelation']
META_FIELDS = ['timeMinutes', 'targetEmotions', 'cognitiveLoad', 'scenes', 'keywords']


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--export', action='store_true')
    args = parser.parse_args()
    docs = json.loads((RESOURCE / 'corpus.json').read_text(encoding='utf-8'))
    reviews = json.loads((RESOURCE / 'reviews.json').read_text(encoding='utf-8'))
    queue, ids, texts = [], set(), set()
    for doc in docs:
        assert set(doc) == set(FIELDS), f"Unexpected fields: {doc.get('id')}"
        assert doc['id'] not in ids and doc['content'] not in texts, 'Duplicate id or content'
        ids.add(doc['id']); texts.add(doc['content'])
        meta = doc['metadata']
        assert set(meta) == set(META_FIELDS)
        assert 1 <= meta['timeMinutes'] <= 60
        assert meta['cognitiveLoad'] in ['very_low', 'low', 'medium']
        assert meta['scenes'] and set(meta['scenes']) <= {'dormitory', 'library', 'classroom', 'commute'}
        assert meta['targetEmotions'] and set(meta['targetEmotions']) <= {'anxiety', 'procrastination', 'frustration', 'confusion'}
        assert doc['category'] in ['activity', 'concept', 'scenario', 'template']
        assert all(isinstance(doc[key], str) and doc[key] for key in ['title', 'content', 'source', 'boundary', 'version', 'sourceId', 'sourceLocator', 'sourceRelation'])
        ordered = {key: doc[key] for key in FIELDS}
        ordered['metadata'] = {key: meta[key] for key in META_FIELDS}
        digest = hashlib.sha256(json.dumps(ordered, ensure_ascii=False, separators=(',', ':')).encode()).hexdigest()
        queue.append({**ordered, 'contentHash': digest, 'reviewStatus': 'PENDING', 'reviewer': '', 'reviewedAt': '',
                      'checks': {'sourceVerified': False, 'faithfulToSource': False, 'safetyBoundary': False, 'metadataAppropriate': False}})
    print(json.dumps({'chunks': len(docs), 'categories': dict(Counter(d['category'] for d in docs)), 'reviewManifestEntries': len(reviews)}, ensure_ascii=False))
    if args.export:
        path = ROOT / 'docs/knowledge-review.json'
        path.write_text(json.dumps(queue, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        print('Exported pending review queue to docs/knowledge-review.json. Runtime approvals unchanged.')


if __name__ == '__main__':
    main()
