// Runs only inside a worker. Node is already installed by CodexSetup.
const fs = require('node:fs');
const path = require('node:path');
const {spawn} = require('node:child_process');
const {StringDecoder} = require('node:string_decoder');
const [dir, run, sandbox, model] = process.argv.slice(1);
const LIMIT = 1024 * 1024;
const validId = id => typeof id === 'string' && /^[a-zA-Z0-9][a-zA-Z0-9_-]{0,127}$/.test(id);
let scratch;
function read(file, limit = LIMIT, optional = false) {
  let fd;
  try {
    fd = fs.openSync(file, fs.constants.O_RDONLY | fs.constants.O_NONBLOCK | fs.constants.O_NOFOLLOW);
    const st = fs.fstatSync(fd);
    if (!st.isFile()) throw new Error('not a regular file: ' + file);
    const buf = Buffer.alloc(Math.min(st.size, limit));
    let used = 0, n;
    while (used < buf.length && (n = fs.readSync(fd, buf, used, buf.length - used, null)) > 0) used += n;
    return {text: utf8(buf.subarray(0, used)), bytes: st.size};
  } catch (e) {
    if (optional) return {text: '', bytes: 0};
    throw e;
  } finally { if (fd !== undefined) fs.closeSync(fd); }
}
// A cut never splits a character: an incomplete one at the end is left out, not replaced.
const utf8 = buf => new StringDecoder('utf8').write(buf);
function publish(name, text) {
  const tmp = path.join(scratch, 'publish-' + Math.random().toString(36).slice(2));
  fs.writeFileSync(tmp, text, {flag: 'wx', mode: 0o600});
  fs.renameSync(tmp, path.join(dir, name));
}
function output(name) {
  const tmp = path.join(scratch, name);
  const fd = fs.openSync(tmp, 'wx', 0o600);
  fs.renameSync(tmp, path.join(dir, name));
  return fd;
}
try {
  scratch = fs.mkdtempSync(path.join(dir, '.codex-'));
  const saved = read(path.join(dir, 'session_id'), 128, true);
  let session = saved.bytes <= 128 ? saved.text : '';
  if (run !== '1' && !validId(session)) {
    // The opening event can survive a kill that interrupted publishing the ID.
    const prefix = read(path.join(dir, 'events-1.jsonl'), LIMIT, true).text;
    for (const line of prefix.split('\n')) {
      try {
        const e = JSON.parse(line);
        if (e.type === 'thread.started' && validId(e.thread_id)) { session = e.thread_id; break; }
      } catch {}
    }
    if (!validId(session)) throw new Error('No saved Codex thread ID; start a new delegated task.');
    publish('session_id', session);
  }
  if (run === 'recover') {
    console.log(session);
    fs.rmSync(scratch, {recursive: true, force: true});
    process.exit(0);
  }
  const input = read(path.join(dir, 'prompt-' + run + '.md'));
  if (input.bytes > LIMIT) throw new Error('Codex task input exceeds 1 MiB');
  let prompt = input.text;
  if (run === '1') {
    const primer = read('/etc/claude-code/CLAUDE.md', 65536, true).text
      || 'You are in a disposable isx instance. Passwordless sudo is available. Credentials are injected by the host proxy; do not search for API keys.';
    prompt = primer + '\n\n' + read(path.join(dir, 'brief.md'), 16384).text + '\n\n' + prompt;
  }
  const reportFile = path.join(scratch, 'last-message');
  const argv = ['exec'];
  if (run !== '1') argv.push('resume');
  argv.push('--json', '--skip-git-repo-check', '-c', 'approval_policy="never"',
    '-c', 'sandbox_mode=' + JSON.stringify(sandbox), '--output-last-message', reportFile);
  if (model) argv.push('--model', model);
  if (run !== '1') argv.push(session);
  argv.push('-');
  const events = output('events-' + run + '.jsonl');
  const stderr = output('stderr-' + run + '.log');
  const child = spawn('codex', argv, {stdio: ['pipe', 'pipe', stderr]});
  let pending = '', dropping = false, malformed = false, terminal = '', error = '', usage, lastMessage = '';
  const decoder = new StringDecoder('utf8');
  function event(line) {
    if (!line.trim()) return;
    let e;
    try { e = JSON.parse(line); } catch { malformed = true; return; }
    if (e.type === 'thread.started') {
      if (!validId(e.thread_id) || (run !== '1' && e.thread_id !== session)) {
        malformed = true; error = 'Codex emitted an invalid or different thread ID';
      } else { session = e.thread_id; publish('session_id', session); }
    }
    if (e.type === 'turn.completed') {
      terminal = 'success'; usage = {};
      for (const key of ['input_tokens', 'output_tokens', 'cached_input_tokens'])
        if (Number.isSafeInteger(e.usage?.[key]) && e.usage[key] >= 0) usage[key] = e.usage[key];
    }
    if (e.type === 'turn.failed') { terminal = 'failed'; error = String(e.error?.message || 'Codex turn failed').slice(0, 2000); }
    if (e.type === 'error') error = String(e.message || e.error?.message || 'Codex error').slice(0, 2000);
    if (e.type === 'item.completed' && e.item?.type === 'agent_message') lastMessage = String(e.item.text || '');
  }
  child.stdout.on('data', data => {
    try {
      let offset = 0;
      while (offset < data.length) offset += fs.writeSync(events, data, offset, data.length - offset);
      const parts = decoder.write(data).split('\n');
      for (let i = 0; i < parts.length; i++) {
        if (!dropping) pending += parts[i];
        if (pending.length > LIMIT) { pending = ''; dropping = true; }
        if (i < parts.length - 1) {
          if (!dropping) event(pending);
          pending = ''; dropping = false;
        }
      }
    } catch (e) { malformed = true; error = e.message.slice(0, 2000); child.kill('SIGTERM'); }
  });
  child.on('error', e => { error = e.message; });
  child.stdin.on('error', () => {}); // A CLI startup failure can close stdin immediately.
  child.stdin.end(prompt);
  child.on('close', (code, signal) => {
    try {
      pending += decoder.end();
      if (pending && !dropping) event(pending);
      const report = read(reportFile, LIMIT, true);
      const message = report.bytes ? null : Buffer.from(lastMessage);
      const text = message ? utf8(message.subarray(0, LIMIT)) : report.text;
      const bytes = message ? message.length : report.bytes;
      publish('result-' + run + '.txt', text);
      publish('outcome-' + run + '.json', JSON.stringify({type: 'isx.codex.result',
        success: code === 0 && terminal === 'success' && !malformed,
        error: error || (terminal ? '' : 'Codex ended without a terminal event'),
        usage, report_bytes: bytes, report_truncated: bytes > Buffer.byteLength(text)}));
      process.exitCode = code === 0 && terminal === 'success' && !malformed ? 0 : (code || (signal ? 143 : 1));
    } catch (e) { console.error(e.message); process.exitCode = 125; }
    finally { fs.closeSync(events); fs.closeSync(stderr); fs.rmSync(scratch, {recursive: true, force: true}); }
  });
} catch (e) {
  console.error(e.message);
  if (scratch) fs.rmSync(scratch, {recursive: true, force: true});
  process.exitCode = 125;
}
