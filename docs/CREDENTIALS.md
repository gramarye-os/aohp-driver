# Credential Broker — API Keys & OAuth for AOHP Apps

Status: design draft (2026-11-05). Owner: injinj. Scope: the on-device mechanism by
which every AOHP app (the OpenClaw gateway, a Perplexity-style search app, and future
apps) obtains the provider credentials it needs — Anthropic, Brave, and others — without
each app storing its own keys or handling its own OAuth.

The guiding rule: **the Driver bridge is the single credential broker. Apps never hold
refresh tokens or long-lived keys; they ask the bridge for a short-lived access token at
the moment they need one.** One login per provider, shared by all apps, refreshed in one
place, with per-app authorization enforced at the bridge.

---

## 0. Portability contract (binding design principle)

**The broker is a capability, not a cage.** This is a hard design rule, not an aspiration: a
consumer app that uses the broker on an AOHP device MUST still be able to run as an ordinary
app off AOHP. If following this document ever makes an app non-portable, the document is wrong,
not the app.

Three rules enforce this. Every `cred.*` consumer and every future extension of this design is
held to them:

1. **Graceful degradation (optional broker).** An app tries the broker first and falls back to
   its own credential source (own Keystore `EncryptedSharedPreferences`, a pasted key, an env
   var) when the bridge is absent or returns `not_configured`/`denied`. The test every consumer
   must pass: *with a small adapter, this app runs on a stock Pixel with a pasted key, and
   lights up with shared login on an AOHP device.* An app that is dead without the bridge is a
   bug against this contract.

2. **Standard-shaped contract.** `cred.getToken` returns a provider-agnostic
   `header_name`/`header_value` so the app's actual provider calls are plain HTTPS, identical on
   or off AOHP. Only credential *acquisition* is AOHP-specific, and it is modeled on OAuth token
   exchange / OS credential services (AccountManager, Keychain, Secret Service) so porting off
   AOHP is a thin adapter, never a rewrite. Do not invent bespoke wire formats that leak AOHP
   assumptions into the app's request path.

3. **Narrow scope — resist bridge creep.** The lock-in surface is not any single service; it is
   the *number* of unrelated capabilities an app must pull from the bridge. An app bound to the
   bridge only for credentials is lightly coupled and portable; an app that also pulls its model,
   its search, and its UI from the bridge cannot leave the device. Keep the credential broker to
   credentials. Adding more to the bridge is a decision to be justified against portability, not
   a default. The litmus test: *could this app exist, in degraded form, on a non-AOHP phone?* If
   a new bridge dependency makes the answer "no", it is the wrong seam.

**Why this is a contract and not a preference.** AOHP's thesis is the phone as an open Linux
*node* that produces local tooling — the opposite of an app that can only consume one vendor's
integrations. A credential fabric that apps cannot function without would recreate exactly that
vendor-capture pattern, with AOHP as the new vendor. The binding is acceptable only while AOHP
stays an open, documented, reimplementable platform (the Driver is open; the `cred.*` contract
is public; another implementation could serve it) AND apps remain able to live without it. Lose
either property and the broker becomes a trap. Future changes that strengthen binding at the cost
of portability must be rejected or justified explicitly against this section.

---

## 1. Landscape: API keys vs OAuth

The two credential styles behave differently, and the broker must support both because
real providers split across them.

**Static API key / token.** A long-lived string the client sends on every request.
- Anthropic API: `x-api-key: sk-ant-...` (also the `sk-ant-oat01-...` setup-token form).
- Brave Search: `X-Subscription-Token: <token>`.
- Properties: never expires on its own; no exchange step; billed per use to the key's
  account; revoked only by regenerating it. Simple to store and use; the whole secret is
  the credential, so anything that reads it has full power until rotation.

**OAuth 2.0 (Authorization Code + PKCE).** The client never holds a static key. After an
interactive login it holds two tokens:
- an **access token**, short-lived (minutes to ~1 hour), sent on requests;
- a **refresh token**, long-lived, used only to mint new access tokens.
- Properties: the access token's short life limits the damage of a leak; the refresh
  token is the real secret and is revocable server-side; many providers **rotate** the
  refresh token on every use (mint-new-on-refresh), so whoever refreshes must persist the
  new one or lose the account. Using a subscription (e.g. Claude Pro/Max via
  Sign-in-with-Claude) rather than metered API billing is the usual reason to choose OAuth.

**Which provider uses what (today):**

