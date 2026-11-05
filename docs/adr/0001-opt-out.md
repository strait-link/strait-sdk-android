# ADR 0001: opt-out switch `setEnabled(false)`

Date: 5 Nov 2026 · Status: accepted · Backlog: td2-27 · Same design as strait-sdk-react-native ADR 0002
(and the web, Swift and Flutter SDKs)

## Problem
Apps in India (DPDP Act) and the EU (GDPR) often have to ask a person for consent before an attribution SDK
sends anything. Without a switch they must not start Strait at all, which also loses the deferred link, or
they pick a rival SDK that has one (Branch documents `disableTracking`).

## Options
1. **Do nothing**: apps skip `start()` until consent. Loses direct-link routing and the install's deferred link.
2. **A `StraitConfig` flag**: can't change after a consent answer without recreating the client.
3. **A persisted runtime switch** `setEnabled(Boolean)` + `isEnabled()`, default on. **Chosen.**

## Design
- Stored as `strait.enabled` (`"0"` / `"1"`) in `StraitConfig.storage` (SharedPreferences); absent or
  unreadable = on. Held in a `@Volatile` field after the first read; `setEnabled` writes it first, so no new
  request starts after the call returns.
- Off: `call()` (every engine request) throws before the transport, and each entry point returns early:
  no `/v1/open`, `/v1/event`, `/v1/match`, `/v1/referrer`, `/v1/resolve`, `/v1/store-sheet`, debug
  fingerprint. `enqueue` and `noteTap` check the switch under `queueLock` / `tapLock`, and the opt-out
  clears the queue and the remembered tap under the same locks (on the executor, so the caller never
  waits for a flush in progress), so nothing written before the switch survives it.
- Store sheet while off: the Play Store still opens when `androidPackage` is set (the person's own action),
  without the engine call, so the deep link is not kept (`reason = "offline"`).
- Links: own-destination links (App Links to your domain, custom schemes) still reach `onLink`, unreported.
  A Strait short link needs `/v1/resolve`, which records the tap: `matched = false, reason = "disabled"`.
- First launch while off: the once-per-install check is not marked done; `setEnabled(true)` runs it on the
  executor in the same launch, else the next start does.
- `doctor()` reads the public assetlinks.json through the transport directly; not a report, still works.

## Security lens
Spoofing / tampering: none new (local flag in the app's sandbox). Data exposure: the point of the change;
accepted risk = a storage read that fails reads as "on". Abuse / DoS: none (fewer requests).

## Follow-up
E3 (engine): a lookup-only `/v1/resolve` (`record:false`) would let short links route while opted out.

## Update 5 Nov 2026: short links route while off (follow-up E3)
The engine now has `POST /v1/lookup` (engine ADR 0083): the same workspace-scoped lookup as `/v1/resolve`,
recording nothing (no tap, no open, no counter, no webhook, no tap id; Strait's code writes no IP or user
agent, while Cloudflare and Render keep their usual short-lived request logs, as for every request). While
off, a Strait short link is looked up there with the publishable key and the link's host + path only (no
query string, not even utm_source): no openId, platform, device class or app state. The link callback gets `matched: true`
with the destination and no `clickId`; nothing is queued, remembered or retried. A failed lookup (offline,
429, or an engine without it: 404, so it fails closed) comes back `reason: 'disabled'` as before. This is
the one engine call allowed while off; it bypasses the opt-out gate by name, nothing else does.

## Update 5 Nov 2026: follow-ups F1 + F4 (`ops/run-1105/sdk-privacy-followups.md`)
- **F1, User-Agent.** `UrlConnectionTransport` sent Android's default UA, `Dalvik/2.1.0 (Linux; U;
  Android 14; <model> Build/...)`, on every call, including the opt-out `/v1/lookup`, so the phone model
  reached Cloudflare and Render request logs. It now sends `strait-android` and nothing about
  the device, and no SDK version either: the engine's test-mode phone key hashes the UA, so a version
  would unpin a registered test phone (`test_devices`) at every SDK upgrade (review finding). Checked first: the engine reads the UA for matching only as the iOS major version
  (`match-signals.ts`, `iosMajorFromUA`), never on Android, and the platform comes from the body. One side
  effect, accepted: test mode's per-phone key (engine ADR 0071, UA + screen + density + language + time
  zone) can no longer tell apart two Android test phones with identical screen, density, language and time
  zone in the same test window. A custom `HttpTransport` keeps full control of its headers.
- **F4, switch-off mid-call.** `setEnabled(false)` while `/v1/resolve` was out (another thread) made the
  call fail at the gate and the link came back `reason = "network"`. A failed resolve now re-reads the
  switch and, when off, routes through `/v1/lookup` as an opted-out tap does; nothing is queued. (A reply
  that lands after the switch went off already kept no tap: `noteTap` checks the switch.)
