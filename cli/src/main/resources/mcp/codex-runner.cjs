// Appended to runner-io.cjs. Node is already installed by CodexSetup.
const [sandbox, model, effort] = process.argv.slice(3);
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
    // The build's one stored copy (#1788); an image built before it has the managed policy.
    const primer = read('/etc/isx/agent-instructions.md', 65536, true).text
      || read('/etc/claude-code/CLAUDE.md', 65536, true).text
      || 'You are in a disposable isx instance. Passwordless sudo is available. Credentials are injected by the host proxy; do not search for API keys.';
    prompt = primer + '\n\n' + read(path.join(dir, 'brief.md'), 16384).text + '\n\n' + prompt;
  }
  const reportFile = path.join(scratch, 'last-message');
  const argv = ['exec'];
  if (run !== '1') argv.push('resume');
  argv.push('--json', '--skip-git-repo-check', '-c', 'approval_policy="never"',
    '-c', 'sandbox_mode=' + JSON.stringify(sandbox), '--output-last-message', reportFile);
  if (model) argv.push('--model', model);
  // A task's own effort; without one, config.toml's, which the template's effort wrote.
  if (effort) argv.push('-c', 'model_reasoning_effort=' + JSON.stringify(effort));
  if (run !== '1') argv.push(session);
  argv.push('-');
  const events = output('events-' + run + '.jsonl');
  const stderr = output('stderr-' + run + '.log');
  const child = spawn('codex', argv, {stdio: ['pipe', 'pipe', stderr]});
  let malformed = false, terminal = '', error = '', usage, lastMessage = '';
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
  const records = lines(event);
  child.stdout.on('data', data => {
    try {
      copy(events, data);
      records.write(data);
    } catch (e) { malformed = true; error = e.message.slice(0, 2000); child.kill('SIGTERM'); }
  });
  child.on('error', e => { error = e.message; });
  child.stdin.on('error', () => {}); // A CLI startup failure can close stdin immediately.
  child.stdin.end(prompt);
  child.on('close', (code, signal) => {
    try {
      records.end();
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
