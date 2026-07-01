# Airblock — App Map (Phase 1)

Due-diligence code review. This file is the pre-review inventory; findings live in
`REVIEW_FINDINGS.md`.

## What the app is

A single-purpose Android **home-screen widget** that shows the nearest aircraft to
your location (callsign, type, altitude, speed, distance, route, photo, airline
logo, and optional real FlightAware schedule times). There is one thin settings
Activity; the widget is the product. Data comes from free community APIs
(adsb.lol, planespotters.net, Kiwi logo CDN, plane-alert-db) plus an optional paid
FlightAware AeroAPI key the user pastes in.

## Module / build inventory

| Aspect | Value |
|---|---|
| Gradle modules | Single `:app` (no library modules) |
| AGP | 8.7.3 |
| Kotlin | 2.1.20 |
| compileSdk / targetSdk | 35 / 35 |
| minSdk | 31 (Android 12) |
| Java/JVM target | 17 |
| Gradle wrapper | **None committed** — CI injects 8.10.2 via `setup-gradle` |
| DI framework | **None** — manual `new` everywhere |
| Networking | OkHttp 4.12.0 + kotlinx-serialization-json 1.7.3 (one shared `Http.client`) |
| Persistence | DataStore Preferences (settings, widget state as a JSON blob, AeroAPI quota); `EncryptedSharedPreferences` (Keystore) for the one secret; flat files in filesDir/cacheDir for logs, photos, logos, plane-alert CSV |
| Async model | Coroutines + Flow (no RxJava). `StateFlow` wake channel in the service |
| Background exec | A `specialUse` foreground `Service` (15 s loop) + `WorkManager` `CoroutineWorker` keep-alive (15 min) + `BOOT_COMPLETED` receiver |
| UI toolkit | Jetbrains Compose — Material3 `1.5.0-alpha18` (M3 Expressive) for the app; **Glance** `1.2.0-rc01` (RemoteViews) for the widget |
| Location | Google Play Services fused provider (`play-services-location` 21.3.0) |

## Source layout (~30 Kotlin files)

- `ui/MainActivity.kt` — **1,678 lines**, the entire settings UI + status card + log screen in one file.
- `engine/` — `UpdateService` (the 15 s loop + gate state machine), `Ticker` (one refresh cycle, ~290-line `tick()`), `Gates` (all the "may we tick?" checks), `LocationProvider`, `KeepAliveWorker`, `BootReceiver`.
- `data/` — API clients (`AdsbApi`+`Http`, `AeroApi`), repos (`PhotoRepo`, `AirlineLogoRepo`, `ManufacturerLogoRepo`, `PlaneAlertRepo`), stores (`Settings`, `AeroStore`, `WidgetState`), `SecureKeyStore`, `EventLog`, `Models`.
- `widget/` — `AirblockWidget` (Glance renderer, ~950 lines of Compose + Canvas bitmap drawing), `AirblockWidgetReceiver`, `RefreshAction`.
- `util/` — pure helpers (`Units`, `AirlineCodes`, `TypeNames`, `AircraftIcons`, `Squawk`, `SpecialType`).
- `test/` — 4 unit-test files, all over `util/` + CSV parsing only.

## Intended architecture & where it drifts

There is no declared pattern; the *de facto* shape is **"stores + engine"**:

- **State container**: `WidgetState` (a ~40-field `@Serializable` god object) persisted as one JSON string in DataStore. Everything reads/writes it through `WidgetStateStore.update {}` (atomic read-transform-write).
- **Engine**: `Ticker.tick()` orchestrates a refresh; `UpdateService` drives cadence and gating.
- **UI**: Compose reads `WidgetStateStore.flow()`; the widget reads the same flow.

It is *not* MVVM/MVI — there are no ViewModels; the Activity's composables own their own state and call stores directly. That's defensible at this size, but the consequences show up in the findings: business logic (quota caps, mode→interval math, freshness) is scattered across `Gates`, `Ticker`, `AeroStore`, and `MainActivity` with **duplicated copies**, and the untestable orchestrator (`Ticker`) holds the logic that actually spends money. The cleanest layers are `data/` API clients and `util/`; the muddiest are `Ticker` and `MainActivity`.
