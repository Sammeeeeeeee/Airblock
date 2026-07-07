# Airblock — Review Findings (Phase 2)

Severity: 🔴 Critical · 🟡 Should-fix · ⚪ Nit.
Each finding is tagged **Confirmed** (I traced the code path) or **Needs-verification**
(plausible from the code, not fully proven at runtime).

The tone below is deliberately that of a reviewer who is *not* charmed by the prose
comments. The code is genuinely careful in places — the gating state machine and the
DataStore atomic swaps are good work. But the comment-to-assertion ratio is doing a
lot of load-bearing, and several of the confident claims in those comments are wrong.

---

## Security (OWASP MASVS)

### 🔴 S1 — Release APK is signed with the debug key *(Confirmed · MASVS-RESILIENCE / code-quality)*
`app/build.gradle.kts:63-65` — `release { signingConfig = signingConfigs.getByName("debug") }`.
The debug keystore uses the world-known password `"android"` (`build.gradle.kts:48-49`)
and, in CI, a keystore restored from a repo secret. Consequences: (a) **anyone** can build
an APK that installs *over* this one (same signing identity), which is the whole basis of
Android update/permission/shared-UID trust; (b) it is an automatic Play Store rejection.
The comment calls this a "personal app" choice — fine as an intent, but it's the single
biggest integrity weakness in the repo and must be named as such.

### 🟡 S2 — AeroAPI key can be baked into the APK in cleartext via BuildConfig *(Confirmed · MASVS-STORAGE)*
`build.gradle.kts:35` compiles `AERO_API_KEY` from `local.properties`/env into
`BuildConfig.AERO_API_KEY`, and `SecureKeyStore.kt:59` reads it back and migrates it into
EncryptedSharedPreferences. Yes, it defaults to empty and the comments push you toward the
secure path — but the moment anyone sets `AERO_API_KEY` for "convenience," the paid
credential ships as a plaintext string constant in the APK (`strings app-release.apk` finds
it) *and* is duplicated into the encrypted store. A footgun that reads as a feature. At
minimum this seam should not exist in `release`.

### 🟡 S3 — Precise location + foreground app names written to Logcat, in release builds *(Confirmed · MASVS-PRIVACY / STORAGE)*
`Ticker.kt:359` logs the user's exact fix: `Log.d(TAG, "tick @%.2f,%.2f: …")`. `Gates`/
`UpdateService` log the **foreground package** the user is looking at
(`UpdateService.kt:188-189`, `"hidden: fg=…"`). None of this is gated by `BuildConfig.DEBUG`
or the log toggle, and `proguard-rules.pro` has **no** `-assumenosideeffects` rule stripping
`android.util.Log` (17 Log calls across the engine survive into release). On Android 12+ apps
can't read each other's logcat, but adb, USB, and bug-reports capture it — so a shipped build
leaks GPS coordinates and a record of which apps the user opens. Strip Log in release and/or
gate the coordinate/PII lines.

### 🟡 S4 — No backup rules; EncryptedSharedPreferences will break (or crash) on restore *(Needs-verification · MASVS-STORAGE)*
The manifest sets no `android:allowBackup`, `android:dataExtractionRules`, or
`android:fullBackupContent` (grep: none in the repo). `allowBackup` therefore defaults to
**true**, so the `airblock_secrets` encrypted file is eligible for Auto Backup — but its
Keystore master key is device-bound and is *not* backed up. On restore to a new device the
ciphertext is undecryptable and Tink/EncryptedSharedPreferences throws when the file is
opened — and `SecureKeyStore` opens it lazily on a background thread with no catch
(`SecureKeyStore.kt:38-49`), so every accessor can start throwing. Exclude the secrets file
from backup (or set `allowBackup="false"`) and wrap the keystore open in a rebuild-on-failure.