| Provider | API key | OAuth | Notes |
|---|---|---|---|
| Anthropic | yes (`sk-ant-...`) | yes (Claude subscription; setup-token `sk-ant-oat01-`) | OAuth => subscription billing, API key => metered |
| Brave Search | yes (`X-Subscription-Token`) | no | API key only |
| Google / Gmail / Drive (future) | no (for user data) | yes | OAuth mandatory for consumer accounts |

Design consequence: the broker stores a **typed credential** per provider. A consumer app
does not need to know which style a provider uses — it always asks the broker for a usable
token and gets back something it can put on the wire.

---

## 2. Storage mechanics

**At rest: the Driver's Keystore-backed store.** Credentials live in the existing
`SecretStore` (`org.aohp.driver.bridge.SecretStore`): Android `EncryptedSharedPreferences`,
values AES-256-GCM, names AES-256-SIV, master key in the Android Keystore (hardware-backed
TEE/StrongBox where available). This store already backs the current `secret.*` RPC and the
`aohp secret` CLI. It survives reboot, is private to the Driver's uid, and is never written
into any container rootfs.

**Schema change: from opaque string to typed record.** Today a secret is a bare string.
The broker stores a small JSON record per provider instead:

~~~json
{
  "provider": "anthropic",
  "kind": "oauth",                 // "apikey" | "oauth"
  "apikey": null,                   // set when kind=apikey
  "refresh_token": "<secret>",     // set when kind=oauth; the real at-rest secret
  "access_token": "<cache>",       // last minted access token (cache)
  "expires_at": 1793999999000,      // access-token expiry, epoch ms
  "token_url": "https://.../oauth/token",
  "client_id": "<public>",         // public client id; no client secret on device
  "scopes": ["..."],
  "account_label": "chris@...",    // for display; never a secret
  "created_wall_ms": 1793900000000
}
~~~

- For `kind=apikey`, only `apikey` + metadata are set; there is no refresh lifecycle.
- For `kind=oauth`, the **refresh token is the crown jewel** and never leaves the Driver
  process except as a derived short-lived access token handed to an authorized caller.
- Backward compatibility: existing bare-string secrets (e.g. today's `ANTHROPIC_API_KEY`,
  `BRAVE_API_KEY`) are read as implicit `kind=apikey` records so nothing breaks during
  migration.

**Not the framework vault.** The in-memory, ingress-only `AohpVaultService`
(`system_server`) is a different component (it tokenizes secrets flowing toward the agent
on screen and resolves them at the display sink). It is volatile and one-directional and is
**not** where provider credentials live. The broker is the Driver `SecretStore`.

---

## 3. Acquiring credentials (on-device, once per provider)

A new user must be able to authenticate entirely on the phone. The Driver already has a
4-step `SetupWizard` (Env -> Credentials -> Start -> Done) whose Credentials step offers
Paste / Git / Skip. The broker adds an OAuth path.

**API key path (existing, extended):** user pastes the key; `SecretStore` stores it as a
`kind=apikey` record; UI state drops the plaintext immediately (already done today).

**OAuth path (new): Authorization Code + PKCE, in the Driver, via a Custom Tab.**
1. User taps "Sign in" for a provider. The Driver builds a PKCE challenge and opens the
   provider's authorization URL in an **Android Custom Tab** (not a WebView — providers
   reject embedded WebViews).
2. User authenticates with the provider; the provider redirects to a registered URI:
   either a custom scheme `aohp://auth/<provider>` or an `https` App Link on a domain we
   control (`raitechnology.com` with `.well-known/assetlinks.json`). Custom scheme is
   simpler; App Link is stronger against scheme hijacking.
3. The Driver (using **AppAuth-Android**, the standard library — we do not hand-roll the
   flow) exchanges the authorization code + PKCE verifier for `{access_token,
   refresh_token, expires_in}` at the token endpoint. **No client secret is embedded in
   the APK** — PKCE exists precisely so a public client needs none.
4. The Driver writes a `kind=oauth` record to `SecretStore` and discards the raw response
   from memory.

**Who can trigger a login.** Any consumer app can request a provider it lacks; the bridge
responds by bringing the Driver's login UI forward (the app cannot run the flow itself).
So the search app on first run can bounce the user into the Driver to sign in to Anthropic,
and afterward both it and OpenClaw are authenticated from the one login.

---

## 4. Caller authentication: SO_PEERCRED + capability tokens

**This is a prerequisite, not an add-on.** Centralizing credentials behind one RPC is only
safe if the bridge knows *who is calling*. Today the bridge listens on `127.0.0.1:6666`
with **no caller authentication** — any local process (including code in a container) can
call `secret.get`. A broker without caller auth would be a downgrade: a single endpoint
handing every app's credentials to anyone who connects. So the broker ships with two
enforcement layers.

