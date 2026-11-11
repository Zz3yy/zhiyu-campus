#!/usr/bin/env python3
"""Exercise a deployed proxy with synthetic data; never print session tokens."""
import argparse
import json
import subprocess
import time
import urllib.request
import uuid

parser = argparse.ArgumentParser()
parser.add_argument('--base', default='http://127.0.0.1:8088')
parser.add_argument('--restart-service', action='store_true', help='On the server, verify data survives a service restart')
args = parser.parse_args()
base = args.base.rstrip('/')
token = None


def request(path, method='GET', body=None):
    headers = {'Content-Type': 'application/json', 'Origin': base}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    data = None if body is None else json.dumps(body).encode()
    with urllib.request.urlopen(urllib.request.Request(base + path, data=data, method=method, headers=headers), timeout=130) as response:
        return response.read(), response.headers


def api(path='', method='GET', body=None):
    return json.loads(request('/api/garden' + path, method, body)[0])['data']


try:
    html, _ = request('/')
    assert b'<div id="root">' in html, 'Frontend root missing'
    assert json.loads(request('/api/health')[0])['status'] == 'ok'
    token = api('/session', 'POST', {'accepted': True})['token']
    snapshot = api()
    assert snapshot['storageMode'] == 'postgres', 'Deployment must use persistent storage'
    state = api('/entries', 'POST', {'requestId': str(uuid.uuid4()), 'plant': 'mimosa', 'intensity': 4,
                                   'note': 'Synthetic deployment check', 'timezone': 'Asia/Shanghai'})
    entry_id = state['entries'][0]['id']
    assert api()['garden']['entries'][0]['intensity'] == 4
    print('PASS frontend, same-origin POST, authentication, PostgreSQL write/read')
    raw, headers = request('/api/garden/chat/stream', 'POST', {'text': '复习资料太多，不知道从哪里开始',
        'cloudConsent': False, 'includeGardenContext': False, 'includeActionFeedback': False, 'history': []})
    assert 'text/event-stream' in headers['Content-Type']
    assert b'event:status' in raw and b'event:complete' in raw, 'SSE response incomplete'
    print('PASS SSE through Nginx (local guidance, no cloud provider call)')
    if args.restart_service:
        subprocess.run(['systemctl', 'restart', 'zhiyu'], check=True)
        for _ in range(60):
            try:
                if json.loads(request('/api/health')[0])['status'] == 'ok':
                    break
            except (OSError, ValueError):
                pass
            time.sleep(1)
        assert any(e['id'] == entry_id for e in api()['garden']['entries']), 'Record lost on restart'
        print('PASS record and session survive application restart')
    for path in ['/chat/status', '/images/status']:
        status = api(path)
        print(path + ': configured=' + str(status.get('configured', False)))
finally:
    if token:
        api('', 'DELETE')
        print('Removed synthetic garden')