### 🟡 S5 — Unbounded response bodies read fully into memory *(Needs-verification · MASVS-CODE / resilience)*
Every client does `resp.body?.string()` / `.bytes()` with no size cap: `AdsbApi.closest`/
`nearestAirborne`/`route` (`AdsbApi.kt:53,72,103`), `AeroApi` (`AeroApi.kt:101,140`),
`PhotoRepo`/`AirlineLogoRepo` image downloads, and the **~40k-row plane-alert CSV**
(`PlaneAlertRepo.kt:44`, `body.bytes()`). A captive portal, a broken CDN, or a hostile
mirror returning a multi-MB body OOMs the process. Cap with a `.peekBody(limit)` or a bounded
read, especially on the CSV and image paths.

### ⚪ S6 — No certificate pinning on the paid credential's host *(Needs-verification · MASVS-NETWORK)*
The AeroAPI `x-apikey` bearer credential is sent to `aeroapi.flightaware.com` over stock TLS
with no pinning (`AeroApi.kt:94-97`). Fine for the free community feeds; for a billable key,
a user-installed/corporate MITM root can lift it. Consider pinning that one host. Everything
else is HTTPS and `targetSdk 35` disables cleartext by default, so no cleartext exposure.

### ⚪ S7 — IPC surface is clean *(Confirmed — noting the negative)*
`MainActivity` is exported only as LAUNCHER (no data/deep-link parsing, so no intent
redirection); `UpdateService` and `BootReceiver` are `exported=false`; the widget receiver
must be exported (framework requirement); the dynamic receiver listens **only** to protected
system broadcasts, so it's exempt from the Android 13 `RECEIVER_*_EXPORTED` flag. **No
WebView anywhere.** Good — but it's the low bar, not a mitigation for S1–S5.

---

## Concurrency & lifecycle

### 🟡 C1 — `LocationProvider` leaks a `CoroutineScope` on every KeepAliveWorker run *(Confirmed)*
`LocationProvider.kt:35` creates `CoroutineScope(SupervisorJob() + Dispatchers.IO)` that is
**never cancelled**, and `currentFix()` launches a background fix into it
(`LocationProvider.kt:58`). `KeepAliveWorker` builds a fresh `Ticker(ctx)` every 15 minutes
(`KeepAliveWorker.kt:58`), each `Ticker` builds a fresh `LocationProvider`
(`Ticker.kt:42`), so each worker invocation strands a `SupervisorJob` + any in-flight fetch.
Not catastrophic (short-lived work), but it's an unmanaged scope that violates the app's own
otherwise-disciplined structured-concurrency story. Give `LocationProvider` a `close()`/scope
owned by the caller, or use the worker's own scope.

### 🟡 C2 — Location keeps flowing after the user revokes permission *(Confirmed)*
`LocationProvider.currentFix()` gates only the *fresh-fetch* branches on `hasPermission()`;
the final fallback `lastGood?.let { return Fix(...) }` (`LocationProvider.kt:79`) is
**outside** the permission check. Once a fix is cached in memory, revoking location does not
stop the widget from producing location-derived results — it silently serves the last known
coordinates until the process dies. Both a correctness and a privacy edge case; re-check
`hasPermission()` before returning `lastGood`.

### ⚪ C3 — Per-instance caches don't dedupe across service + worker *(Confirmed)*
`cachedRoute` / `cachedAero` are instance fields of `Ticker` (`Ticker.kt:52-55`). The service
and the keep-alive worker hold *separate* `Ticker` instances, so the "one billable AeroAPI
call per flight" guarantee is per-instance. `AeroStore.recordRequest` still enforces the hard
cap atomically, so no budget breach — but a worker tick and a service tick on the same flight
can each spend one request. Minor money leak; note it.

### ⚪ C4 — `NetworkCard` rebuilds `Gates` and hits ConnectivityManager every second *(Confirmed)*
`MainActivity.kt:1287-1297` — a `while(true){ … delay(1000) }` that constructs `Gates(context)`
and calls `networkTransport()` + `dataSaverOn()` once per second for as long as the settings
screen is open. Lifecycle-scoped so it's not a leak, but it's needless churn; cache the Gates
instance and widen the interval.

---

## Architecture & maintainability

