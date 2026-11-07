# Credential broker — reference clients

These are the **consumer shims** for the credential broker (see `../docs/CREDENTIALS.md`). They
exist so every consumer — the OpenClaw gateway launcher, a Perplexity-style search app, future apps
— obtains credentials the same way, and so each one satisfies the **§0 portability contract**:
*try the broker first, fall back to the app's own credential source, so the app still runs off AOHP.*

## Files

- **`cred-get.js`** — minimal Node client for the AF_UNIX control socket (`@aohp_cred`). Calls
  `cred.getToken` and prints `HEADER_NAME<TAB>HEADER_VALUE`. Non-zero exit on any miss, so callers
  fall back. No dependencies (Node `net` only).
- **`cred-set.js`** — stores an API key via `cred.configure` (key from stdin or `-e VAR`, never
  argv); `--revoke` deletes the record. Needs the same grant as `cred-get`.
- **`cred-list.js`** — `cred.list` + `cred.status` per provider: provider / kind / state. Only
  providers granted to the caller are shown; no secret material is printed. `--json` for raw.
- **`aohp-secrets-oauth.sh`** — reference OpenClaw launcher integration: broker first, Keystore
  `aohp secret` fallback. `eval "$(aohp-secrets-oauth.sh anthropic)"`.

## Protocol (for a native/other-language client)

Connect to abstract-namespace Unix socket `@aohp_cred` and write one JSON line:

    {"id":"1","method":"cred.getToken","params":{"provider":"anthropic","purpose":"..."}}

Read one JSON line back:

    {"id":"1","ok":true,"result":{"header_name":"authorization","header_value":"Bearer ...","expires_at":...}}
    {"id":"1","ok":false,"error":{"code":"denied|not_configured|needs_reauth","message":"..."}}

The broker authenticates the caller by **SO_PEERCRED** on this socket (kernel uid → app identity),
so no token is sent in the request. The caller is authorized per-provider by the ACL.

## The §0 rule for any new consumer

A consumer that is dead without the broker is a **bug against the contract**. The test: *with a
small adapter, this app runs on a stock phone with a pasted key, and lights up with shared login on
AOHP.* Broker calls are an enhancement, never a hard dependency.
