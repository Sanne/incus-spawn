#!/usr/bin/env node
// A stdio MCP server that forwards to a Streamable HTTP one (#2476), for an agent that reads no
// variable and runs no header helper in its MCP config (Antigravity CLI). A credential in a file
// (--header-file) is read when each request is sent, so a rotation reaches the next request. One in
// a variable (--bearer-env) is the bridge's environment as agy started it, and changes only when
// agy starts the bridge again.
//
//   node isx-mcp-bridge.cjs --url <https url> --bearer-env <VAR>
//   node isx-mcp-bridge.cjs --url <https url> --header <Name> --header-file <path>
'use strict';
const fs = require('fs');
const readline = require('readline');

const args = process.argv.slice(2);
const opt = (name) => { const i = args.indexOf(name); return i >= 0 ? args[i + 1] : undefined; };
const url = opt('--url');
const bearerEnv = opt('--bearer-env');
const headerName = opt('--header');
const headerFile = opt('--header-file');
if (!url || (!bearerEnv && !(headerName && headerFile))) {
  process.stderr.write('usage: isx-mcp-bridge --url https://… (--bearer-env VAR | --header NAME --header-file PATH)\n');
  process.exit(2);
}

/** The credential header, read now: its variable or file, whichever the bridge was given. */
function credentialHeaders() {
  if (bearerEnv) {
    const token = process.env[bearerEnv];
    return token ? { Authorization: 'Bearer ' + token } : {};
  }
  try {
    const value = fs.readFileSync(headerFile, 'utf8').trim();
    return value ? { [headerName]: value } : {};
  } catch (e) {
    return {};
  }
}

let sessionId;
let protocolVersion;
let initialized = Promise.resolve();
let initMessage;

const out = (message) => process.stdout.write(JSON.stringify(message) + '\n');
const fail = (id, text) => { if (id !== undefined && id !== null) out({ jsonrpc: '2.0', id, error: { code: -32000, message: text } }); };

/** The data of each event of a text/event-stream body, as it arrives. */
async function* events(body) {
  const decoder = new TextDecoder();
  let buffer = '';
  for await (const chunk of body) {
    buffer += decoder.decode(chunk, { stream: true });
    let end;
    while ((end = buffer.search(/\r?\n\r?\n/)) >= 0) {
      const block = buffer.slice(0, end);
      buffer = buffer.slice(end).replace(/^\r?\n\r?\n/, '');
      const data = block.split(/\r?\n/).filter((l) => l.startsWith('data:')).map((l) => l.slice(5).replace(/^ /, '')).join('\n');
      if (data) yield data;
    }
  }
  const tail = buffer.split(/\r?\n/).filter((l) => l.startsWith('data:')).map((l) => l.slice(5).replace(/^ /, '')).join('\n');
  if (tail) yield tail;
}

/** Each JSON-RPC text of a response: the data of every event of a stream, or its body whole. */
async function* texts(response) {
  if ((response.headers.get('content-type') || '').includes('text/event-stream')) yield* events(response.body);
  else yield await response.text();
}

const discard = async (response) => { if (response.body) await response.body.cancel().catch(() => {}); };

function post(message) {
  const headers = {
    'Content-Type': 'application/json',
    Accept: 'application/json, text/event-stream',
    ...credentialHeaders(),
  };
  if (sessionId) headers['Mcp-Session-Id'] = sessionId;
  if (protocolVersion) headers['MCP-Protocol-Version'] = protocolVersion;
  // A redirect would carry the secret header to another origin
  return fetch(url, { method: 'POST', headers, body: JSON.stringify(message), redirect: 'error' });
}

/** The server ended our session (another client initialized, or it restarted): initialize again, with what agy sent the first time. */
let renewing = null;
function renew() {
  renewing = renewing || (async () => {
    sessionId = undefined;
    protocolVersion = undefined;
    const response = await post({ ...initMessage, id: 'isx-renew' });
    const session = response.headers.get('mcp-session-id');
    if (session) sessionId = session;
    if (!response.ok) { await discard(response); return false; }
    for await (const text of texts(response)) {
      try {
        const parsed = JSON.parse(text);
        if (parsed && parsed.result && parsed.result.protocolVersion) protocolVersion = parsed.result.protocolVersion;
      } catch (e) { /* not the answer */ }
    }
    await discard(await post({ jsonrpc: '2.0', method: 'notifications/initialized' }));
    return true;
  })().catch(() => false).finally(() => { renewing = null; });
  return renewing;
}

async function send(message, retried) {
  const isRequest = message.method !== undefined && message.id !== undefined;
  const isInitialize = isRequest && message.method === 'initialize';
  const failed = (text) => { if (isRequest) fail(message.id, text); };
  if (isInitialize) initMessage = message;
  else await initialized;
  const used = sessionId;
  let response;
  try {
    response = await post(message);
  } catch (e) {
    return failed('the MCP server could not be reached: ' + (e.cause && e.cause.code || e.message));
  }
  const session = response.headers.get('mcp-session-id');
  if (session) sessionId = session;
  if (response.status === 404 && used && !isInitialize && !retried && initMessage) {
    await discard(response);
    if (sessionId !== used || await renew()) return send(message, true);
    return failed('the MCP server ended the session and would not start another');
  }
  if (response.status === 202 || !isRequest) {
    await discard(response);
    if (!response.ok) failed('the MCP server answered ' + response.status);
    return;
  }
  if (!response.ok) {
    await discard(response);
    return failed('the MCP server answered ' + response.status);
  }
  let answered = false;
  try {
    for await (const text of texts(response)) {
      if (relay(text, isInitialize, message.id)) answered = true;
    }
  } catch (e) {
    return failed('the MCP server\'s answer was cut off: ' + e.message);
  }
  if (!answered) failed('the MCP server sent no answer to the request');
}

/** Writes a JSON-RPC message through; whether it is the response to request {@code id}. */
function relay(text, isInitialize, id) {
  let parsed;
  try { parsed = JSON.parse(text); } catch (e) { return false; }
  if (isInitialize && parsed && parsed.result && parsed.result.protocolVersion) protocolVersion = parsed.result.protocolVersion;
  out(parsed);
  return parsed !== null && typeof parsed === 'object' && parsed.id === id && (parsed.result !== undefined || parsed.error !== undefined);
}

process.stdout.on('error', () => process.exit(0)); // agy is gone

const pending = new Set();
const rl = readline.createInterface({ input: process.stdin });
rl.on('line', (line) => {
  if (!line.trim()) return;
  let message;
  try { message = JSON.parse(line); } catch (e) { return; }
  if (message === null || typeof message !== 'object' || Array.isArray(message)) return;
  const run = send(message, false).catch((e) => {
    if (message.method !== undefined) fail(message.id, e.message);
  });
  if (message.method === 'initialize') initialized = run;
  pending.add(run);
  run.finally(() => pending.delete(run));
});
rl.on('close', async () => {
  await Promise.allSettled([...pending]);
  if (sessionId) {
    try { await fetch(url, { method: 'DELETE', headers: { 'Mcp-Session-Id': sessionId, ...credentialHeaders() }, redirect: 'error' }); } catch (e) { /* ending anyway */ }
  }
  process.exit(0);
});
