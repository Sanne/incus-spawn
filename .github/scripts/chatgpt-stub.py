#!/usr/bin/env python3
"""A local stand-in for OpenAI's sign-in endpoints and the Codex backend (#1332).

Tests of the ChatGPT session mode drive the real Codex client through the real isx proxy
against this stub, so no test ever needs a ChatGPT account or reaches OpenAI. It implements
what the open-source Codex client (codex-rs, the version the tests record) speaks:

  auth.openai.com  POST /oauth/token    grant_type=refresh_token (JSON body); rotates the
                                         refresh token on every use and answers a reused one
                                         with 400 refresh_token_reused, as OpenAI documents
                   POST /oauth/revoke   recorded, 200
  chatgpt.com      POST /backend-api/codex/responses      one Responses turn as SSE
                   GET  /backend-api/codex/responses      the same turn over a WebSocket
                   GET  /backend-api/codex/models         an empty catalogue
                   GET  /backend-api/wham/accounts/check  the session's one workspace, no
                                                          residency constraint

Everything else is recorded and answered 404, so a test can see what a client asked for.
Backend requests must carry the current access token and its account id, else 401.

Tokens are minted here, random per run, so a test can search for them anywhere a guest can
read and know that a hit is a leak. Nothing here is a real credential.

The TLS listener serves both host names (one certificate with both). A second, plain HTTP
listener on loopback is the test's control channel:

  POST /mint[?ttl=S]    a fresh session: {id_token, access_token, refresh_token, account_id},
                        its access token valid for S seconds (default 3600)
  GET  /log             every request the TLS listener saw, oldest first
  POST /fault?name=ws-unavailable&count=K   answer the next K WebSocket upgrades 503,
                        so Codex falls back to HTTP
  GET  /state           counters: refreshes, rotations, revokes
  GET  /minted          every token minted so far, for a test to search for leaks

Usage: chatgpt-stub.py <cert.pem> <key.pem> <tls-host> <tls-port> <control-port>
"""

import base64
import hashlib
import json
import os
import secrets
import socket
import ssl
import struct
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

ACCOUNT_ID = "acct-stub-" + secrets.token_hex(4)
USER_ID = "user-stub-" + secrets.token_hex(4)
EMAIL = "stub-user@example.invalid"
ACCESS_TTL = 3600
WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

lock = threading.Lock()
log = []
faults = {}
state = {"refreshes": 0, "rotations": 0, "revokes": 0}
# Every refresh token minted, and whether it has been spent
refresh_tokens = {}
access_tokens = set()
minted = []


def b64(data):
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def jwt(claims):
    header = b64(json.dumps({"alg": "HS256", "typ": "JWT"}).encode())
    payload = b64(json.dumps(claims).encode())
    sig = b64(hashlib.sha256((header + "." + payload + secrets.token_hex(8)).encode()).digest())
    return header + "." + payload + "." + sig


def auth_claims():
    return {"chatgpt_plan_type": "pro", "chatgpt_user_id": USER_ID, "user_id": USER_ID,
            "chatgpt_account_id": ACCOUNT_ID, "chatgpt_account_is_fedramp": False}


def mint(ttl=ACCESS_TTL):
    now = int(time.time())
    access = jwt({"iat": now, "exp": now + ttl, "jti": secrets.token_hex(8),
                  "https://api.openai.com/auth": auth_claims()})
    id_token = jwt({"iat": now, "exp": now + 3600, "email": EMAIL, "jti": secrets.token_hex(8),
                    "https://api.openai.com/profile": {"email": EMAIL},
                    "https://api.openai.com/auth": auth_claims()})
    refresh = "rt.stub-" + secrets.token_hex(24)
    with lock:
        refresh_tokens[refresh] = False
        access_tokens.add(access)
        minted.extend([access, id_token, refresh])
    return {"id_token": id_token, "access_token": access, "refresh_token": refresh,
            "account_id": ACCOUNT_ID}


def take_fault(name):
    with lock:
        left = faults.get(name, 0)
        if left <= 0:
            return False
        faults[name] = left - 1
        return True


