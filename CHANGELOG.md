# Changelog

## Unreleased

- `doctor()` (contract B23, shared-spec ADR 0002 amendment of 7 Nov): on a Strait link domain before Dashboard → Apps & stores is filled in (it answers 404 `{"error":"not configured"}`, the usual first-time state) the finding is now `app.none` with "Fill in Dashboard → Apps & stores (package name and SHA-256 fingerprint / Team ID and bundle ID), wait a minute, then check again", not `host.file_missing` ("use your Strait link domain as endpoint", which you already were). Every `app.none` fix now names those fields. 49 shared doctor vectors (was 43).
- README: starts with the 5-minute path, `npx @straitlink/cli init` (writes the integration file and prints the steps left), before the manual quick start.

## 0.10.0 (7 Nov 2026)

- New: open and install reports carry the app's version and the SDK's version (`appVersion`, `sdkVersion`), so
  when someone says "links stopped working", support can see which app build sent each report. Pass
  `StraitConfig.appVersion = { packageManager.getPackageInfo(packageName, 0).versionName }`; it is read once,
  trimmed, cut to 64 characters and left out unless plain ASCII. Sent on `/v1/resolve`, `/v1/open` (a saved
  retry keeps the versions of the build that made it), `/v1/referrer` and `/v1/match`; never on events or `/v1/lookup`, and nothing while `setEnabled(false)`.
  Older engines ignore both fields.
- Privacy: the default `UrlConnectionTransport` sends `User-Agent: strait-android` instead of
  Android's default (`Dalvik/2.1.0 (Linux; U; Android 14; <model> Build/...)`), so no phone model reaches
  request logs. Matching never used it on Android.
- Fix: switching the SDK off while a short link's `/v1/resolve` is still out no longer answers
  `reason = "network"`; the link routes through `/v1/lookup` as an opted-out tap does, and nothing is queued.
- New: `setEnabled(false)`, an opt-out switch for consent flows (DPDP, GDPR). While off the SDK sends
  no open reports or events and does no deferred matching; reports saved on the device and the remembered
  tap are deleted. Kept across launches (`strait.enabled` in `storage`); `setEnabled(true)` resumes and runs
  a first-launch check that was skipped while off (on the executor). `isEnabled()` reads it. Default on.
  Links that carry their own destination still reach `onLink`. A Strait short link still routes: while
  off the SDK asks the engine's `/v1/lookup` (key + host + path, no query), which records no tap, open or tap
  id, and queues no report; if that lookup fails (offline, or an engine without it) the link comes back
  `reason = "disabled"`. ADR 0001; engine ADR 0083.
- New: a matched deferred `LinkEvent` has `match: MatchInfo(method, confidence)`, how the engine matched
  (`install_referrer`, `fingerprint`, `test`, ...) and how sure it is (`exact`, `high`, `probable`); null when
  not matched or the engine doesn't send it (engine ADR 0081). New `data class MatchInfo`.

## 0.9.0 (5 Nov 2026)

- Fix: reinstalls from a link are no longer lost. Android Auto Backup puts the "deferred check done"
  flag back after a reinstall, so the reinstall skipped its deferred link and was never counted
  (reproduced on the emulator with `bmgr`). New `StraitConfig.installTime` (pass
  `packageManager.getPackageInfo(packageName, 0).firstInstallTime`): the SDK stores it as
  `strait.installTime` and runs the check again when it changed; an app update, or an app updated
  from an older SDK, is never a new install (contract B6a, shared-spec ADR 0003). README shows how to
  keep the Strait prefs out of backups too.
- New: `trackEvent(…, eventId = order.id)`; the engine stores each eventId once. Left out, the SDK
  makes one, so a retry never counts twice. Events that can't be sent (offline, 429, 5xx) are kept in
  the open-report queue and sent later; `pendingOpenReports()` counts them (contract B13a, ADR 0004).
- Fix: when the server is busy (429 or 5xx) the saved queue no longer sends its whole backlog on every
  foreground. A flush stops at the first 429/5xx and the queue waits 15–30 s, doubling each time in a
  row up to 1 hour, and at least the server's `Retry-After` (contract B14 backoff, C1, shared-spec ADR
  0005). New opens and events are still sent once when they happen.
- Conformance vectors v11 (`firstLaunchState`, `retryDelay`).

