# Airblock

A 4×2 Android home-screen widget showing the **nearest aircraft** overhead: callsign, aircraft
type, photo, origin → destination with country flags, plus altitude (ft), ground speed (mph) and
distance (km). Emergency squawks (7500/7600/7700) appear as a red alert chip. Material 3
Expressive with full dynamic color.

Built to be **as light as possible** for 24/7 use:

- Refreshes every 15 s **only while the widget is actually visible** — screen on *and* the
  launcher in the foreground (Usage Access check). Zero work during other apps, screen-off,
  or battery saver.
- One ~1.5 KB request per tick (`api.adsb.lol/v2/closest`). Route and photo are fetched **once
  per flight/aircraft** and cached (Planespotters.net thumbnails, disk LRU).
- Location is read from the system's cached fused fix (free); at most one balanced-power fix
  per 10 min; user-saved home coordinates as fallback.
- Widget re-renders only when visible data actually changed.

## Data sources
- Live aircraft: [adsb.lol](https://adsb.lol) community ADS-B network (free, no key)
- Photos: [Planespotters.net](https://www.planespotters.net) public API — © the photographers
- Real flight times (optional): [FlightAware AeroAPI](https://www.flightaware.com/commercial/aeroapi/) —
  scheduled/actual arrival in place of the ETA estimate. Opt-in under **Tuning → Flight times**;
  the key is stored in the Android keystore (never in the repo or APK), and usage is capped to
  stay inside the feeder's free $10/month allowance, auto-disabling when spent.

## Build
Requires Android Studio (or Android SDK 35 + JDK 17). `gradlew assembleDebug`, deploy with
`gradlew installDebug` over (wireless) ADB. minSdk 31 (Android 12+).

After placing the widget: open the Airblock app once to grant **precise location ("Allow all
the time")** and **Usage access**, and optionally set a home-location fallback.
