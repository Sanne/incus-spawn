// Appended to runner-io.cjs. Node is already installed by OpencodeSetup.
// opencode names its session itself (ses_...), and every --format json event carries it, so run 1
// saves the first event's sessionID, as the Codex runner saves its thread ID. opencode refuses a
// --session it cannot find ("Session not found", exit 1) rather than opening a new one, so a resume
// needs no check of its own beyond the events naming the same session.
const [model] = process.argv.slice(3);
// A tool's output (a file it read) can be large, and a record that large is not needed: dropped.
const RECORD_LIMIT = 8 * LIMIT;
// opencode.json, which OpencodeSetup writes: its model names the provider a bare model ID is for.
const CONFIG = path.join(process.env.XDG_CONFIG_HOME || path.join(require('node:os').homedir(), '.config'),
  'opencode', 'opencode.json');
try {
  scratch = fs.mkdtempSync(path.join(dir, '.opencode-'));
  const saved = read(path.join(dir, 'session_id'), 128, true);
  let session = saved.bytes <= 128 ? saved.text : '';
  if (run !== '1' && !validId(session)) {
    // The first event can survive a kill that interrupted publishing the ID.
    const prefix = read(path.join(dir, 'events-1.jsonl'), LIMIT, true).text;
    for (const line of prefix.split('\n')) {
      try {
        const e = JSON.parse(line);
        if (validId(e.sessionID)) { session = e.sessionID; break; }
      } catch {}
    }
    if (!validId(session)) throw new Error('No saved opencode session; start a new delegated task.');
    publish('session_id', session);
  }
  if (run === 'recover') {
    console.log(session);
    fs.rmSync(scratch, {recursive: true, force: true});
    process.exit(0);
  }
  const input = read(path.join(dir, 'prompt-' + run + '.md'));
  if (input.bytes > LIMIT) throw new Error('opencode task input exceeds 1 MiB');
  let prompt = input.text;
  // The isx primer needs no delivery: opencode's config names /AGENTS.md in its instructions. opencode
  // has no option for a system prompt of its own, so the brief opens the first message, as for Codex.
  if (run === '1') prompt = read(path.join(dir, 'brief.md'), 16384).text + '\n\n' + prompt;
  // --auto answers every permission opencode would ask about, as nobody is there to: what its
  // config or its run mode denies stays denied.
  const argv = ['run', '--format', 'json', '--auto'];
  if (run !== '1') argv.push('--session', session);
  if (model) {
    let provider = '';
    if (!model.includes('/')) {
      // Through its real path, as a dotfiles resource may link it, since read() follows no link.
      try { provider = String(JSON.parse(read(fs.realpathSync(CONFIG), 65536, true).text || '{}').model || '').split('/')[0]; } catch {}
      if (!provider) throw new Error('opencode needs the model as provider/model, e.g. anthropic/' + model);
    }
    argv.push('--model', provider ? provider + '/' + model : model);
  }
  const events = output('events-' + run + '.jsonl');
  const stderr = output('stderr-' + run + '.log');
  const child = spawn('opencode', argv, {stdio: ['pipe', 'pipe', stderr]});
  let named = false, malformed = false, lost = false, failed = false, error = '', reason = '';
  let lastMessage = '', lastMessageId, turns = 0, cost = 0, costed = false;
  // Whether a text part of the current step was dropped: the step's other parts are not its report.
  let droppedInStep = false;
  const usage = {};
  // Stops opencode, and reading it: what it prints after this does not change why it was stopped.
  let stopped = false;
  const fail = message => { malformed = stopped = true; error = message; child.kill('SIGTERM'); };
  const count = (key, value) => {
    if (Number.isSafeInteger(value) && value >= 0) usage[key] = (usage[key] || 0) + value;
  };
  function event(line) {
    if (stopped || !line.trim()) return;
    let e;
    try { e = JSON.parse(line); } catch { malformed = true; return; }
    if (!named) {
      if (!validId(e.sessionID) || (run !== '1' && e.sessionID !== session)) {
        return fail(run === '1' ? 'opencode did not open a valid session' : 'opencode did not open session ' + session);
      }
      if (run === '1') { session = e.sessionID; publish('session_id', session); }
      named = true;
    }
    const part = e.part || {};
    if (e.type === 'step_start') droppedInStep = false;
    if (e.type === 'text' && typeof part.text === 'string' && !droppedInStep) {
      // The report is the last assistant message's text, which may come in several parts.
      lost = false;
      if (part.messageID !== lastMessageId) { lastMessageId = part.messageID; lastMessage = part.text; }
      else lastMessage += '\n' + part.text;
    }
    if (e.type === 'step_finish') {
      turns++;
      reason = String(part.reason || '');
      count('input_tokens', part.tokens?.input);
      count('output_tokens', part.tokens?.output);
      count('cached_input_tokens', part.tokens?.cache?.read);
      count('cache_creation_input_tokens', part.tokens?.cache?.write);
      if (typeof part.cost === 'number' && Number.isFinite(part.cost) && part.cost >= 0) { cost += part.cost; costed = true; }
    }
    if (e.type === 'error') {
      failed = true;
      error = String(e.error?.data?.message || e.error?.name || 'opencode reported an error').slice(0, 2000);
    }
  }
  // A final message too large to keep is never replaced by an earlier one.
  const records = lines(event, RECORD_LIMIT, start => {
    if (start.includes('"type":"text"')) lost = droppedInStep = true;
  });
  child.stdout.on('data', data => {
    try {
      copy(events, data);
      records.write(data);
    } catch (e) { fail(e.message.slice(0, 2000)); }
  });
  child.on('error', e => { error = e.message; });
  child.stdin.on('error', () => {}); // A CLI startup failure can close stdin immediately.
  child.stdin.end(prompt);
  child.on('close', (code, signal) => {
    try {
      records.end();
      const success = code === 0 && named && turns > 0 && !failed && reason !== 'error' && !malformed && !lost;
      const message = Buffer.from(lost ? '' : lastMessage);
      const text = utf8(message.subarray(0, LIMIT));
      publish('result-' + run + '.txt', text);
      publish('outcome-' + run + '.json', JSON.stringify({type: 'isx.opencode.result', success,
        error: error || (success ? '' : !named && run !== '1'
          ? 'opencode could not open session ' + session + ' (see its stderr); start a new delegated task'
          : !named ? 'opencode ended before opening its session'
          : turns === 0 ? 'opencode ended before the model answered'
          : lost ? 'opencode\'s final message was too large to keep' : 'opencode failed'),
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
