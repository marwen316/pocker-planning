"""Exercise the packaged WAR with real HTTP sessions and SSE connections (stdlib only)."""
import argparse
import http.cookiejar
import json
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('war', nargs='?', default='target/pocker-planning.war')
parser.add_argument('--java', default='java')
args = parser.parse_args()
with socket.socket() as reservation:
    reservation.bind(('127.0.0.1', 0))
    port = reservation.getsockname()[1]
root = f'http://127.0.0.1:{port}/pocker-planning/'


def browser():
    return urllib.request.build_opener(
        urllib.request.ProxyHandler({}),
        urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))


def request(client, path='', method='GET', data=None):
    req = urllib.request.Request(root + path, method=method,
        data=json.dumps(data).encode() if data is not None else None,
        headers={'Content-Type': 'application/json'})
    try:
        with client.open(req, timeout=5) as response:
            return response.status, response.read().decode()
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode()


def event(stream):
    lines = []
    while True:
        line = stream.readline().decode().rstrip('\r\n')
        if not line:
            if lines:
                return lines
            raise AssertionError('Unexpected end of SSE stream')
        lines.append(line)


def state(stream):
    while True:
        lines = event(stream)
        if 'event:state' in lines:
            return json.loads(next(line[5:] for line in lines if line.startswith('data:')))


def open_stream(client):
    stream = client.open(root + 'api/session/events', timeout=25)
    assert stream.headers.get_content_type() == 'text/event-stream'
    assert stream.headers['X-Accel-Buffering'] == 'no'
    assert stream.headers['Cache-Control'] == 'no-store'
    return stream


def card(snapshot, name):
    return next(p['card'] for p in snapshot['participants'] if p['firstName'] == name)


streams = []
with tempfile.TemporaryFile(mode='w+') as log:
    process = subprocess.Popen([args.java, '-jar', args.war, f'--server.port={port}',
        '--server.servlet.context-path=/pocker-planning'], stdout=log, stderr=subprocess.STDOUT)
    try:
        alice, bob, visitor = browser(), browser(), browser()
        for _ in range(80):
            try:
                status, html = request(alice)
                break
            except urllib.error.URLError:
                if process.poll() is not None:
                    raise AssertionError('WAR exited during startup')
                time.sleep(.25)
        else:
            raise AssertionError('WAR did not start in 20 seconds')
        assert status == 200 and '<app-root>' in html
        assert request(visitor, 'api/session/events')[0] == 401
        for client, name in [(alice, 'Alice'), (bob, 'Bob')]:
            assert request(client, 'api/session/join', 'POST',
                {'firstName': name, 'lastName': 'Test', 'role': 'DEVELOPER'})[0] == 200
        a, b = open_stream(alice), open_stream(bob)
        streams.extend([a, b])
        initial_a, initial_b = state(a), state(b)
        assert len(initial_a['participants']) == 2
        assert sum(p['self'] for p in initial_b['participants']) == 1
        assert request(alice, 'api/session/vote', 'PUT', {'card': '21'})[0] == 200
        own, other = state(a), state(b)
        assert card(own, 'Alice') == '21' and card(other, 'Alice') is None
        assert other['revision'] > initial_b['revision']
        assert request(alice, 'api/session/reveal', 'POST')[0] == 409
        assert request(bob, 'api/session/vote', 'PUT', {'card': 'COFFEE'})[0] == 200
        assert state(a)['canReveal'] and state(b)['canReveal']
        assert request(alice, 'api/session/reveal', 'POST')[0] == 200
        assert card(state(a), 'Bob') == 'COFFEE'
        revealed = state(b)
        assert card(revealed, 'Alice') == '21'
        b.close()
        reconnected = open_stream(bob)
        streams.append(reconnected)
        assert state(reconnected)['revision'] == revealed['revision']
        assert request(alice, 'api/session/next', 'POST', {'title': 'Second tour'})[0] == 200
        assert not state(a)['revealed']
        assert all(p['card'] is None for p in state(reconnected)['participants'])
        assert request(visitor, 'api/session/join', 'POST',
            {'firstName': 'Observer', 'lastName': 'Test', 'role': 'VISITOR'})[0] == 200
        assert len(state(a)['participants']) == 3
        assert len(state(reconnected)['participants']) == 3
        assert request(visitor, 'api/session/vote', 'PUT', {'card': '5'})[0] == 403
        assert request(visitor, 'api/session/reveal', 'POST')[0] == 200
        assert state(a)['revealed'] and state(reconnected)['revealed']
        # No state is pushed while idle; a comment maintains this same HTTP connection.
        heartbeat = event(a)
        assert any(line.startswith(':keepalive') for line in heartbeat), heartbeat
        assert not any(line.startswith('data:') for line in heartbeat)
        assert request(bob, 'api/session', 'DELETE')[0] == 200
        while 'event:session-ended' not in event(reconnected):
            pass
        assert len(state(a)['participants']) == 2
        request(alice, 'api/session', 'DELETE')
        request(visitor, 'api/session', 'DELETE')
        print('SSE smoke passed: real WAR, context path, push, privacy, reconnect, roles, reset, heartbeat, leave.')
    except BaseException:
        log.seek(0)
        print(log.read())
        raise
    finally:
        for stream in streams:
            stream.close()
        process.terminate()
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()
