# Airblock

A 4×2 Android home-screen widget (plus the slimmer **Flight strip**, below) showing the **nearest aircraft** overhead: callsign, aircraft
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

## Flight strip
A second widget, next to the card above. It shows the nearest aircraft in less space, and only
its own shapes cover the wallpaper: the rest of the widget is see-through.

- **The whole photo**, never cropped or zoomed, joined to the card as one band — tight corners
  where they meet, round ones outside, like Material 3 Expressive's connected button groups.
- **Callsign with the model's and the airline's logos** beside it ("BAW8KC · A320neo · BA").
  Airbus and Boeing 717/777 logos drop the maker's wordmark so they fit beside the callsign.
- **The route under it**: `LHR ∿✈── GLA · 34 min left`, the wavy line showing how far along
  the flight is.
- **A stats pill coming off the photo**, docked along the band's bottom and notched into the
  photo, so it costs no extra height. Swipe it up or down, or tap it, for the next page: height
  (with a climb/descent arrow), speed and distance · where to look (a compass needle, "NW 34° up")
  · landing time and delay (with flight times on) · registration, type and Mach. An emergency
  squawk goes first. Launchers that turn widget stacks over by themselves also turn these pages.
- **The airline's colours.** The strip takes its tones from the airline's logo (Material's
  colour-from-content, set in OkLCH so every livery keeps the same contrast in light and dark).
  No logo, or one with no real colour: the wallpaper's colours. Military aircraft and emergencies
  turn it red, other special aircraft tertiary.
- Two cells tall gives all of that; one cell gives the photo and the card. A new aircraft's lines
  roll in on the Material 3 Expressive spring.

Text is Google Sans Flex, the Material 3 Expressive typeface (SIL Open Font License, subset
bundled; licence in `app/src/main/assets/licenses`). Launchers refuse app fonts in widget text,
so the strip draws its text as images the launcher tints: it still follows light and dark.

**On Niagara:** place it under the clock, 4 wide. Left/right swipes belong to Niagara's widget
stacks, so the stats page up/down. The strip keeps clear of the A–Z bar on the right, where
Niagara takes taps. If your Niagara version does not pass up/down swipes to widgets, tap the
stats instead.

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

Alerts **clear themselves** once the aircraft has gone: about 5 minutes after it was last the
nearest aircraft (or 2 / 15 min, or never — **Notifications → Clear alerts**). Each refresh that
still sees it restarts the countdown, and Android does the clearing, so it happens on time even with
the screen off or another app open, when no refresh runs. On the 10-minute data-saving plan the
countdown never ends before the next refresh, so an aircraft still overhead isn't cleared between
looks.

Alerts only fire for aircraft within the **alert range** (5 / 10 / 25 km or any; default 25 km) and
say *overhead* only when the aircraft is at least 30° up in the sky, *nearby* otherwise. Each shows
when it went up; tap it to dismiss. An emergency squawk on an aircraft that is already alerted on
sounds again rather than updating the old alert silently. Alerts are checked on the widget's
refreshes, so they only fire while your home screen is on screen.

## Data sources
- Live aircraft: [adsb.lol](https://adsb.lol) community ADS-B network (free, no key)
- Photos: [Planespotters.net](https://www.planespotters.net) public API — © the photographers
- Real flight times (optional): [FlightAware AeroAPI](https://www.flightaware.com/commercial/aeroapi/) —
  scheduled/actual arrival in place of the ETA estimate. Opt-in under **Tuning → Flight times**;
  the key is stored in the Android keystore (never in the repo or APK), and usage is capped to
  stay inside the feeder's free $10/month allowance, pausing itself when spent and
  resuming when the allowance renews.
  Lookups are **paced**: the allowance still unspent is drip-fed over the time still left in the
  billing month (with a small burst so a run of new flights all resolve), so one busy afternoon
  can't eat the whole month and quiet days automatically raise the rate. The settings card shows
  the live rate — "Paced to ~50 lookups/day · 8 ready now".

## Build
Requires Android Studio (or Android SDK 35 + JDK 17). `gradlew assembleDebug`, deploy with
`gradlew installDebug` over (wireless) ADB. minSdk 31 (Android 12+).

Widget renders without a phone (Robolectric draws the real RemoteViews through Android's own
widget host): `gradle testDebugUnitTest -Pscreenshots`. PNGs go to `STRIP_RENDER_DIR`
(default `app/build/strip-renders`); `STRIP_SAMPLE_PHOTO`, `STRIP_SAMPLE_MODEL` and
`STRIP_SAMPLE_AIRLINE` can point at a cached photo and logos to draw with.

After placing the widget: open the Airblock app once to grant **precise location ("Allow all
the time")** and **Usage access**, and optionally set a home-location fallback.
