# Device bridge — use cases (pass 1)

Status: draft, 2026-10-19. No code. The point of this page is to find the
cross-cutting requirements *before* the first bridge fixes the design.

Context: the ws :6666 bridge already has `sandbox.*`, `unit.*`, `display.*`,
`act.*` (taps/keys), `ui.*` (a11y tree), `shot.*`, `app.*`, `sys.*`
(clipboard, battery, network, screen, wake/sleep/unlock, shade) and
`event.register/drain` (the upstream AohpEventStreamService). What it lacks
is the phone-as-a-phone surface: notifications in/out, SMS, telephony,
sensors, location, camera, audio, BLE. `sys.notify` (post/cancel) was added
in Driver 0.6.0 as the first of these. Every row below is Driver (Kotlin) + a `device.*` op
family + `aohp device …` in the CLI + a tool/channel on the OpenClaw side;
none of it needs a ROM rebuild — the Driver is platform-signed and runs as
`platform_app`, so signature permissions are automatic and framework
services are reachable. Runtime (dangerous) permissions are prompted once
per capability. `AohpEventStreamService` (system_server,
`IAohpEventStream.register/drain/unregister`) already exists upstream with a
drain-with-cursor model; the Driver-side subscription should reuse that
shape (and that service for the events system_server sees: screen,
package, heads-up).

Flow shapes used below:

- **push** — the Driver sends an event frame; the gateway's `phone` channel
  plugin turns it into an inbound message → an agent turn. Rare, message-shaped.
- **stream + reducer** — high-rate data goes to a unit in the env (no LLM)
  that emits a *synthesized* push event seldom. Sensors, location.
- **pull** — the agent calls a tool mid-turn (`phone_battery`, `phone_location`).
  State, not events.
- **act** — the agent (or a unit) asks the Driver to do something: post,
  send, dial, vibrate, capture.

## Scenarios

### S1. Build/job done → wrist — **PROVEN 2026-10-19** (Driver 0.6.0 `sys.notify` from env `oc` → NotificationManager → Gadgetbridge → Fenix 7)
A unit finishes (`build-ingot-2`, a cron job, a long test). It runs
`aohp device notify --channel jobs --title "ingot build-2" --text "rc=0 1h47"`.
Android notification → every listener → Gadgetbridge → Fenix 7. Replaces
the Telegram ping for on-phone work.
Bridges: notification(act). Shape: act.
Needs: notification channels owned by the Driver (importance, sound, quiet
hours), collapse/rate-limit per channel so the wrist isn't a log tail.

### S2. Ask Chris a question from the wrist, get the answer back
Agent posts a `MessagingStyle` notification with a `RemoteInput` reply
action ("deploy raims 1.54 to www? y/n"). Chris answers from the watch or
the shade. The reply arrives as `{type:"notification.reply", id, text}` →
inbound on the same conversation → the agent continues.
Bridges: notification(act + push). Shape: act then push.
Needs: a notification id ↔ session/conversation map in the channel plugin;
dismiss events too ("he swiped it away" = no answer); replay if the env was
down when the reply came.

### S3. Every messaging app's inbound, one listener
WhatsApp/Signal/Telegram/Slack/SMS post notifications. The Driver's
`NotificationListenerService` forwards `onNotificationPosted/Removed` with
pkg, conversation key, `EXTRA_MESSAGES`, available actions. Channel plugin
routes `notification:<pkg>/<conv>` into per-conversation sessions; the agent
can reply via the app's own `RemoteInput` action and mark read.
Bridges: notification(push + act). Shape: push.
Needs: **trust** — bodies are untrusted content from arbitrary apps and
strangers; a sender can never be a principal; reply is gated per
conversation. Per-app opt-in in the Driver (which packages are forwarded at
all). Honour app privacy: secret/sensitive notifications are not forwarded.
Cannot: start a new conversation, send attachments, read history.

### S4. SMS bot / triage
Incoming SMS → push with sender, body. Agent drafts/sends replies, looks up
the sender, forwards urgent ones to Telegram when Chris is away. Also:
"text my wife I'm 20 min out" from the OpenClaw chat.
Bridges: sms(push + act). Shape: push, act.
Needs: `SEND_SMS`/`READ_SMS`/`RECEIVE_SMS` runtime grants; allowlist of
senders whose messages may trigger actions vs. merely be data; outgoing
rate limit and a confirmation policy per env.

