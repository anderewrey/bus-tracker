# Bus Tracker

An unofficial Android app for **BusPlanner** parent portals ("Where's My Bus"). It watches your
child's school bus during the times you choose and rings an alarm when the bus enters a zone you
drew on the map, so you know when to head out to the stop.

> **Not affiliated with BusPlanner or its makers.** The app reads the same web pages your browser
> shows after you sign in to your portal. It was built and tested with **one portal only**; other
> portals may lay out their pages differently. Use it as a convenience, never as the only way you
> keep a child safe.

## What it does

- **Signs in to your portal** (any `https://` BusPlanner portal address, your email and password)
  and finds every student on the account and their bus runs (pickup and dropoff).
- **Watches each run during its own period**, started automatically on the days you pick, with a
  per-run Alarm switch. Checks the bus position every 30 s, like the portal's Refresh button.
- **Main zone, drawn on the phone:** tap the corners of an area around your stop. When the bus
  enters it, an alarm rings (through Do Not Disturb if you allow it).
- **Map** with the bus, your stop and the zone, a countdown to the next check, and how old the
  position is (old data shows in red).
- **BusPlanner notices** (delays, cancellations) for your bus ring once, and a delay extends the
  watching period.
- **Rescans** the account daily and shows what changed (time, stop, bus).
- Handles the portal's dropped sessions by itself: see [DESIGN.md](DESIGN.md) §2.2.

## Privacy

- Your password stays on the phone, encrypted with a key from the Android Keystore. It is only
  ever sent to the portal address you typed, over https (plain http is refused).
- The app talks to: your portal, the map tiles server ([OpenFreeMap](https://openfreemap.org)),
  and Android's own Geocoder for street names. Nothing else; no analytics.
- Logs stay on the phone; the Log screen can export them for debugging, and they can contain
  your children's names and positions, so share them with care.

## Building

Requirements: JDK 17 or newer, the Android SDK (compile SDK 37), and a device or emulator with
Android 10 (API 29) or newer.

```bash
./gradlew testDebugUnitTest assembleDebug
```

The APKs are in `app/build/outputs/apk/debug/`, one per CPU type: `app-arm64-v8a-debug.apk` for
most phones, `app-x86_64-debug.apk` for the emulator.

Every source file starts with an SPDX license header. A pre-commit hook refuses a commit without
it; turn it on once in your clone:

```bash
git config core.hooksPath .githooks
```

GitHub Actions runs the same header check, the unit tests and the debug build on every push.

## Documentation

- [DESIGN.md](DESIGN.md): how the portal works, what the app does and why, every finding so far.
- [ROADMAP.md](ROADMAP.md): what is done and what comes next.

The examples in the code and docs use real street names (This Street, That Street and The Other
Street, Porters Lake, Nova Scotia) with made-up stops, buses, schools and zones.

## License

[GPL-3.0-or-later](LICENSE): GPL version 3, or any later version. Apps built from this code must stay open source under the same license. Each source file says so in its first two lines (SPDX).
