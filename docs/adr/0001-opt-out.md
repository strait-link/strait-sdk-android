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