## 0.8.4

- `doctor()` now gives exactly the findings of the other Strait SDKs (React Native 0.9.3, Swift 0.9.4,
  Flutter 0.9.4) for every case in the shared `doctor-vectors.json` (43 cases, every ADR 0002 row):
  a blank package counts as unknown (`app.unknown`) and a padded one is trimmed; blank certificates are
  ignored; `cert.missing` names every certificate passed, not only the first; an
  apple-app-site-association's `appIDs` are listed before the legacy `appID`, once each.
- `doctor()` prints each finding, `[Strait doctor] <severity> <id>: <title>. Fix: <fix>`, the same line
  as the other SDKs (logcat tag System.out); 0.8.3 logged nothing. If you logged the findings yourself,
  you can drop that loop.

## 0.8.3

- `doctor()`: the `host.file_missing` title names the bare file, matching the other Strait SDKs:
  "<host> doesn't serve assetlinks.json" (0.8.2 said "/.well-known/assetlinks.json"). No other change.

## 0.8.2

- New `StraitClient.doctor(packageName, certSha256)`: a setup check for debug builds (contract B23,
  shared-spec ADR 0002). It checks the key (publishable, not a secret key), the endpoint (an https link
  domain), and whether the link host's `/.well-known/assetlinks.json` lists your package and this build's
  signing certificate. One GET through the configured transport; blocking (call it off the main thread);
  never throws; logs nothing. Returns `DoctorReport(ok, findings)` with `DoctorFinding(id, severity,
  title, fix)`, the same ids and wording as every Strait SDK. The pure `doctorFindings(...)` (with
  `DoctorFile`) is public for wrappers such as Capacitor. README: "Check your setup (doctor)".

## 0.8.1

- `/v1/resolve`, `/v1/open` (queued retries too) and `/v1/store-sheet` now send `deviceClass`
  (`"phone"` | `"tablet"`, contract B14, engine ADR 0039). `StraitConfig.deviceClass` defaults to the
  smallest-width rule (`Configuration.smallestScreenWidthDp` >= 600 = tablet), read from the system
  configuration; `null` sends nothing. New `deviceClassForSize`. Older engines ignore the field.
  Conformance vectors v9.
- Docs: the store sheet is labelled new (not beta) in the README and KDoc; the README's iPhone rows
  match the platform status page (iPhone links live, install matching opt-in). No behaviour change.

## 0.8.0

- Smaller public API (SDK-23, decision record: shared-spec `docs/adr/0001-sdk-public-api-surface.md`).
- Removed the unused legacy deferred-link pieces: `Strait.buildMatchBody`, `Strait.buildReferrerBody`,
  `MatchResult` and `HttpPoster`. Nothing in the SDK called them, and a body built with them had no
  `openId` or `at`, so an app using them skipped contract rules B6 and B14. Use `StraitClient`
  (`start()`, `onLink`, `checkDeferred()`), which does the deferred match for you.
- The contract helpers in `Core.kt` are now `internal`: `classifyUrl`, `splitUrl`, `SplitUrl`,
  `normalizeLinkHosts`, `parseStraitLink`, `parseStraitClick`, `takeClickId`, `pageLinkLongLink`,
  `pruneOpenQueue`, `rememberTap`, `eventClickId`, `staleTap`, `reportUrl`, `replyClickId`,
  `replyReferralCode`, `shouldRetryReport`, `newOpenId`, `browserScreenWidth`, `AppStateTracker` and the
  queue and window constants. None were in the docs. Still public: `StraitClient` and its types,
  `DeviceFields`, `portraitScreenWidth`, `AppLifecycleState`, `AppStateAtLink`, `LinkRoute`,
  `computeSignature`, `h32` and the store sheet.

## 0.7.4

- `checkDeferred()` (the debug / settings re-check) now sends `"record": false` on `/v1/match` and `/v1/referrer`, so the
  engine answers the match without adding an install, an open or an `install.matched` webhook
  (contract B6: it never adds an install). The once-per-install check is unchanged. Takes effect
  once the engine with `record` support is live; an older engine ignores the field.

## 0.7.3