def sse_turn(text):
    rid = "resp_" + secrets.token_hex(6)
    events = [
        {"type": "response.created", "response": {"id": rid}},
        {"type": "response.output_item.done", "item": {
            "type": "message", "role": "assistant", "id": "msg_" + secrets.token_hex(4),
            "content": [{"type": "output_text", "text": text}]}},
        {"type": "response.completed", "response": {"id": rid, "usage": {
            "input_tokens": 1, "input_tokens_details": None, "output_tokens": 1,
            "output_tokens_details": None, "total_tokens": 2}}},
    ]
    return events


class TlsHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        sys.stderr.write("stub: " + (fmt % args) + "\n")

    def record(self, body):
        host = (self.headers.get("Host") or "").split(":")[0]
        entry = {
            "time": time.time(),
            "method": self.command,
            "host": host,
            "path": self.path,
            "authorization": self.headers.get_all("Authorization") or [],
            "account_id": self.headers.get_all("ChatGPT-Account-Id") or [],
            "upgrade": self.headers.get("Upgrade", ""),
            "body": body.decode("utf-8", "replace")[:4096],
        }
        with lock:
            log.append(entry)
        return host

    def read_body(self):
        length = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(length) if length else b""

    def send_json(self, status, obj):
        data = json.dumps(obj).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def backend_authorized(self):
        auths = self.headers.get_all("Authorization") or []
        accounts = self.headers.get_all("ChatGPT-Account-Id") or []
        if len(auths) != 1 or len(accounts) != 1 or accounts[0] != ACCOUNT_ID:
            return False
        if not auths[0].startswith("Bearer "):
            return False
        with lock:
            return auths[0][len("Bearer "):] in access_tokens

    def do_GET(self):
        host = self.record(b"")
        path = urlsplit(self.path).path
        if host == "chatgpt.com" and path == "/backend-api/codex/responses" \
                and self.headers.get("Upgrade", "").lower() == "websocket":
            return self.websocket()
        if host == "chatgpt.com" and path == "/backend-api/codex/models":
            if not self.backend_authorized():
                return self.send_json(401, {"error": {"message": "unauthorized"}})
            return self.send_json(200, {"models": []})
        if host == "chatgpt.com" and path == "/backend-api/wham/accounts/check":
            if not self.backend_authorized():
                return self.send_json(401, {"error": {"message": "unauthorized"}})
            return self.send_json(200, {"accounts": [{
                "id": ACCOUNT_ID, "plan_type": "pro", "workspace_backend_origin": "NO_CONSTRAINT",
                "account_routing_override": "NO_CONSTRAINT", "name": None, "structure": "personal"}],
                "account_ordering": [ACCOUNT_ID], "default_account_id": ACCOUNT_ID})
        self.send_json(404, {"error": {"message": "not in the stub: " + path}})

    def do_POST(self):
        body = self.read_body()
        host = self.record(body)
        path = urlsplit(self.path).path
        if host == "auth.openai.com" and path == "/oauth/token":
            return self.token(body)
        if host == "auth.openai.com" and path == "/oauth/revoke":
            with lock:
                state["revokes"] += 1
            return self.send_json(200, {})
        if host == "chatgpt.com" and path == "/backend-api/codex/responses":
            if not self.backend_authorized():
                return self.send_json(401, {"error": {"message": "unauthorized"}})
            data = "".join("event: %s\ndata: %s\n\n" % (e["type"], json.dumps(e))
                           for e in sse_turn("stub turn over http")).encode()
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            return
        self.send_json(404, {"error": {"message": "not in the stub: " + path}})

    def token(self, body):
        try:
            req = json.loads(body or b"{}")
        except ValueError:
            return self.send_json(400, {"error": {"code": "invalid_request"}})
        if req.get("grant_type") != "refresh_token":
            return self.send_json(400, {"error": {"code": "unsupported_grant_type"}})
        with lock:
            state["refreshes"] += 1
        token = req.get("refresh_token", "")
        with lock:
            spent = refresh_tokens.get(token)
            if spent is None:
                return self.send_json(400, {"error": {"code": "invalid_grant", "message": "unknown"}})
            if spent:
                return self.send_json(400, {"error": {"code": "refresh_token_reused", "message": "reused"}})
            refresh_tokens[token] = True
            state["rotations"] += 1
        fresh = mint()
        self.send_json(200, {"id_token": fresh["id_token"], "access_token": fresh["access_token"],
                             "refresh_token": fresh["refresh_token"]})

    def websocket(self):
        if take_fault("ws-unavailable"):
            return self.send_json(503, {"error": {"message": "websockets unavailable"}})
        if not self.backend_authorized():
            return self.send_json(401, {"error": {"message": "unauthorized"}})
        key = self.headers.get("Sec-WebSocket-Key", "")
        accept = base64.b64encode(hashlib.sha1((key + WS_GUID).encode()).digest()).decode()
        self.send_response(101)
        self.send_header("Upgrade", "websocket")
        self.send_header("Connection", "Upgrade")
        self.send_header("Sec-WebSocket-Accept", accept)
        self.end_headers()
        self.wfile.flush()
        self.close_connection = True
        while True:
            frame = read_frame(self.rfile)
            if frame is None:
                return
            opcode, payload = frame
            if opcode == 8:
                write_frame(self.wfile, 8, payload[:2])
                return
            if opcode == 9:
                write_frame(self.wfile, 10, payload)
                continue
            if opcode != 1:
                continue
            with lock:
                log.append({"time": time.time(), "method": "WS", "host": "chatgpt.com",
                            "path": "/backend-api/codex/responses", "authorization": [],
                            "account_id": [], "upgrade": "message",
                            "body": payload.decode("utf-8", "replace")[:4096]})
            try:
                message = json.loads(payload)
            except ValueError:
                continue
            if message.get("type") == "response.create":
                for event in sse_turn("stub turn over websocket"):
                    write_frame(self.wfile, 1, json.dumps(event).encode())