### 🟡 A1 — `Ticker.tick()` is a ~290-line god-method *(Confirmed)*
`Ticker.kt:105-391`. One function does location, aircraft selection, a 40-field inline
`buildState` closure, two-phase publish, three parallel enrichment launches, AeroAPI
budgeting, logging, and error handling. `route`/`leg`/`geo` are `var`s reassigned inside a
launched coroutine (`Ticker.kt:180-183,292-297`). It works, but it is untestable as a unit,
and it's exactly the code where the money-spending and freshness logic lives. This is the
class most in need of extraction (selection → enrichment → state-building as separate,
testable steps).

### 🟡 A2 — "Mode → interval" logic is duplicated three times *(Confirmed)*
The transport/mode→interval-ms mapping exists in `Gates.effectiveMode` (`Gates.kt:130-142`),
is recomputed in `Ticker` (`Ticker.kt:119-128`), and re-implemented **again** in the UI's
`NetworkCard` (`MainActivity.kt:1299-1316`). `transportOf` is likewise duplicated between
`Gates.kt:112` and `UpdateService`'s network callback (`UpdateService.kt:77-83`). Three places
to change one rule; they *will* drift. Centralize in `Gates`.

### 🟡 A3 — `WidgetState.renderKey()` is a hand-maintained 35-field string *(Confirmed)*
`WidgetState.kt:121-133`. Every render-relevant field must be manually appended here or the
widget silently stops (or over-) redrawing. Adding a field to the 40-field data class and
forgetting this line is a latent bug with no compiler help and no test. Derive it, or at least
test it.

### ⚪ A4 — `MainActivity.kt` is 1,678 lines *(Confirmed)*
The whole settings screen, status card, network card, log screen, permission rows, and the
AeroAPI card live in one file with private composables. Split by section; it's a merge-conflict
magnet and hard to navigate.

---

## Performance

### 🟡 P1 — RemoteViews 1 MB bitmap budget risk on large widgets *(Needs-verification)*
`AirblockWidget` ships multiple freshly-allocated `ARGB_8888` bitmaps per update: the decoded
photo, a **second** full-size rounded copy (`roundCorners`, `AirblockWidget.kt:668-679`), the
callsign text bitmap, the route-progress bitmap (480×64), and the placeholder (480×320). All
RemoteViews bitmaps for a widget share a ~1 MB Binder transaction budget; on a large/expanded
widget this can exceed it, which surfaces as the widget silently not updating (a symptom the
`forceFullRestart` "nuclear option" at `UpdateService.kt:311` seems to exist to paper over).
Downsample to the actual displayed size and round in-place rather than allocating a copy.

### ⚪ P2 — 40k-row CSV parsed on the tick thread, cached forever *(Confirmed)*
`PlaneAlertRepo.load()` parses ~40k lines into a process-wide `@Volatile static` map
(`PlaneAlertRepo.kt:56-92,102-103`) on first `lookup()`, which happens **on the refresh path**
(`Ticker.kt:188`). First tick after cold start blocks on that parse; the map (a few MB) is
then never released. Acceptable, but move the first parse off the tick or preload it in the
worker, and consider trimming.

### ⚪ P3 — Double bitmap decode per photo *(Confirmed)*
`AirblockWidget.decodePhoto` downsamples (`:89-97`) and then `roundCorners` allocates another
full copy (`:668`). Two allocations where one would do.

---

## Testing & build hygiene

### 🔴 H1 — An 8 MB `platform-tools.zip` is committed to the repo *(Confirmed)*
`git ls-files` shows `platform-tools.zip` (8,092,164 bytes) tracked at the repo root, and it
is **not** in `.gitignore`. A downloaded Android SDK archive has no business in source
control; it bloats every clone and is now in history. Remove it, add it to `.gitignore`, and
purge it from history (`git filter-repo`) if you care about clone size.