**Layer 1 — SO_PEERCRED for local-socket callers.** The authoritative, OS-enforced
identity of a Unix-domain / loopback peer is its kernel-supplied credentials. On the
accepted socket the bridge reads `SO_PEERCRED` (peer uid/gid/pid). Android assigns each
installed app a distinct uid, so the peer uid *is* the caller's app identity and cannot be
forged from user space. The bridge maps uid -> package (via `PackageManager`) and uses
that as the caller principal. For the container/gateway case the peer uid is the
container's uid, which is likewise distinct.
- Prefer a **`LocalSocket` (AF_UNIX)** transport for broker calls so `SO_PEERCRED` is
  available and the surface is not a TCP port at all. If the WebSocket-on-TCP bridge is
  retained for compatibility, broker (`cred.*`) methods are restricted to the AF_UNIX
  transport, or TCP callers must present a capability token (Layer 2) and are never
  trusted by address alone (loopback is not identity).

**Layer 2 — capability tokens for non-uid or finer-grained callers.** Where peer uid is
insufficient (e.g. multiple logical consumers in one uid, or a future non-uid transport),
each app receives, at install/pairing time, an opaque **capability token** bound to its
package + signing certificate and to a set of allowed providers. The token is minted by
the Driver and stored by the app in its own Keystore. `cred.*` calls present it; the
bridge verifies it and cross-checks against `SO_PEERCRED` where both are available.

**Per-provider ACL.** The broker keeps an allow-list: which caller principal may obtain
which provider's token. Example:

| Caller | anthropic | brave | google |
|---|---|---|---|
| OpenClaw gateway (container uid) | getToken | — | — |
| Search app (`org.aohp.search`) | getToken | getToken | — |
| Unknown caller | deny | deny | deny |

