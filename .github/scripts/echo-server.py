#!/usr/bin/env python3
"""Minimal HTTPS server that echoes request headers as JSON.

Used by CI integration tests to verify the MITM proxy injects credentials
into upstream requests. Expects TLS cert/key paths as arguments.
Listens on 0.0.0.0:443.
"""
import json
import ssl
import sys
from http.server import HTTPServer, BaseHTTPRequestHandler

class EchoHandler(BaseHTTPRequestHandler):
    def _echo(self):
        length = int(self.headers.get("Content-Length", 0))
        if length:
            self.rfile.read(length)
        headers = {k: v for k, v in self.headers.items()}
        body = json.dumps({"headers": headers}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    do_GET = do_POST = do_PUT = do_DELETE = do_PATCH = _echo

    def log_message(self, fmt, *args):
        pass

if __name__ == "__main__":
    cert, key = sys.argv[1], sys.argv[2]
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.load_cert_chain(cert, key)
    server = HTTPServer(("0.0.0.0", 443), EchoHandler)
    server.socket = ctx.wrap_socket(server.socket, server_side=True)
    server.serve_forever()