def read_exact(stream, n):
    data = b""
    while len(data) < n:
        chunk = stream.read(n - len(data))
        if not chunk:
            return None
        data += chunk
    return data


def read_frame(stream):
    head = read_exact(stream, 2)
    if head is None:
        return None
    opcode = head[0] & 0x0F
    masked = head[1] & 0x80
    length = head[1] & 0x7F
    if length == 126:
        length = struct.unpack(">H", read_exact(stream, 2))[0]
    elif length == 127:
        length = struct.unpack(">Q", read_exact(stream, 8))[0]
    mask = read_exact(stream, 4) if masked else b"\0\0\0\0"
    payload = read_exact(stream, length) if length else b""
    if payload is None:
        return None
    return opcode, bytes(b ^ mask[i % 4] for i, b in enumerate(payload))


def write_frame(stream, opcode, payload):
    head = bytes([0x80 | opcode])
    n = len(payload)
    if n < 126:
        head += bytes([n])
    elif n < 65536:
        head += bytes([126]) + struct.pack(">H", n)
    else:
        head += bytes([127]) + struct.pack(">Q", n)
    stream.write(head + payload)
    stream.flush()


class ControlHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        pass

    def reply(self, obj):
        data = json.dumps(obj).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        path = urlsplit(self.path).path
        with lock:
            if path == "/log":
                return self.reply(log)
            if path == "/minted":
                return self.reply(minted)
            if path == "/state":
                return self.reply(dict(state, faults=dict(faults)))
        self.send_error(404)

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        if length:
            self.rfile.read(length)
        url = urlsplit(self.path)
        if url.path == "/mint":
            return self.reply(mint(int(parse_qs(url.query).get("ttl", [ACCESS_TTL])[0])))
        if url.path == "/fault":
            query = parse_qs(url.query)
            name = query.get("name", [""])[0]
            count = int(query.get("count", ["1"])[0])
            with lock:
                faults[name] = count
            return self.reply({"armed": name})
        self.send_error(404)


def main():
    cert, key, host, port, control_port = sys.argv[1:6]
    tls = ThreadingHTTPServer((host, int(port)), TlsHandler)
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(cert, key)
    tls.socket = context.wrap_socket(tls.socket, server_side=True)
    control = ThreadingHTTPServer(("127.0.0.1", int(control_port)), ControlHandler)
    threading.Thread(target=control.serve_forever, daemon=True).start()
    sys.stderr.write("chatgpt-stub: TLS on %s:%s, control on 127.0.0.1:%s\n" % (host, port, control_port))
    tls.serve_forever()


if __name__ == "__main__":
    main()
