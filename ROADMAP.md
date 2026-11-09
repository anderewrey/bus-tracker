# Bus Tracker roadmap

The order of the work. **What** each feature does and **how** is in [DESIGN.md](DESIGN.md); this file only says what is done, what is in progress and what comes next.

## Done

| Version | Date | What |
|---|---|---|
| 0.1 | 2026-11-03 | Login, account scan, Monitor now / Stop, 30 s checks, map, tracking notification |
| 0.2 | 2026-11-03 | Log screen, map Refresh, school name near the school, wake lock, page reload on a failed check |
| 0.4 | 2026-11-03 | Hardcoded main zone, insistent main-zone alarm, leaving the zone ends monitoring |
| 0.5 | 2026-11-04 | Full-form postbacks, stale-data reload, bulletin text, "no vehicle" warning, log files + Export |
| 0.6 | 2026-11-04 | Rescan diff, Changes screen, daily rescan (§3.2) |
| 0.7 | 2026-11-04 | Automatic start by exact alarm, per-run days, reschedule on boot / update / clock change (§5) |
| 0.8 | 2026-11-04 | Checks through the portal's Refresh button (fresh positions), Silence, Re-arm alarm, global Automatic start switch with "Off until" |
| 0.9 | 2026-11-05 | End rules (parked at school / leaving the zone), school per run, run page preview, "Stop changed" line |
| 0.10 | 2026-11-05 | Settings page (sound, vibration, Quiet mode override), BusPlanner notices (newest only), bulletin × fix |
| 0.11 | 2026-11-06 | Alarm switch per run (bell, replaces Monitor now / Stop / Re-arm), notice hints (delay extends the period, red "delayed N minutes"), countdown ring, "No vehicle" + message on ↻, each run's own stop, Use default sound (committed locally, not yet tested on the phone) |
| 0.12 | 2026-11-06 | Main zone drawn on the phone: run page "Main zone" section (Draw new, Edit, Choose, Remove), shared zones (all runs / this run only), zones kept across logouts; the hardcoded zone is gone |
| 0.13 | 2026-11-06 | Portal address on the login screen (§1); zone screen by taps (corners in order, Undo, Done closes the shape), unique zone names; dropped sessions handled (§2.2 session hints: answers with no update, cookies, fresh login at period start and after 5 idle minutes, "Waiting for network"), old positions in red with their age |
| Public | 2026-11-09 | On GitHub as `anderewrey/bus-tracker` (GPL-3.0-or-later, SPDX header in every source file), one fresh first commit: the family's details scrubbed (examples on This / That / The Other Street, Porters Lake NS), portal address on the login screen, README and LICENSE, package `io.github.anderewrey.bustracker`; the earlier history stays private |

0.12 and 0.13 were committed together on 2026-11-06. 0.13 (with 0.11 and 0.12 in it) passed its first real run on the phone on 2026-11-09: automatic start, live checks, the main-zone alarm, and recovery from a short network loss.

## Next, in order

1. **Times in 12-hour style** (user, 2026-11-06 and 2026-11-09): BusPlanner's times are 12-hour ("2:51:12 PM"), so the app uses the same style, hardcoded for now, easier to read in the afternoon: the "checked" time on the run card and the map (`h:mm:ss a` in place of `H:mm:ss`, in MainActivity.kt and MapScreen.kt), and the planned time in the run titles ("Dropoff 2:39 PM", not "Dropoff 14:39").
2. **Zones, the rest:** heads-up zones (§6.1), the Zones page (list, delete unused zones)
3. **Run configuration page:** the working parts (§5.1)
4. **Overview page and School page** (§5.3)
5. **"Ringing" option per alert type** (§7.1)
6. **Updates through [Obtainium](https://github.com/ImranR98/Obtainium)** (user, 2026-11-09): CI publishes a signed APK on the repo's GitHub Releases, and Obtainium on the phone watches them and installs each new build. Needs a fixed signing key in CI (the versionCode, the build time, already always goes up, DESIGN.md §8, Versions).
7. **Configuration import for test builds** (user, 2026-11-09): "Bus Tracker dev" and "Bus Tracker PR N" start empty. An "Import from Bus Tracker" action, shown only in those builds, copies the official app's sign-in, zones, runs and settings in one tap. The official app exports them only to apps signed with the same key (a signature-level permission). A one-way copy, not shared data: a test build can't damage the official setup. The official app needs the export first, so it ships in an official build before the import can be used.

## Waiting on a real event

- **Cancelled-bus notice:** format not seen yet; decide what it does then (§7.3, §9)
- **Alert ranges:** test the `RangeType` values the next time a notice is posted (§9)
- **Quiet mode override:** check on the phone that it rings through Do Not Disturb (§9)
