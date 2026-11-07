#!/usr/bin/env node
/*
 * cred-list.js — list the providers this caller may obtain from the AOHP credential broker, with
 * each one's status (docs/CREDENTIALS.md). cred.list shows only providers the caller is granted;
 * cred.status adds kind / account / OAuth expiry. Secrets are never printed.
 *
 *   cred-list              provider  kind    state
 *   cred-list --json       raw merged status objects
 *
 * Exit codes: 0 ok (even if empty); 4 broker unreachable; 2 protocol error.
 */
'use strict';
const net = require('net');
const TOOL = 'cred-list';

// Abstract-namespace socket: Node encodes a leading NUL as "\0name".
const SOCKET = '\0aohp_cred';

// One request, one newline-terminated JSON reply. Resolves {ok, result|error}; exit 4 if unreachable.
function rpc(method, params, onReply) {
  const req = JSON.stringify({ id: String(Date.now()), method, params }) + '\n';
  const sock = net.connect(SOCKET);
  let buf = '', done = false;
  const finish = (code) => { if (done) return; done = true; try { sock.destroy(); } catch (e) {} process.exit(code); };
  sock.setTimeout(8000, () => { process.stderr.write(TOOL + ': timeout\n'); finish(4); });
  sock.on('error', (e) => { process.stderr.write(TOOL + ': broker unreachable (' + e.code + ')\n'); finish(4); });
  sock.on('connect', () => sock.write(req));
  sock.on('data', (d) => {
    buf += d.toString('utf8');
    const nl = buf.indexOf('\n');
    if (nl < 0) return;
    let msg;
    try { msg = JSON.parse(buf.slice(0, nl)); }
    catch (e) { process.stderr.write(TOOL + ': bad reply\n'); return finish(2); }
    const c = onReply(msg); if (c !== undefined) finish(c); else { try { sock.destroy(); } catch (e) {} }
  });
}

const json = process.argv.includes('--json');

function status(provider) {
  return new Promise((resolve) => {
    const req = JSON.stringify({ id: provider, method: 'cred.status', params: { provider } }) + '\n';
    const sock = net.connect(SOCKET); let buf = '';
    sock.setTimeout(8000, () => { sock.destroy(); resolve({ provider, error: 'timeout' }); });
    sock.on('error', (e) => resolve({ provider, error: e.code }));
    sock.on('connect', () => sock.write(req));
    sock.on('data', (d) => { buf += d; const nl = buf.indexOf('\n'); if (nl < 0) return; sock.destroy();
      try { const m = JSON.parse(buf.slice(0, nl)); resolve(m.ok ? m.result : { provider, error: (m.error && m.error.code) || m.code || 'error' }); }
      catch (e) { resolve({ provider, error: 'bad_reply' }); } });
  });
}

rpc('cred.list', {}, (m) => {
  if (!m.ok) { process.stderr.write(TOOL + ': ' + ((m.error && m.error.code) || m.code || 'error') + '\n'); return 2; }
  const providers = (m.result && m.result.providers) || [];
  Promise.all(providers.map(status)).then((rows) => {
    if (json) { process.stdout.write(JSON.stringify(rows, null, 2) + '\n'); return process.exit(0); }
    if (!rows.length) { process.stderr.write('no providers granted to this caller (or none configured)\n'); return process.exit(0); }
    process.stdout.write('provider   kind    state\n');
    for (const r of rows) {
      let state = r.error ? r.error : (!r.configured ? 'not_configured' : (r.needs_reauth ? 'needs_reauth' : 'ok'));
      if (r.kind === 'oauth' && r.expires_at) state += ' (expires ' + new Date(r.expires_at).toISOString() + ')';
      if (r.account_label) state += ' ' + r.account_label;
      process.stdout.write((r.provider || '?').padEnd(10) + ' ' + (r.kind || '-').padEnd(7) + ' ' + state + '\n');
    }
    process.exit(0);
  });
  return undefined; // keep the socket alive until the promise settles
});