### 🟡 H2 — The one piece of money-spending logic is untested *(Confirmed)*
Tests exist only for pure `util/` + CSV parsing (`UnitsTest`, `AirlineCodesTest`,
`PlaneAlertTest`, `ParsingTest`). **Untested**: `AeroStore`'s hard cap, budget trip, and
UTC-month rollover (`AeroStore.kt:46-53,119-148`) — i.e. the guardrail that keeps the paid
API inside its free allowance; `Gates.effectiveMode`'s decision matrix; `WidgetState.renderKey`;
`LocationProvider`'s fallback ladder. Name these, not a percentage — the billing cap is the
first thing I'd want a test around, and there isn't one.

### 🟡 H3 — Log statements not stripped from release *(Confirmed — see S3)*
No `-assumenosideeffects class android.util.Log { *; }` in `proguard-rules.pro`. Overlaps S3;
listed here too because it's a build-config fix, not just a privacy one.

### ⚪ H4 — CI ships a debug APK as a release asset *(Confirmed)*
`.github/workflows/build.yml:111-153` uploads `airblock-<tag>-debug.apk` next to the release
APK, and the release APK is itself debug-signed (S1). Fine for personal sideloading; call it
out so it isn't mistaken for a real release pipeline.

---

## Compliance (Play Store blockers)

### 🟡 CP1 — Debug signing blocks submission outright *(Confirmed — see S1)*
Play rejects debug-signed uploads. This is the hard gate before any policy discussion.

### 🟡 CP2 — Sensitive/restricted permissions needing Console declarations *(Confirmed)*
From `AndroidManifest.xml`:
- `ACCESS_BACKGROUND_LOCATION` (`:9`) — requires the location-permission declaration form,
  prominent-disclosure UX, and a review video. Worth questioning whether it's even needed
  (CP3).
- `PACKAGE_USAGE_STATS` (`:19-21`) — special/restricted; heightened review, frequently
  disallowed unless core.
- `FOREGROUND_SERVICE_SPECIAL_USE` (`:11`, service at `:59-66`) — requires a Console
  justification, and Google regularly rejects `specialUse` for "refresh a widget" when a
  standard FGS type is deemed to fit.
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (`:15`) with the direct
  `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` dialog (`MainActivity.kt:201-207`) — an
  Acceptable-Use-restricted flow; apps outside allowed categories get pulled.

### 🟡 CP3 — Background location may be broader than needed *(Needs-verification)*
The entire gating premise is "only tick while the launcher/home screen is visible" — i.e.
while-in-use. If refreshes truly only happen in the foreground, `ACCESS_BACKGROUND_LOCATION`
may be droppable; the only background path is `KeepAliveWorker`, which itself re-checks
`launcherForeground()` before ticking (`KeepAliveWorker.kt:55-58`). Reassess — dropping it
removes the single hardest permission review.

---

## Prioritized punch-list (fix these first)

1. **🔴 S1 / CP1 — Stop shipping a debug-signed release.** Real upload key in a
   `signingConfig`, secrets out of the Gradle file. Everything else is downstream of this.
2. **🔴 H1 — Delete `platform-tools.zip` from the repo** (and history) and gitignore it.
3. **🟡 S3 / H3 — Strip `android.util.Log` in release and stop logging GPS coords + foreground
   app names.** One proguard line + delete/guard the `tick @lat,lon` and `fg=` lines.
4. **🟡 S4 — Set backup rules** (`allowBackup=false` or exclude `airblock_secrets`) and make
   `SecureKeyStore` survive an undecryptable keyset instead of throwing.
5. **🟡 S2 — Remove the BuildConfig API-key seam from release builds** so the paid key can
   never be compiled into the APK.
6. **🟡 C2 — Re-check location permission before returning the cached `lastGood` fix.**
7. **🟡 H2 — Test `AeroStore`'s cap/budget/month-rollover** — the code that spends money.
8. **🟡 S5 — Bound response-body sizes** (CSV + images especially) to close the OOM path.
9. **🟡 A2 — Collapse the three copies of the mode→interval logic** into `Gates`.
10. **🟡 P1 / A1 — Trim the widget's per-update bitmap allocations** and start carving
    `Ticker.tick()` into testable pieces; the `forceFullRestart` escape hatch is a smell that
    the render path is already fighting the RemoteViews budget.