- README: the common Strait SDK header (logo, the promise "Straight to the screen. On the record.",
  badges, links to docs, platform status and the changelog), a platform features table in the
  availability words (Live / Beta / Planned / Not yet) with the iPhone beta truth (the method is
  the customer's choice, device matching off by default), and a "Docs and support" section
  (Talk to the Strait team). The same structure in all seven SDK READMEs (design system v5).
- Store sheet (beta): `StraitClient.openStoreSheet(url, launcher, options)` (also
  `Strait.openStoreSheet(client, …)`) opens Google Play inside your app, inline install
  first, then `market://`, then the web listing, with the Play Install Referrer carrying
  `strait_link` + `strait_click` from the engine's `POST /v1/store-sheet`. New `StoreSheet`,
  `StoreIntent`, `StoreLauncher`, `StoreSheetOptions`, `StoreSheetResult`.
- Old Firebase Dynamic Links (shared-spec/SDK-CONTRACT.md B22, conformance vectors v8). Add your
  `<x>.page.link` host to `linkHosts` (and keep the page.link intent filter in your app build): a page.link
  **short** link is looked up through `/v1/resolve` like any short link (the engine matches the old host +
  code of links you imported), and a page.link **long** link (`https://<x>.page.link/?link=<url>&…`) opens
  its `link=` destination straight away, on the device, with no lookup. Only `*.page.link` hosts at the root
  path, only an http(s) `link`. New core function `pageLinkLongLink`. Not yet tested on a real phone.

## 0.7.2

- Referral codes (preview; shared-spec/proposals/referral-code.md, B21): a matched
  deferred `LinkEvent` (Play referrer or `/v1/match`) carries `referralCode` when the
  engine's reply has a valid one. New core function `replyReferralCode`. (In the
  `v0.7.2` tag; earlier listed here as Unreleased.)
- Privacy hardening (shared-spec/SDK-CONTRACT.md B18): the URL sent to `/v1/open` and
  `/v1/resolve` and saved in the offline retry queue (`strait.pendingOpens`) no longer
  carries the query string or fragment, except the first `utm_source` pair, which the
  engine uses for channel attribution. Reports saved by an older version are stripped the
  next time the queue is read. `LinkEvent` (what your app routes on) is unchanged.
- An expired remembered tap id (`strait.lastTap`, older than 7 days) is now deleted at
  `start()` and by `trackEvent`, not only ignored.
- New `Core.kt` functions: `reportUrl`, `staleTap` (conformance vectors v6).
- Gradle wrapper: `distributionSha256Sum` pins the official Gradle 9.8.0 checksum.

## 0.7.1

- Report the portrait screen width so a first launch in landscape still matches the tap
  (shared-spec/SDK-CONTRACT.md B17): new `Core.kt` function `portraitScreenWidth(w, h)`
  (the shorter side, rounded like the browser; conformance vectors v5). Build
  `DeviceFields.screenWidth` with it from `widthPixels` and `heightPixels`, as the README
  now shows.

## 0.7.0

- Every attributed open now supplies the tap id (shared-spec/SDK-CONTRACT.md B16):
  when `/v1/resolve` (verified short link), `/v1/match` (fingerprint) or
  `/v1/referrer` (Play install) returns `clickId`, it is remembered as
  `strait.lastTap` and sent with conversion events. A reply without one (older
  engine) keeps the 0.6.0 behaviour.
- New core function: `replyClickId` (conformance vectors v4).

## 0.6.0 (2026-10-04)

- Conversion events carry the tap id (shared-spec/SDK-CONTRACT.md B15): the tap id
  of the last attributed link open (browser hand-off `strait_click`, or the Play
  referrer on a deferred install) is remembered under `strait.lastTap` and sent as
  `clickId` with `trackEvent` for 7 days. A newer short-link or fingerprint open
  forgets it. `trackEvent(name, clickId = …)` overrides it.
- New in `Core.kt`: `eventClickId`, `rememberTap`, `ATTRIBUTION_WINDOW_MS`
  (conformance vectors v3).

## 0.5.0 (2026-10-03)

**Renamed to Strait** (breaking, clean break; no aliases for the old names).

- New names: Kotlin package `dev.strait.sdk`; `StraitClient`, `StraitConfig`,
  `object Strait`, `parseStraitLink`, `parseStraitClick`.
- Wire params: the Play referrer / hand-off params are now `strait_link` and
  `strait_click` only.
