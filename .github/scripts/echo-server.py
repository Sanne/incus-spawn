#!/usr/bin/env python3
"""Minimal HTTPS server that echoes request headers as JSON.

Used by CI integration tests to verify the MITM proxy injects credentials
into upstream requests. Expects TLS cert/key paths as arguments.
Listens on 0.0.0.0:443.

It also answers GitHub's /user and /user/emails, deriving the identity from the
token the proxy injected: a bearer token of the form ``ghp-acct-<name>`` is
answered as the user ``<name>``. That is what lets a test assert an instance
committed as the account it is pinned to, without any real GitHub credential --
and unlike a real token, it can play two identities at once.
"""
import json
import re
import ssl
import sys
from http.server import HTTPServer, BaseHTTPRequestHandler

# Tokens the proxy injects for an account-scoped test credential.
ACCOUNT_TOKEN = re.compile(r"^ghp-acct-([a-z0-9][a-z0-9-]*)$")

# Any other bearer token -- notably the real CI token, which is the default
# account -- answers as this login. A template that installs `gh` resolves its
# git identity at build time, and that request lands here too: without an
# identity to give it, the build fails outright.
DEFAULT_LOGIN = "ci-user"


def account_from_auth(header):
    """The login a bearer token should answer as, or None if it is not a bearer."""
    if not header:
        return None
    parts = header.split(None, 1)
    if len(parts) != 2 or parts[0].lower() != "bearer":
        return None
    match = ACCOUNT_TOKEN.match(parts[1].strip())
    return match.group(1) if match else DEFAULT_LOGIN


class EchoHandler(BaseHTTPRequestHandler):
    def _send(self, payload):
        body = json.dumps(payload).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _echo(self):
        length = int(self.headers.get("Content-Length", 0))
        if length:
            self.rfile.read(length)

        # Impersonate GitHub only on the two identity endpoints, so every other
        # request -- including the existing "was a Bearer injected at all"
        # assertions, which use "/" -- keeps getting the plain header echo.
        account = account_from_auth(self.headers.get("Authorization"))
        path = self.path.split("?", 1)[0]
        if account and path == "/user":
            self._send({
                "login": account,
                "name": account.title() + " Bot",
                "email": None,
            })
            return
        if account and path == "/user/emails":
            self._send([{
                "email": account + "@accounts.test",
                "primary": True,
                "verified": True,
                "visibility": "private",
            }])
            return

        self._send({"headers": {k: v for k, v in self.headers.items()}})

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
