// Appended to runner-io.cjs. Node is already installed by PiSetup.
// Run 1 chooses the session ID before Pi starts, and every run passes it with --session-id, so
// there is nothing to capture. Pi prints the session header first but writes the session file only
// once a user or assistant message is appended, right after it emits that message's message_end.
// So run 1 publishes session_id at the record after the first such message_end: a task with one
// has a conversation on disk to continue, and one cancelled before it has none and is not
// resumable. Pi creates a session it cannot find under that ID, so a resume checks that the
// session header is run 1's, and stops Pi before it starts a new conversation in place of the old one.
const [model, effort] = process.argv.slice(3);
const crypto = require('node:crypto');
// Pi's agent_end repeats the whole conversation: a record that large is not needed, and dropped.
const RECORD_LIMIT = 8 * LIMIT;
try {
  scratch = fs.mkdtempSync(path.join(dir, '.pi-'));
  let session, started = '';
  if (run === '1') {
    session = crypto.randomUUID();
  } else {
    const saved = read(path.join(dir, 'session_id'), 128, true);
    session = saved.bytes <= 128 ? saved.text : '';
    started = read(path.join(dir, 'session_started'), 64, true).text;
    if (!validId(session) || !started) throw new Error('No saved Pi session; start a new delegated task.');
  }
  const input = read(path.join(dir, 'prompt-' + run + '.md'));
  if (input.bytes > LIMIT) throw new Error('Pi task input exceeds 1 MiB');
  // The isx primer is /AGENTS.md, which Pi loads from every working directory.
  const argv = ['-p', '--mode', 'json', '--session-id', session,
    '--append-system-prompt', read(path.join(dir, 'brief.md'), 16384).text];
  if (model) argv.push('--model', model);
  // A task's own thinking level; without one, settings.json's, which the template's effort wrote.
  if (effort) argv.push('--thinking', effort);
  const events = output('events-' + run + '.jsonl');
  const stderr = output('stderr-' + run + '.log');
  // Run 1 creates its session on purpose, so Pi's warning that it is creating one would only read,
  // to a coordinator, as a lost session; a resume keeps it, where it would mean exactly that.
  const created = "Warning: No project session found with id '" + session + "'; creating a new session with that id.";
  const child = spawn('pi', argv, {stdio: ['pipe', 'pipe', run === '1' ? 'pipe' : stderr]});
  const diagnostics = run === '1' && lines(line => {
    if (line.replace(/\r$/, '') !== created) copy(stderr, Buffer.from(line + '\n'));
  }, LIMIT, start => copy(stderr, Buffer.from(start + '... (cut)\n')));
  if (diagnostics) child.stderr.on('data', data => { try { diagnostics.write(data); } catch {} });
  let header = false, settled = false, aborted = false, malformed = false, error = '', stopReason = '';
  let lastMessage = '', lost = false, stopped = false, turns = 0, cost = 0, costed = false;
  // Whether session_id is saved (a resume's already is), and whether Pi has written the session file.
  let saved = run !== '1', persisted = false;
  const usage = {};
  // Stops Pi, and reading it: what it prints after this does not change why it was stopped.
  const fail = message => { malformed = stopped = true; error = message; child.kill('SIGTERM'); };
  const count = (key, value) => {
    if (Number.isSafeInteger(value) && value >= 0) usage[key] = (usage[key] || 0) + value;
  };
  function event(line) {
    if (stopped || !line.trim()) return;
    let e;
    try { e = JSON.parse(line); } catch { malformed = true; return; }
    if (!header) {
      // The first record names the session Pi opened; anything else first is not Pi's JSON mode.
      if (e.type !== 'session' || e.id !== session || typeof e.timestamp !== 'string' || !e.timestamp) {
        return fail('Pi did not open session ' + session);
      }
      if (run === '1') publish('session_started', e.timestamp.slice(0, 64));
      else if (e.timestamp !== started) {
        return fail('Pi no longer has session ' + session + ' and would start a new one; start a new delegated task.');
      }
      header = true;
      return;
    }
    // Pi appends a message, and so writes the file, before it emits anything after its message_end.
    if (persisted && !saved) { publish('session_id', session); saved = true; }
    if (e.type === 'message_end' && ['user', 'assistant'].includes(e.message?.role)) persisted = true;
    if (e.type === 'turn_end') turns++;
    if (e.type === 'agent_settled') { settled = true; aborted = e.aborted === true; }
    if (e.type === 'auto_retry_end' && e.success === false) error = String(e.finalError || 'Pi gave up retrying').slice(0, 2000);
    if (e.type === 'message_end' && e.message?.role === 'assistant') {
      const m = e.message;
      lost = false;
      stopReason = String(m.stopReason || '');
      error = m.errorMessage ? String(m.errorMessage).slice(0, 2000) : '';
      lastMessage = (Array.isArray(m.content) ? m.content : [])
        .filter(b => b?.type === 'text' && typeof b.text === 'string').map(b => b.text).join('\n');
      count('input_tokens', m.usage?.input);
      count('output_tokens', m.usage?.output);
      count('cached_input_tokens', m.usage?.cacheRead);
      count('cache_creation_input_tokens', m.usage?.cacheWrite);
      const c = m.usage?.cost?.total;
      if (typeof c === 'number' && Number.isFinite(c) && c >= 0) { cost += c; costed = true; }
    }
  }
  // A final message too large to keep is never replaced by an earlier one.
  const records = lines(event, RECORD_LIMIT, start => { if (start.includes('"message_end"')) lost = true; });
  child.stdout.on('data', data => {
    try {
      copy(events, data);
      records.write(data);
    } catch (e) { fail(e.message.slice(0, 2000)); }
  });
  child.on('error', e => { error = e.message; });
  child.stdin.on('error', () => {}); // A CLI startup failure can close stdin immediately.
  child.stdin.end(input.text);
  child.on('close', (code, signal) => {
    try {
      records.end();
      if (diagnostics) diagnostics.end();
      const failed = ['error', 'aborted'].includes(stopReason);
      const success = code === 0 && header && settled && !aborted && !failed && !malformed && !lost;
      const message = Buffer.from(lost ? '' : lastMessage);
      const text = utf8(message.subarray(0, LIMIT));
      publish('result-' + run + '.txt', text);
      publish('outcome-' + run + '.json', JSON.stringify({type: 'isx.pi.result', success,
        error: error || (success ? '' : !header ? 'Pi ended before opening its session'
          : !settled ? 'Pi ended without settling' : aborted || stopReason === 'aborted' ? 'Pi was aborted'
          : lost ? 'Pi\'s final message was too large to keep' : 'Pi failed'),
        usage, turns, cost_usd: costed ? cost : undefined,
        report_bytes: message.length, report_truncated: message.length > Buffer.byteLength(text)}));
      process.exitCode = success ? 0 : (code || (signal ? 143 : 1));
    } catch (e) { console.error(e.message); process.exitCode = 125; }
    finally { fs.closeSync(events); fs.closeSync(stderr); fs.rmSync(scratch, {recursive: true, force: true}); }
  });
} catch (e) {
  console.error(e.message);
  if (scratch) fs.rmSync(scratch, {recursive: true, force: true});
  process.exitCode = 125;
}
