// Appended to runner-io.cjs. Node is installed by AntigravitySetup for this runner.
// agy names its conversation itself (a UUID), and its first stream-json event, init, carries it, so
// run 1 saves it as the opencode runner saves its session. agy reads the prompt from stdin as one
// stream-json user message: an argument would be capped at 128 KiB. It prints init only once it
// read that message, so it cannot be stopped before it works: --conversation with an ID it cannot
// find only warns and opens a new conversation. A resume therefore first checks that the
// conversation's file is still there, then stops agy if init names another one.
const [model] = process.argv.slice(3);
const os = require('node:os');
// A tool's output (a file it read) can be large, and a record that large is not needed: dropped.
const RECORD_LIMIT = 8 * LIMIT;
// Where agy keeps each conversation, one SQLite file per ID.
const CONVERSATIONS = path.join(os.homedir(), '.gemini', 'antigravity-cli', 'conversations');
try {
  scratch = fs.mkdtempSync(path.join(dir, '.antigravity-'));
  const saved = read(path.join(dir, 'session_id'), 128, true);
  let session = saved.bytes <= 128 ? saved.text : '';
  if (run !== '1' && !validId(session)) {
    // The first event can survive a kill that interrupted publishing the ID.
    const prefix = read(path.join(dir, 'events-1.jsonl'), LIMIT, true).text;
    for (const line of prefix.split('\n')) {
      try {
        const e = JSON.parse(line);
        if (e.event === 'init' && validId(e.conversation_id)) { session = e.conversation_id; break; }
      } catch {}
    }
    if (!validId(session)) throw new Error('No saved Antigravity CLI conversation; start a new delegated task.');
    publish('session_id', session);
  }
  if (run === 'recover') {
    console.log(session);
    fs.rmSync(scratch, {recursive: true, force: true});
    process.exit(0);
  }
  if (run !== '1' && !fs.existsSync(path.join(CONVERSATIONS, session + '.db'))) {
    throw new Error('Antigravity CLI conversation ' + session + ' is gone, and agy would open an empty one in its place; start a new delegated task.');
  }
  const input = read(path.join(dir, 'prompt-' + run + '.md'));
  if (input.bytes > LIMIT) throw new Error('Antigravity CLI task input exceeds 1 MiB');
  let prompt = input.text;
  // The isx primer needs no delivery: agy reads its always-on rule, ~/.gemini/config/rules/isx.md.
  // agy has no option for a system prompt of its own, so the brief opens the first message, as for Codex.
  if (run === '1') prompt = read(path.join(dir, 'brief.md'), 16384).text + '\n\n' + prompt;
  // -p= is print mode with its prompt read from stdin; --dangerously-skip-permissions answers every
  // permission agy would ask about, as nobody is there to.
  const argv = ['-p=', '--input-format', 'stream-json', '--output-format', 'stream-json', '--dangerously-skip-permissions'];
  if (run !== '1') argv.push('--conversation', session);
  if (model) argv.push('--model', model);
  const events = output('events-' + run + '.jsonl');
  const stderr = output('stderr-' + run + '.log');
  const child = spawn('agy', argv, {stdio: ['pipe', 'pipe', stderr]});
  let named = false, malformed = false, lost = false, ended = false, success = false, error = '', report = '';
  let turns = 0;
  const usage = {};
  const count = (key, value) => {
    if (Number.isSafeInteger(value) && value >= 0) usage[key] = (usage[key] || 0) + value;
  };
  // Stops agy, and reading it: what it prints after this does not change why it was stopped.
  let stopped = false;
  const fail = message => { malformed = stopped = true; error = message; child.kill('SIGTERM'); };
  function event(line) {
    if (stopped || !line.trim()) return;
    let e;
    try { e = JSON.parse(line); } catch { malformed = true; return; }
    if (!named) {
      if (e.event !== 'init' || !validId(e.conversation_id) || (run !== '1' && e.conversation_id !== session)) {
        return fail(run === '1' ? 'agy did not open a valid conversation' : 'agy did not open conversation ' + session);
      }
      if (run === '1') { session = e.conversation_id; publish('session_id', session); }
      named = true;
    }
    const step = e.step_update || {};
    if (e.event === 'step_update' && step.step_type === 'agent_response' && step.state === 'DONE') {
      // One model call: the result's usage and num_turns count the whole conversation, these this run.
      turns++;
      count('input_tokens', step.usage?.input_tokens);
      count('output_tokens', step.usage?.output_tokens);
      count('cached_input_tokens', step.usage?.cache_read_tokens);
    }
    if (e.event === 'result') {
      // The report is the response of the result, which agy prints last.
      const r = e.result || {};
      ended = true;
      lost = false;
      success = r.status === 'SUCCESS';
      report = typeof r.response === 'string' ? r.response : '';
      if (!success) error = String(r.error || 'agy reported ' + (r.status || 'no status')).slice(0, 2000);
    }
  }
  // A result too large to keep is never replaced by nothing: the report is then lost, and the run failed.
  const records = lines(event, RECORD_LIMIT, start => {
    if (start.includes('"event":"result"')) lost = true;
  });
  child.stdout.on('data', data => {
    try {
      copy(events, data);
      records.write(data);
    } catch (e) { fail(e.message.slice(0, 2000)); }
  });
  child.on('error', e => { error = e.message; });
  child.stdin.on('error', () => {}); // A CLI startup failure can close stdin immediately.
  child.stdin.end(JSON.stringify({event: 'user', message: {role: 'user', content: prompt}}) + '\n');
  child.on('close', (code, signal) => {
    try {
      records.end();
      const ok = code === 0 && named && ended && success && !malformed && !lost;
      const message = Buffer.from(lost ? '' : report);
      const text = utf8(message.subarray(0, LIMIT));
      publish('result-' + run + '.txt', text);
      publish('outcome-' + run + '.json', JSON.stringify({type: 'isx.antigravity.result', success: ok,
        error: error || (ok ? '' : !named && run !== '1'
          ? 'agy could not open conversation ' + session + ' (see its stderr); start a new delegated task'
          : !named ? 'agy ended before opening its conversation'
          : lost ? 'agy\'s final message was too large to keep'
          : !ended ? 'agy ended without a result' : 'agy failed'),
        usage, turns, report_bytes: message.length, report_truncated: message.length > Buffer.byteLength(text)}));
      process.exitCode = ok ? 0 : (code || (signal ? 143 : 1));
    } catch (e) { console.error(e.message); process.exitCode = 125; }
    finally { fs.closeSync(events); fs.closeSync(stderr); fs.rmSync(scratch, {recursive: true, force: true}); }
  });
} catch (e) {
  console.error(e.message);
  if (scratch) fs.rmSync(scratch, {recursive: true, force: true});
  process.exitCode = 125;
}
