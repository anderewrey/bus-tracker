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
| — | 2026-11-14 | One notification per run (the main-zone alarm takes the tracking notification's place), clearing it no longer turns the Alarm switch off, the daily rescan no longer cancelled by the switch; times on screen in 12-hour style |
| — | 2026-11-17 | Run page: monitoring period minutes (before and after), end of monitoring (school, leaving or entering a zone, period end); overview page (children, schools, runs, zones; delete unused zones); School page (move the point, circle 25–300 m, where the bus parked, reset, choose among several BusPlanner schools) |
| — | 2026-11-18 | Main-zone alarm: rings 1 minute at most, goes away when the bus leaves the zone, never again at the end of watching |
| — | 2026-11-18 | Zones drawn and changed from the overview page; "Import from Bus Tracker" in test builds (§8, Configuration import) |
| — | 2026-11-18 | Street name from 5 geocoder candidates by distance: the nearest street, and the others within 25 m of it ("This Street / That Street"), never a stop's whole address line (§7.2, Street name); the lookup never holds up a check or the alarm ("finding street…", "street unknown"); Test geocoder on the Debug screen |

0.12 and 0.13 were committed together on 2026-11-06. 0.13 (with 0.11 and 0.12 in it) passed its first real run on the phone on 2026-11-09: automatic start, live checks, the main-zone alarm, and recovery from a short network loss.

## Next, in order

1. **Heads-up zones** (§6.1)
2. **"Ringing" option per alert type** (§7.1)

## Later

- **Updates through [Obtainium](https://github.com/ImranR98/Obtainium)** (user, 2026-11-09; not a short-term goal, user 2026-11-18): CI publishes a signed APK on the repo's GitHub Releases, and Obtainium on the phone watches them and installs each new build. The CI part, the app's key and its GitHub secrets are done (DESIGN.md §8, GitHub Actions; 2026-11-09); left: Obtainium's settings on the phone.

## Waiting on a real event

- **Cancelled-bus notice:** format not seen yet; decide what it does then (§7.3, §9)
- **Alert ranges:** test the `RangeType` values the next time a notice is posted (§9)
- **Quiet mode override:** check on the phone that it rings through Do Not Disturb (§9)
