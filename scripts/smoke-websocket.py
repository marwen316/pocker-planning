"""Exercise the packaged JAR with real HTTP sessions and WebSocket connections (stdlib only)."""
import argparse
import base64
import hashlib
import os
import struct
import http.cookiejar
import json
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('archive', nargs='?', default='target/pocker-planning.jar')
parser.add_argument('--java', default='java')
args = parser.parse_args()
with socket.socket() as reservation:
    reservation.bind(('127.0.0.1', 0))
    port = reservation.getsockname()[1]
root = f'http://127.0.0.1:{port}/'


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


class SocketStream:
    def __init__(self, sock, reader):
        self.sock, self.reader = sock, reader
    def exact(self, count):
        value = self.reader.read(count)
        if len(value) != count:
            raise AssertionError("WebSocket closed unexpectedly")
        return value
    def message(self):
        first, second = self.exact(2)
        length = second & 127
        if length == 126: length = struct.unpack("!H", self.exact(2))[0]
        if length == 127: length = struct.unpack("!Q", self.exact(8))[0]
        assert not (second & 128), "Server frames must not be masked"
        payload = self.exact(length)
        if first & 15 == 8:
            assert struct.unpack("!H", payload[:2])[0] == 1008
            return {"type":"session-ended"}
        assert first & 128 and first & 15 == 1, "Expected final text frame"
        return json.loads(payload)
    def close(self):
        self.reader.close()
        self.sock.close()


def event(stream):
    return stream.message()


def state(stream):
    while True:
        message = event(stream)
        if message["type"] == "state":
            return message["data"]


def open_stream(client, expected=101, origin=None):
    url = root + "api/session/ws"
    req = urllib.request.Request(url)
    for handler in client.handlers:
        if isinstance(handler, urllib.request.HTTPCookieProcessor):
            handler.cookiejar.add_cookie_header(req)
    key = base64.b64encode(os.urandom(16)).decode()
    sock = socket.create_connection(("127.0.0.1",port),timeout=25)
    reader = sock.makefile("rb")
    headers = {"Host":f"127.0.0.1:{port}", "Upgrade":"websocket",
        "Connection":"Upgrade", "Sec-WebSocket-Key":key, "Sec-WebSocket-Version":"13",
        "Origin": origin or root.rstrip("/")}
    if req.get_header("Cookie"): headers["Cookie"] = req.get_header("Cookie")
    sock.sendall(("GET /api/session/ws HTTP/1.1\r\n" +
        "".join(f"{k}: {v}\r\n" for k,v in headers.items()) + "\r\n").encode())
    status = int(reader.readline().split()[1])
    response_headers = {}
    while True:
        line = reader.readline().decode().strip()
        if not line: break
        k,v = line.split(":",1); response_headers[k.lower()] = v.strip()
    assert status == expected, (status, response_headers)
    if status != 101:
        reader.close();sock.close();return None
    accept=base64.b64encode(hashlib.sha1((key+"258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()).digest()).decode()
    assert response_headers["sec-websocket-accept"] == accept
    return SocketStream(sock,reader)


def card(snapshot, name):
    return next(p['card'] for p in snapshot['participants'] if p['firstName'] == name)


streams = []
with tempfile.TemporaryFile(mode='w+') as log:
    process = subprocess.Popen([args.java, '-jar', args.archive, f'--server.port={port}'], stdout=log, stderr=subprocess.STDOUT)
    try:
        alice, bob, visitor = browser(), browser(), browser()
        for _ in range(80):
            try:
                status, html = request(alice)
                break
            except urllib.error.URLError:
                if process.poll() is not None:
                    raise AssertionError('JAR exited during startup')
                time.sleep(.25)
        else:
            raise AssertionError('JAR did not start in 20 seconds')
        assert status == 200 and '<app-root>' in html
        open_stream(visitor, expected=401)
        for client, name in [(alice, 'Alice'), (bob, 'Bob')]:
            assert request(client, 'api/session/join', 'POST',
                {'firstName': name, 'lastName': 'Test', 'role': 'DEVELOPER'})[0] == 200
        open_stream(alice, expected=403, origin="https://other.example")
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
        # No state is pushed while idle; a heartbeat maintains the same WebSocket connection.
        heartbeat = event(a)
        assert heartbeat["type"] == "heartbeat", heartbeat
        assert request(bob, 'api/session', 'DELETE')[0] == 200
        while event(reconnected)["type"] != "session-ended":
            pass
        assert len(state(a)['participants']) == 2
        request(alice, 'api/session', 'DELETE')
        request(visitor, 'api/session', 'DELETE')
        print('WebSocket smoke passed: real JAR, context path, push, privacy, reconnect, roles, reset, heartbeat, leave.')
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
