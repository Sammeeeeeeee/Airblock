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

## Alerts
Optional heads-up notifications when something worth looking up at goes over: emergency squawks,
military, government, historic aircraft, drones, or your own watchlist of registrations. They ride
on the refresh ticks already happening, so they cost no extra requests. Each alert carries the
aircraft's **photo** — posted the instant the aircraft is seen, with the Planespotters shot filled
into the same notification (no second buzz) as soon as it downloads.

Aircraft are named by their plane-alert-db **category** everywhere — the widget badge, the alert,
the settings switches — so whatever you read is a thing you can go and toggle; the database **tag**
rides along as a pill drawn onto the alert's photo. Watchlist entries take a **note** (shown on the
alert) and can be set to **track once**, which removes them 20 minutes after the aircraft was last
seen — for a single flight you're waiting on.

## Data sources
- Live aircraft: [adsb.lol](https://adsb.lol) community ADS-B network (free, no key)
- Photos: [Planespotters.net](https://www.planespotters.net) public API — © the photographers
- Real flight times (optional): [FlightAware AeroAPI](https://www.flightaware.com/commercial/aeroapi/) —
  scheduled/actual arrival in place of the ETA estimate. Opt-in under **Tuning → Flight times**;
  the key is stored in the Android keystore (never in the repo or APK), and usage is capped to
  stay inside the feeder's free $10/month allowance, auto-disabling when spent.
  Lookups are **paced**: the allowance still unspent is drip-fed over the time still left in the
  billing month (with a small burst so a run of new flights all resolve), so one busy afternoon
  can't eat the whole month and quiet days automatically raise the rate. The settings card shows
  the live rate — "Paced to ~50 lookups/day · 8 ready now".

## Build
Requires Android Studio (or Android SDK 35 + JDK 17). `gradlew assembleDebug`, deploy with
`gradlew installDebug` over (wireless) ADB. minSdk 31 (Android 12+).

After placing the widget: open the Airblock app once to grant **precise location ("Allow all
the time")** and **Usage access**, and optionally set a home-location fallback.