### S5. Call handling
Phone rings → push with caller id and lookup → agent can answer, hold,
hang up, send DTMF, or screen (`CallScreeningService`: reject known spam,
let through contacts, ask the agent for unknowns). "Call the dentist and
press 2."
Bridges: telephony(push + act), contacts(pull). Shape: push, act.
Needs: **preemption** — a ringing call is the one event that must not queue
behind a running turn (new `urgent` flag on the inbound); the Dialer role
or an `InCallService`; a hard human-in-the-loop default for answering.

### S6. "Where are you / what's around"
Agent is asked (by Chris on Telegram, or by a rule) for location; it calls
`phone_location` (last known, or a fresh fix with a timeout).
Bridges: location(pull). Shape: pull.
Needs: fine + background location grants; accuracy/age in the result.

### S7. Arrived / left — geofence and motion as events
A reducer unit subscribes to location (coarse, slow) + significant-motion
and emits `phone.arrived home`, `phone.left office`, `phone.still 3h`.
Drives automations (start the home VPN, mute the wrist, remind to take the
Pixel) without a model call per sample.
Bridges: location(stream), sensor(stream). Shape: stream + reducer.
Needs: the reducer runs as a unit in the env with no LLM; places config;
battery budget — low-rate location, batched sensor delivery.

### S8. Sensor rules
Tilt-to-wake-the-env, "phone face down = do not disturb the agent", shake
= cancel current job, proximity/light for "in pocket". Cheap wins that make
the device feel like it hosts the agent.
Bridges: sensor(stream). Shape: stream + reducer.
Needs: subscribe with rate + batching; reducer templates; unsubscribe when
no consumer (sensors cost battery while subscribed).

### S9. Camera → local OCR/VLM
"Photograph the whiteboard / receipt" → `aohp device camera capture` → JPEG
bytes land in the env → tesseract or the on-device VLM; nothing leaves the
phone. Preview for aiming goes to the virtual display.
Bridges: camera(act). Shape: act with a bulk payload.
Needs: bulk transfer over the socket (binary frames or a file handoff via
`/data/aohp/envs/<env>/…`); the Android privacy indicator is the consent UI.

### S10. Voice in / voice out
Hold a button → `aohp device audio record` → PCM to the env → whisper →
text to the agent; replies via `audio play` (TTS in the env or Android TTS).
Also: listen on the wrist (S1 + speech).
Bridges: audio(act, both directions). Shape: act, streaming payload.
Needs: streaming frames both ways with backpressure; "driving" detection
(S7) to choose voice over text.

### S11. Phone as a sensor of its own state
Agent throttles itself on battery/metered network, pauses builds below 20 %,
knows the screen is off (no point drawing), reacts to dock/undock
(desktop mode on the Saga/OnePlus), USB attach, Wi-Fi SSID change.
Bridges: system(push + pull). Shape: pull mostly; dock/battery-low as push.
Needs: little — broadcasts in the Driver; most already in
`AohpEventStreamService` (screen, package).

### S12. Clipboard and intents
"Share with the agent": copy text/URL → agent picks it up; agent opens an
Android app with an intent (maps to an address, a deep link).
Bridges: system(pull + act). Shape: pull, act.
Needs: `READ_CLIPBOARD_IN_BACKGROUND` (signature, automatic); intent
allowlist per env.

### S13. BLE gadgets directly
A unit talks to a Bangle.js / ESP32 / BLE sensor via Driver-brokered GATT
(scan, connect, read/write/notify characteristics). Hackable watches,
sensors, home devices without a companion app.
Bridges: bluetooth(act + push). Shape: act, push for notifications.
Needs: GATT brokering with per-env device allowlist; not needed for the
Fenix (Gadgetbridge handles it) — later.

### S14. Haptics
Agent pulses the phone for "attention needed" when the screen is off and no
watch is paired. Trivial; piggybacks on notification channels.
Bridges: haptics(act). Shape: act.