- Storage keys: `strait.deferredChecked`, `strait.pendingOpens` (old values are ignored).
- Artifact / repo: `com.github.bazingga08:strait-sdk-android`; brand.json is final
  (Strait, https://usestrait.com, strait.link; the copyright holder is now "The Strait authors").
- Shared vectors (`conformance-vectors.json`) follow the rename.

## 0.4.0 (2026-10-03)

Report every link open exactly once (shared-spec/SDK-CONTRACT.md B14, plus the
B4/B6/B7 revisions). Shared vectors are now `conformance-vectors.json` v2.

- Every open gets an id from `newOpenId` (`o_<base36 ms>_<12 × [a-z0-9]>`),
  which is also `LinkEvent.id` (was `evt_<ms>_<n>`).
- Short links send `openId`, `appState`, `firstLaunch` and `at` with
  `/v1/resolve`. If the reply lacks `recorded: true`, the open is reported via
  `/v1/open`.
- Custom-scheme hand-offs and your own https links are reported via
  `POST /v1/open` after the event is emitted, so navigation never waits.
- A `strait_click` tap id is removed from the destination (`takeClickId`) and
  sent as `clickId`. `classifyUrl` now returns `clickId`.
- Reports that get no answer, 429 or 5xx are saved under `strait.pendingOpens`
  in `storage` and retried on start, on `onAppState(ACTIVE)` and after any
  successful report (pruned to 7 days / 100). New `pendingOpenReports()` and
  `flushOpenReports()`.
- Deferred: `/v1/referrer` now sends `clickId` (`parseStraitClick`), `openId`
  and `at`; `/v1/match` sends the same `openId` and `at`. The once-per-install
  flag is set only once the engine answered (no answer / 429 / 5xx → `network`,
  retried next launch). `checkDeferred()` sends no `openId`.
- New pure helpers: `parseStraitClick`, `takeClickId`, `pruneOpenQueue`,
  `shouldRetryReport`, `newOpenId`, `OPEN_QUEUE_MAX`, `OPEN_QUEUE_MAX_AGE_MS`.

### Packaging

- Publish-ready on JitPack: `maven-publish` with sources + javadoc jars and full
  POM metadata (name, description, URL, MIT license, developer, SCM, issues),
  Gradle wrapper and `jitpack.yml` (JDK 17). Coordinates
  `com.github.<owner>:<repo>:v<version>`. Test vectors never ship.
- Owner, repo name, URLs and copyright holder come from `brand.json` (read by
  Gradle directly; README install block + LICENSE applied by `scripts/brand.mjs`).
- Tag `vX.Y.Z` → GitHub Actions runs the tests, builds the Maven artifacts and
  (once switched on) asks JitPack to build the tag. See PUBLISHING.md.

## 0.3.0 (2026-10-03)

Parity with the React Native SDK (shared-spec/SDK-CONTRACT.md B1–B13).

- **New `StraitClient`**: `start(initialUrl)`, `handleUrl`, `onAppState`,
  `onLink` (with replay), `onLinkStart`, `checkDeferred`, `reportFingerprint`,
  `compareFingerprint`, `trackEvent` and `stop`. It is pure JVM, and the Android
  pieces are injected through `StraitConfig`.
- Short links on the endpoint host or a configured `linkHosts` entry are
  resolved via `POST /v1/resolve`. The engine's `reason` (`expired`,
  `not_found`, …) is reported.
- Deferred check runs once per install (`strait.deferredChecked`) and is skipped
  when the first launch was opened by a link. The referrer `strait_link` goes to
  `/v1/referrer`, with a fallback to `/v1/match`.
- App-state labels (`closed` / `background` / `foreground`) come from
  `AppStateTracker`, which handles Android's brief delivery pause and links
  delivered before resume.
- Pure helpers in `Core.kt` (`browserScreenWidth`, `splitUrl`, `parseStraitLink`,
  `classifyUrl`, `AppStateTracker`) pass `conformance-vectors.json`.
- Link handling never throws. A network failure gives `matched=false`,
  `reason="network"`.
- `parseStraitLink` now %-decodes the link id (`lnk%5F7` → `lnk_7`), matching
  the reference.
- Build: `org.json` is now `compileOnly` (Android provides it). Version is set
  to 0.3.0.