Default is **deny**. Granting a provider to an app is an explicit, user-visible decision
(a consent screen in the Driver, mirroring the framework's consent-gate pattern).

**Audit.** Every `cred.getToken` and `cred.configure` writes an audit entry (principal,
provider, purpose, outcome, masked token id), mirroring the framework's
`AohpSecurityAuditLog`. A compromised app's harvesting attempts are then visible.

---

## 5. RPC design

The broker extends the existing bridge RPC (which already has `secret.get/set/delete/
list`). New namespace `cred.*`. The legacy `secret.*` methods remain for non-credential
opaque secrets but are **deprecated for provider credentials** in favor of `cred.*`.

**`cred.configure`** — store or begin acquiring a credential.
~~~json
// API key:
{ "method": "cred.configure",
  "params": { "provider": "brave", "kind": "apikey", "apikey": "<value>" } }
// OAuth (launches the Driver login UI; returns when the flow completes or a handle to poll):
{ "method": "cred.configure",
  "params": { "provider": "anthropic", "kind": "oauth" } }
~~~
Requires caller authorization to configure that provider. Never echoes the stored secret.

**`cred.getToken`** — the core call. Returns a currently-valid credential to put on the
wire. This is the **only** way any app obtains a usable credential.
~~~json
{ "method": "cred.getToken", "params": { "provider": "anthropic", "purpose": "model_call" } }
// ->
{ "kind": "oauth", "header_name": "authorization", "header_value": "Bearer <access-token>",
  "expires_at": 1793999999000 }
// for an api-key provider:
{ "kind": "apikey", "header_name": "x-api-key", "header_value": "<key>", "expires_at": null }
~~~
- For OAuth, the broker refreshes transparently if the cached access token is expired or
  near expiry (see Lifecycle), persisting any rotated refresh token, and returns a fresh
  access token. The refresh token is **never** returned.
- Returning `header_name`/`header_value` lets the broker own the per-provider wire detail
  (`x-api-key` vs `Authorization: Bearer` vs `X-Subscription-Token`) so apps stay provider-
  agnostic.

**`cred.status`** — non-secret health for settings UIs.
~~~json
{ "method": "cred.status", "params": { "provider": "anthropic" } }
// -> { "kind":"oauth", "account_label":"chris@...", "expires_at":..., "configured":true }
~~~

**`cred.revoke`** — delete the stored credential (and best-effort server-side revoke for
OAuth).

**`cred.list`** — provider names + kind + configured flag the caller is allowed to see;
never secrets.

All `cred.*` methods: authorize the caller (section 4), act, audit. Invalid provider or
unauthorized caller returns a typed error, never a secret.

---

## 6. Lifecycle & refresh

**API key:** no lifecycle. `cred.getToken` returns the stored key. Rotation is a
`cred.configure` with a new value; all consumers pick it up on their next call.

**OAuth access-token refresh (the central new logic):**
1. On `cred.getToken`, compare `now` to `expires_at` minus a safety skew (e.g. 60 s).
2. If still valid, return the cached access token.
3. If expired/near-expiry, POST to `token_url` with `grant_type=refresh_token`,
   `refresh_token`, `client_id` (PKCE public client; no secret).
4. On success, update `access_token`, `expires_at`, and — critically — **persist any
   rotated `refresh_token`** the provider returns, atomically, before returning.
5. On failure (refresh token revoked/expired), mark the record `needs_reauth`, fail the
   call with a typed error, and surface a re-login prompt in the Driver. `cred.status`
   reports the degraded state.

**Single-refresher invariant.** Exactly one component refreshes a given credential: the
broker. Consumers never refresh. This avoids the rotated-refresh-token race where two
parties each think they own the refresh token and invalidate each other. Within the broker,
refresh for a provider is serialized (a per-provider lock) so concurrent `getToken` calls
collapse to one network refresh.

**Long-running consumers (e.g. the OpenClaw gateway).** A gateway process outlives a
1-hour access token, so it cannot cache a token for its lifetime. Two supported patterns:
- **Pull on demand (preferred):** the consumer calls `cred.getToken` per request, or on a
  401, and always holds only a short-lived token. A small OpenClaw provider shim does this;
  the launcher no longer exports a static `ANTHROPIC_API_KEY`.
- **Short-TTL export (compat):** `aohp-secrets env` calls `cred.getToken` at gateway start
  and exports the access token; the gateway re-execs or re-reads on expiry. Simpler but the
  token is an env var for its (short) life.

**Consumer integration summary:**
- OpenClaw: launcher `aohp-secrets env` -> `cred.getToken anthropic`; export the right
  header credential, or configure OpenClaw's provider transport to call the broker on 401.
- Search app: `cred.getToken anthropic` for the model call, `cred.getToken brave` for
  search, each at call time; holds only minutes-long tokens in its own sandbox.

---

## 7. Trust boundaries (summary)

- **Refresh tokens and API keys** live only in the Driver's Keystore-backed `SecretStore`
  and only ever exist in plaintext inside the Driver process.
- **Consumer apps** receive only short-lived access tokens (OAuth) or the key at call time
  (API key), scoped by per-app ACL, over a `SO_PEERCRED`-authenticated channel.
- **The bridge** authenticates every caller (kernel peer creds + capability token),
  authorizes per provider, refreshes centrally, and audits.
- **The framework `AohpVault`** is unchanged and unrelated: it protects the agent's screen
  *input* channel, not provider credentials.

## 8. Build order

1. **Bridge caller auth** (SO_PEERCRED + capability token + per-provider ACL + audit) —
   prerequisite; everything else is unsafe without it.
2. **Typed credential record** in `SecretStore` (+ back-compat read of bare strings).
3. **`cred.*` RPC** (configure/getToken/status/revoke/list) with the apikey path first.
4. **OAuth acquisition** (AppAuth + PKCE + Custom Tab) wired into the Setup wizard's
   Credentials step and a Harness-tab "Set / replace credential" entry.
5. **Central refresh** with the single-refresher lock and rotated-token persistence.
6. **Consumer shims:** OpenClaw launcher + provider-on-401; search app call sites. Every
   consumer MUST satisfy the §0 portability contract: broker first, own-credential fallback
   second, so the app still runs off AOHP. Reference clients implementing this live in
   `clients/` (`cred-get.js` AF_UNIX client; `aohp-secrets-oauth.sh` launcher shim; `README.md`
   protocol + the §0 rule). New consumers copy these rather than re-deriving the protocol.
7. Restart the gateway after a credential change via the existing `unitControl` path on
   `openclaw-gateway` (already implemented).

## 9. Open questions

- Transport: move `cred.*` to a dedicated AF_UNIX socket, or keep the WebSocket bridge and
  gate `cred.*` behind capability tokens? AF_UNIX gives clean SO_PEERCRED.
- Capability-token issuance: at app install (manifest + signature check) vs an explicit
  pairing step in the Driver.
- OpenClaw on-401 refresh: a minimal provider plugin vs the short-TTL env export for v1.
- App Link (`assetlinks.json` on raitechnology.com) vs custom scheme for the OAuth redirect.
- Multi-account per provider (profiles), mirroring OpenClaw's auth-profile routing.