### S15. Garmin/companion watchdog (HostExec, outside the bridge)
A `HostExec=` unit checks that the companion's notification listener is
bound (`dumpsys notification`), rebinds it (`cmd notification
disallow_listener/allow_listener`), keeps it on the doze whitelist, posts a
canary and checks delivery. Diagnoses the "watch connected, nothing arrives"
class. Needs ~3 sepolicy rules for `aohp_container_daemon` (logd read,
service_manager find + binder to system_server) — the only ROM change on
this page.

## Bridge × scenario matrix

| bridge | S1 | S2 | S3 | S4 | S5 | S6 | S7 | S8 | S9 | S10 | S11 | S12 | S13 | S14 | ops |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| notification | act | act,push | push,act | | | | | | | | | | | | post, listen, reply, dismiss, markRead |
| sms | | | | push,act | | | | | | | | | | | send, list, incoming |
| telephony | | | | | push,act | | | | | | | | | | state, dial, answer, hangup, dtmf, screen |
| location | | | | | | pull | stream | | | | | | | | last, fix, subscribe |
| sensor | | | | | | | stream | stream | | | | | | | list, subscribe(rate,batch) |
| camera | | | | | | | | | act | | | | | | list, capture, preview |
| audio | | | | | | | | | | act | | | | | record, play |
| system | | | | | | | | | | | push,pull | pull,act | | | battery, net, screen, dock, clipboard, intent |
| bluetooth | | | | | | | | | | | | | act,push | | scan, connect, gatt |
| haptics | | | | | | | | | | | | | | act | vibrate |

## Cross-cutting requirements (what the spine must have before bridge #1)

1. **Subscription protocol on ws :6666**: `device.subscribe {types, filter}` →
   server-push `event` frames with monotonically increasing `id`,
   `ts`, `type`; `device.unsubscribe`; reconnect with `after=<id>`;
   bounded per-subscriber buffer in the Driver (events outlive a crashed
   env, up to N); keepalive frame so silence ≠ dead socket. Same shape as
   `IAohpEventStream.drain` — consider the Driver *being* a client of that
   service for system events.
2. **Per-env capability grants** in the Driver UI: env × bridge × (read /
   act) with the Android runtime prompt fired on first grant. Default: none.
   `oc` may read notifications and send SMS; `cc` may do nothing.
3. **Trust labelling**: every inbound body (notification, SMS, call id) is
   wrapped as untrusted content in the channel plugin, never as the owner's
   voice. Actions originating from inbound text need the per-env act grant
   *and* a per-conversation allow.
4. **Reply/act round trip**: ids on everything the agent posts so replies,
   dismissals and delivery results come back to the right session.
5. **Urgency**: one `urgent` bit on inbounds (ringing call, SOS) that the
   gateway may use to interrupt a turn — new gateway behaviour; everything
   else queues.
6. **Reducers as units**: a documented template (`aohp device subscribe …
   | reducer | aohp device emit`) so stream-shaped bridges never reach the
   model raw.
7. **Bulk payloads**: camera/audio need binary frames or file handoff
   through the env's filesystem; decide once.
8. **Rate limits & quiet hours** on acts (notify, SMS, vibrate) in the
   Driver, not in the env.
9. **CLI + tools**: `aohp device <bridge> <op>` for units; `phone_*` tools
   and the `phone` channel plugin for the on-phone OpenClaw gateway.

## Build order (pass 2/3)

1. Spine (1,2,3,4,9) proven by **notification** (S1, S2, S3 — push + reply +
   wearables for free) and **sensor** (S8 — stream + reducer, the token/battery
   stress test).
2. **sms** + **telephony** (S4, S5; adds *send* and *urgent*).
3. **location** (S6, S7) + **system** (S11, S12) — mostly pull, cheap.
4. **camera**, **audio** (S9, S10) — the bulk-payload decision.
5. **bluetooth**, **haptics** as pulled by a use case.
6. S15 (HostExec watchdog) is independent and can go first; it carries the
   only sepolicy change.

## Open questions for Chris

- Should the `phone` channel be a plugin in the on-phone gateway (no extra
  daemon; dies with the gateway) or an `aohp-eventd` unit bridging to the
  gateway over RPC (survives gateway restarts, one more process)?
- Pre-grant runtime permissions in the image (`default-permissions-aohp.xml`,
  rebuild) or always prompt on first per-env grant?
- Does any scenario justify extending `AohpEventStreamService` in
  frameworks/base rather than keeping everything app-side in the Driver?
