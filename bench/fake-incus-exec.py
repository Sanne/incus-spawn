#!/usr/bin/env python3
"""A fake Incus that answers one exec at once, for bench/poller-mode.sh (#2129).

It serves the instance read, the exec POST, the four exec websockets (each upgraded, then sent
a close frame) and the operation's /wait (exit 0) on a Unix socket, so `isx ssh-proxy x` runs
its whole exec relay against it and exits. Nothing here waits, so a slow run is isx waiting.

Usage: fake-incus-exec.py <socket path>
"""
import base64, hashlib, json, os, socket, sys, threading

path = sys.argv[1]
try:
    os.unlink(path)
except FileNotFoundError:
    pass
srv = socket.socket(socket.AF_UNIX)
srv.bind(path)
os.chmod(path, 0o777)
srv.listen(64)


def reply(c, body, code=200):
    b = json.dumps(body).encode()
    c.sendall(b"HTTP/1.1 %d OK\r\nContent-Type: application/json\r\nContent-Length: %d\r\n\r\n" % (code, len(b)) + b)


def serve(c):
    try:
        f = c.makefile("rb")
        while True:
            line = f.readline()
            if not line:
                return
            method, target, _ = line.decode().split(" ", 2)
            headers = {}
            while True:
                h = f.readline().decode().strip()
                if not h:
                    break
                k, v = h.split(":", 1)
                headers[k.strip().lower()] = v.strip()
            n = int(headers.get("content-length", "0"))
            if n:
                f.read(n)
            if headers.get("upgrade", "").lower() == "websocket":
                accept = base64.b64encode(hashlib.sha1(
                    (headers["sec-websocket-key"] + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()).digest())
                c.sendall(b"HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                          b"Sec-WebSocket-Accept: " + accept + b"\r\n\r\n")
                c.sendall(b"\x88\x02\x03\xe8")  # close, 1000
                while c.recv(65536):
                    pass
                return
            if method == "POST" and "/exec" in target:
                reply(c, {"type": "async", "status_code": 100, "status": "Operation created",
                          "operation": "/1.0/operations/op1",
                          "metadata": {"id": "op1", "class": "websocket", "status": "Running",
                                       "metadata": {"fds": {"0": "s0", "1": "s1", "2": "s2", "control": "sc"}}}}, 202)
            elif "/wait" in target:
                reply(c, {"type": "sync", "status_code": 200, "status": "Success",
                          "metadata": {"id": "op1", "status": "Success", "status_code": 200, "metadata": {"return": 0}}})
            else:
                reply(c, {"type": "sync", "status_code": 200, "status": "Success",
                          "metadata": {"name": "x", "status": "Running", "type": "container", "config": {},
                                       "api_extensions": [], "environment": {"server_version": "7.6"}}})
    except OSError:
        pass  # isx closed the socket first: the next run goes on


while True:
    conn, _ = srv.accept()
    threading.Thread(target=serve, args=(conn,), daemon=True).start()
