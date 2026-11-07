#!/usr/bin/env node
/*
 * cred-set.js — store a provider credential in the AOHP credential broker (docs/CREDENTIALS.md §3).
 *
 * Calls cred.configure over the Driver's AF_UNIX control socket (@aohp_cred). The caller must
 * already hold a grant for the provider (consent screen on the phone), the same one cred-get needs.
 * The key never goes on a command line: it is read from stdin (preferred) or from the environment.
 *
 *   echo "sk-…" | cred-set openai           # from stdin (trailing newline stripped)
 *   OPENAI_API_KEY=sk-… cred-set openai -e OPENAI_API_KEY
 *   cred-set anthropic --revoke             # delete the stored record (cred.revoke)
 *
 * Exit codes: 0 stored/revoked; 3 denied (no grant); 4 broker unreachable; 2 usage/protocol/store error.
 */
'use strict';
const net = require('net');
const fs = require('fs');
const TOOL = 'cred-set';

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

const args = process.argv.slice(2);
const provider = args.shift();
if (!provider || !/^[a-z][a-z0-9_-]{0,31}$/.test(provider)) {
  process.stderr.write('usage: cred-set <provider> [-e VAR | --revoke]   (api key on stdin unless -e)\n');
  process.exit(2);
}
const exitFor = (msg) => {
  if (msg.ok) return 0;
  const code = (msg.error && msg.error.code) || msg.code || 'error';
  process.stderr.write(TOOL + ': ' + code + (msg.message ? ' (' + msg.message + ')' : '') + '\n');
  return code === 'denied' ? 3 : 2;
};
if (args[0] === '--revoke') {
  rpc('cred.revoke', { provider }, (m) => { const c = exitFor(m); if (c === 0) process.stderr.write('revoked ' + provider + '\n'); return c; });
} else {
  let apikey;
  if (args[0] === '-e') {
    apikey = process.env[args[1] || ''];
    if (!apikey) { process.stderr.write(TOOL + ': environment variable ' + (args[1] || '') + ' is empty\n'); process.exit(2); }
  } else {
    if (process.stdin.isTTY) process.stderr.write('paste the ' + provider + ' API key, then Ctrl-D: ');
    apikey = fs.readFileSync(0, 'utf8').replace(/\r?\n$/, '');
    if (!apikey) { process.stderr.write(TOOL + ': empty key on stdin\n'); process.exit(2); }
  }
  rpc('cred.configure', { provider, kind: 'apikey', apikey }, (m) => { const c = exitFor(m); if (c === 0) process.stderr.write('stored ' + provider + ' (apikey)\n'); return c; });
}
