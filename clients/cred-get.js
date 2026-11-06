#!/usr/bin/env node
/*
 * cred-get.js — reference client for the AOHP credential broker (docs/CREDENTIALS.md).
 *
 * Connects to the Driver's AF_UNIX control socket (abstract name @aohp_cred), calls cred.getToken
 * for a provider, and prints the usable credential so a consumer can put it on the wire:
 *
 *   HEADER_NAME<TAB>HEADER_VALUE      (to stdout, one line)
 *
 * Exit codes (so a caller can apply the §0 fallback):
 *   0  got a token
 *   3  broker reachable but not configured / denied for this provider
 *   4  broker unreachable (socket missing: Driver predates item 1b, or not running)
 *   2  usage / protocol error
 *
 * Usage: cred-get <provider> [purpose]
 *
 * The §0 portability contract: a consumer tries this first and FALLS BACK to its own credential
 * source on any non-zero exit, so it still runs off AOHP. See clients/README.md.
 */
'use strict';
const net = require('net');

const provider = process.argv[2];
const purpose = process.argv[3] || 'cli';
if (!provider) { process.stderr.write('usage: cred-get <provider> [purpose]\n'); process.exit(2); }

// Abstract-namespace socket: Node encodes a leading NUL as "\0name".
const SOCKET = '\0aohp_cred';
const req = JSON.stringify({ id: String(Date.now()), method: 'cred.getToken', params: { provider, purpose } }) + '\n';

const sock = net.connect(SOCKET);
let buf = '';
let done = false;
const finish = (code) => { if (done) return; done = true; try { sock.destroy(); } catch (e) {} process.exit(code); };

sock.setTimeout(8000, () => { process.stderr.write('cred-get: timeout\n'); finish(4); });
sock.on('error', (e) => { process.stderr.write('cred-get: broker unreachable (' + e.code + ')\n'); finish(4); });
sock.on('connect', () => sock.write(req));
sock.on('data', (d) => {
  buf += d.toString('utf8');
  const nl = buf.indexOf('\n');
  if (nl < 0) return;
  let msg;
  try { msg = JSON.parse(buf.slice(0, nl)); }
  catch (e) { process.stderr.write('cred-get: bad reply\n'); return finish(2); }
  if (!msg.ok) {
    const code = (msg.error && msg.error.code) || 'error';
    process.stderr.write('cred-get: ' + code + '\n');
    return finish(code === 'denied' || code === 'not_configured' || code === 'needs_reauth' ? 3 : 2);
  }
  const r = msg.result || {};
  if (!r.header_name || !r.header_value) { process.stderr.write('cred-get: empty token\n'); return finish(2); }
  process.stdout.write(r.header_name + '\t' + r.header_value + '\n');
  finish(0);
});
