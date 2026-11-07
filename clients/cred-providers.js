#!/usr/bin/env node
/*
 * cred-providers.js — read or push the AOHP credential broker's provider catalog (providers.json).
 *
 *   cred-providers                 print the catalog the Driver is using (JSON; "source": pushed|asset|minimum)
 *   cred-providers --table         provider / env var / auth, one per line
 *   cred-providers --push <file>   install <file> as the catalog (cred.providers.set). aohp-update does this
 *                                  with /opt/aohp-agents/providers.json. Needs at least one existing grant.
 *
 * Exit: 0 ok; 3 denied; 4 broker unreachable; 2 usage/protocol/bad catalog.
 */
'use strict';
const net = require('net');
const fs = require('fs');
const TOOL = 'cred-providers';
const SOCKET = '\0aohp_cred';

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
    try { msg = JSON.parse(buf.slice(0, nl)); } catch (e) { process.stderr.write(TOOL + ': bad reply\n'); return finish(2); }
    finish(onReply(msg));
  });
}
const code = (m) => { const c = (m.error && m.error.code) || m.code || 'error'; process.stderr.write(TOOL + ': ' + c + (m.message ? ' (' + m.message + ')' : '') + '\n'); return c === 'denied' ? 3 : 2; };

const args = process.argv.slice(2);
if (args[0] === '--push') {
  const file = args[1];
  if (!file) { process.stderr.write('usage: cred-providers --push <providers.json>\n'); process.exit(2); }
  let catalog;
  try { catalog = JSON.parse(fs.readFileSync(file, 'utf8')); } catch (e) { process.stderr.write(TOOL + ': cannot read ' + file + ': ' + e.message + '\n'); process.exit(2); }
  rpc('cred.providers.set', { catalog }, (m) => { if (!m.ok) return code(m); process.stderr.write('installed catalog: ' + (m.providers || '?') + ' providers\n'); return 0; });
} else {
  rpc('cred.providers', {}, (m) => {
    if (!m.ok) return code(m);
    const r = m.result || m;
    if (args[0] === '--table') {
      process.stdout.write('provider    env var              auth                      (source: ' + (r.source || '?') + ')\n');
      for (const p of r.providers || []) {
        const auth = (p.auth && p.auth.header) + (p.auth && p.auth.scheme ? ' ' + p.auth.scheme : '');
        process.stdout.write(p.id.padEnd(11) + ' ' + p.env.padEnd(20) + ' ' + auth + '\n');
      }
    } else process.stdout.write(JSON.stringify(r, null, 2) + '\n');
    return 0;
  });
}
