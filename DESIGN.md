# Bus tracker for Android: design

A native Android app (Kotlin) that replaces a BusPlanner portal's "Where's My Bus" page.
It runs entirely on the phone and needs no server.
It logs in to BusPlanner, polls the bus position around each planned stop time, shows the bus on a map,
and sends a dedicated notification when the bus enters zones drawn by the user.

The protocol has been proven with the Python prototype `busprobe.py`.

---

## 1. Scope

**In scope**
- BusPlanner login, with automatic re-login when the server drops the session.
- Polling the bus position during monitoring periods.
- A map showing the bus, the stop and the zones. The automatic fit (and Fit view) keeps the bus, the stop and every zone of the run whole in view.
- Heads-up zones and a main zone per run, drawn with a finger.
- Dedicated, configurable notifications.
- Transportation alerts (late or cancelled bus) from the BusPlanner alerts API.

**Out of scope**
- School information, school position, bell times.
- Route auto-recording. This may come later.
- GeoJSON import.
- Any server, cloud relay or third-party push service.
- Inferring speed, ETA or "stopped for X min".
- A "passed through between two fixes" alert.

**Account**
- The app takes one BusPlanner login: portal address, email and password (0.13).
  - **Portal address:** any BusPlanner portal, any domain (e.g. `example.mybusplanner.ca`, `busplanner.com`). Any `https://` address; a bare host gets `https://`; a path pasted from the browser is dropped (only the origin is kept); plain `http://` is refused, so the password never travels unencrypted.
  - Before logging in, the app checks that the address serves a BusPlanner login form ("No BusPlanner login page at this address" otherwise).
  - Kept with the login and cleared on logout. Settings shows it under the email.
  - A login saved before 0.13 has no portal address: the app opens on the login screen once, with the email filled in.
- Every student on that account is available.
- The app has no other notion of users.

---

## 2. BusPlanner protocol

Base URL: the portal address given at login (§1); every path below is on it.
The site is ASP.NET WebForms, so every POST must send back the page's hidden fields:
`__VIEWSTATE`, `__VIEWSTATEGENERATOR`, `__EVENTVALIDATION` and the `hf*` fields.

**User-Agent:** send a normal browser User-Agent.

**Cookies:** keep a cookie jar and store every `Set-Cookie`, including those on 3xx and 4xx responses.

**Redirects:** never follow them automatically. A 3xx response to `/Login` means the session has expired.

### 2.1 Login

1. Send `GET /Login?ReturnUrl=%2fSubscriptions%2fWheresMyBus&LoginType=Subscriber` and collect the hidden inputs.
2. POST the same URL with the hidden inputs plus these fields:

| Field | Value |
|---|---|
| `ctl00$MainContent$glogin$lLogin$UserName` | email |
| `ctl00$MainContent$glogin$lLogin$Password` | password |
| `__EVENTTARGET` | `ctl00$MainContent$glogin$lLogin$btnLogin` |

3. **Success** is a 302 response that does not point to `/Login`, together with the `.ASPXAUTH` and `SubscriberAUTH` cookies.
   Anything else is a login failure. Show it to the user, and don't retry in a loop.

The login form has no CAPTCHA. The reCAPTCHA exists only on account creation, which the app never touches.

### 2.2 Bus position (Where's My Bus)

**Page:** `GET /Subscriptions/WheresMyBus?showParentPopup=True`. This page carries:
- the hidden fields
- the student dropdown `ctl00$ctl00$MainContent$ddlStudents`, whose options are student GUID → name

If the page has no student list, the session has expired.

**Select a student:** send a full postback with:
- `__EVENTTARGET=ctl00$ctl00$MainContent$ddlStudents`
- the dropdown set to the student's GUID

Only send it when the student differs from the one currently selected.

**Refresh** (the portal's timer tick): send an async postback to the same URL.

Form fields:

| Field | Value |
|---|---|
| `ctl00$ctl00$scriptManager` | `ctl00$ctl00$MainContent$NestContent$UpdatePanel_Map\|ctl00$ctl00$MainContent$NestContent$Timer1` |
| `__EVENTTARGET` | `ctl00$ctl00$MainContent$NestContent$Timer1` |
| `__ASYNCPOST` | `true` |
| `ctl00$ctl00$MainContent$ddlStudents` | selected GUID |
| hidden fields | from the last response |

Headers:
- `X-MicrosoftAjax: Delta=true`
- `X-Requested-With: XMLHttpRequest`

**Response:** the MS Ajax delta format, a repeated sequence of `length|type|id|content|`.

**Do not use the timer: use the Refresh button (since 0.8).** The page creates `Timer1` with `"enabled":false`, so the portal never refreshes by itself. A Timer1 postback only repeats the data the page was loaded with: on 2026-11-04, 37 of 39 app checks came back identical, and the other two brought no new bus position. The portal's **Refresh** button (`ctl00$ctl00$MainContent$NestContent$MapRefresh`, a submit button inside `UpdatePanel_Map`) makes the server fetch a new position. Tested in a signed-in browser the same day, every Refresh returned a newer "Updated Time", about 30 s behind the clock, while timer postbacks in between stayed unchanged.

Each check is one async postback, about 23 KB:

| Field | Value |
|---|---|
| `ctl00$ctl00$scriptManager` | `ctl00$ctl00$MainContent$NestContent$UpdatePanel_Map\|ctl00$ctl00$MainContent$NestContent$MapRefresh` |
| `__EVENTTARGET` | empty |
| `ctl00$ctl00$MainContent$NestContent$MapRefresh` | `Refresh` |
| `__ASYNCPOST` | `true` |
| other fields | the whole form, as the browser posts it, with hidden fields updated from the last response |

The page (GET, about 64 KB) is loaded only at the first check, after a login, after a rescan, after a failed or empty check, and after a second network failure in a row (a single one only retries the Refresh). The page calls no separate data service: the bus position only comes in `hfBusLocation`, inside these responses.
Parse it by `length`, because the content can contain `|`.
Handle these entry types:

| Type | Handling |
|---|---|
| `hiddenField` | Update the stored hidden fields. |
| `updatePanel` | Parse the HTML for the two time labels and the bulletin. |
| `pageRedirect` | The session has expired: log in again. |
| `error` | An error. Log it, and retry on the next tick. |
| *(none of the above)* | The session has expired too: log in again and load the page (0.13). |

**Dropped session without a redirect** (seen 2026-11-05 and 2026-11-06): once the server drops the session, the Refresh postback can keep answering HTTP 200 with about 2 KB that is not an update at all, while a plain GET of a page gets the 302 to `/Login`. The app used to keep the position of the page it had loaded (the bus parked at school) for the whole period, until a rescan's GET found the 302 and logged in again. Now an answer with no `updatePanel`, `hiddenField`, `pageRedirect` or `error` counts as a dropped session; its title and first words are written to the Log, to learn what it is.

**Session hints** (0.13, user 2026-11-06: make the checks reliable). The portal is a browser page with a server session, not an API, so the app never trusts a session it hasn't seen working recently:

| Hint | Kind | What the app does |
|---|---|---|
| 302 to `/Login`, `pageRedirect`, an answer that is no update, a page without the student list | Certain | Log in again at once, load the page, retry the check |
| A new `ASP.NET_SessionId` value, or `.ASPXAUTH` cleared or expired, in any answer's cookies | Certain | Same ("session lost: …" in the Log) |
| Start of a monitoring period (no run watched before it) | Certain enough | Log in again before the first check |
| No request for 5 minutes (between periods, or Android pausing the app) | Certain enough | Log in again before the next request |
| A bus with no "updated" time, or the same "updated" time twice while more than 90 s behind the clock, 2 checks in a row | Suspicious | Log in again and load the page, at most every 5 minutes |
| No network with internet access (Android) | Phone | Status "Waiting for network", no request, no login; the check runs again 30 s later |
| A request that never reached the portal (address lookup failed, timeout, connection refused) while Android says online (seen 2026-11-09, twice, about 30 s each) | Phone | Status "Network problem, retrying"; the next check is the plain Refresh again, and only a second network failure in a row loads the page again |

**Data age on screen:** a position whose "updated" time is more than 2 minutes behind the phone's clock gets "updated N min ago" in its status line, and one with no "updated" time gets "not live"; the line is red on the run card and the map (errors, "Waiting for network" and "Network problem, retrying" too). The tracking notification shows the same words, without color. "GPS h:mm" (the bus's own fix) stays as it is: an old one is normal for a parked bus.

**Fields used**

| Field | Content |
|---|---|
| `…$hfBusLocation` | `[{"Latitude":..,"Longitude":..,"Label":""}]`. This is empty when no vehicle is assigned. |
| `…$hfBusStopLocations` | `[{"Latitude":..,"Longitude":..,"Label":"B101 SMP_PU"}]`. The label is the **run label**. |
| Labels in `UpdatePanel_Map` | "Updated Time" and "Latest Reported Time". The reported time is the GPS fix time, and it only changes when the bus moves. The updated time is simply the server's clock when it answered the Refresh (user, 2026-11-06: refreshing the page quickly makes it step by 1 s, so it is no fetch time; checked the same day with 6 page loads 1–2 s apart: it followed the PC's clock second for second, always 40–41 s behind, while the reported time stayed at the bus's last fix); it runs about 40 s behind the phone's network-set clock, so the server's clock is slow, which is why a fresh GPS time often shows *later* than the updated time. |
| `lblBulletin` (in `UpdatePanel_Bulletin`) | When no vehicle is assigned: "Information about the vehicle associated to your child's transportation is unavailable at this time. Please try again later." Show it **verbatim**. |

**Cost and cadence**
- Each refresh is about 13 KB up and 24 KB down.
- GPS fixes arrive every 19–40 s, and the bus moves 200–450 m between fixes.

### 2.3 Schedule (ChildTransportInfo)

`GET /Subscriptions/ChildTransportInfo` returns the information for the currently selected student. It is about 106 KB, so fetch it once a day, not on every poll.

- The repeater labels hold one row per stop:
  - `lblStopType`: AM Pickup, AM Dropoff, PM Pickup or PM Dropoff
  - `lblArrivalValue`: the scheduled time
  - `lblDetailLocationValue`: the stop address
  - `RunRouteInfo`: route and run
  - `lblOperatorValue` and `lblEffectiveDateValue`
- `hfTransportationPosition` holds the stop coordinates with their scheduled times.
- `hfAddressPosition` is the **home** address, not the stop. Don't use it.

### 2.4 Transportation alerts

The endpoints are:
- `POST /Alerts.aspx/GetBusNotifications`, used by the app
- `GetGeneralNotices` and `GetSchoolNotifications`, kept as optional extras

Each is called with `Content-Type: application/json`.

- **Body:** `{alertCondition: {RangeType: ""}, dataTableData: {draw: 1, start: 0, length: 100, columns: [], order: [], search: {value: "", regex: false}, SortFieldName: "RouteRun"}}`. The page sends `""` for Today (it returned today's rows); `"1"` means Future.
- **No login needed:** a call without cookies returns the same rows (checked 2026-11-05).
- **Response:** `{d: {recordsTotal, data: [{AppliesTo, RouteRun, Zone, Operator, Action, AffectsSchools, TransfersSchools, Comment, CreateTime, CreateTimeDisplay}]}}`.
- **Real alerts seen on 2026-11-05:** two for one bus, a second one replacing the first as the delay grew.
  - `RouteRun`: `B102 (B102 XYZ_ABC_PU)`, the bus number, then the run's own name in brackets.
  - `Action`: `Delayed 15 minutes from original time`, then `Delayed 30 Minutes from original time` (with trailing spaces).
  - `Comment`: the operator's text, in English then French, sometimes with a line break.
  - `CreateTime`: `/Date(1793878581250)/`; `CreateTimeDisplay`: `2026-11-05 7:36 AM`.
  - `AffectsSchools`: `School A ,School B`.
- **Cost:** about 2 KB up, about 100 B down when empty, about 50 ms.
- **Use:** called once per round of checks while a run is monitored (§7.1). What the app does with a notice's text, such as extending the period on a delay, is in §7.3.
  - **Matching:** the first word of `RouteRun` equals the run's bus number, whole word and ignoring case, so `B10` never matches `B102`.
  - The bracketed run name (`…_PU`) isn't mapped to AM or PM; the notification shows the whole `RouteRun` and the time the notice was posted.
  - **Only the newest notice counts** (user, 2026-11-05): a newer notice replaces the older ones for the same bus, e.g. "Delayed 30 minutes" posted 8:02 replaced "Delayed 15 minutes" posted 7:36. The newest is the one with the latest `CreateTime`; the page lists notices by run, not by time, so its order means nothing. Older notices are logged, never rung.
  - One notification per run: a newer notice takes the older one's place in the notification shade instead of adding a second one.
  - A notice rings once: an id (`CreateTime` + `RouteRun`) is kept in the app's storage, so a restart doesn't ring it again. A newer notice for the same bus has a new `CreateTime` and rings.

---

## 3. Account setup and data model

### 3.1 First login

After the first successful login, the app scans the BusPlanner account:
1. It lists the students from the dropdown.
2. For each student, it selects them and reads ChildTransportInfo (§2.3).
3. It groups the results into bus runs. Students who share a run share it in the app.
4. It shows the user the list of bus runs with their stop and planned stop times.
   Next to each planned time, a status line shows the run's monitoring period, e.g. `Pickup 7:33 · monitoring 7:03–8:03`.

### 3.2 Rescans and schedule changes

The scan runs again in two cases:
- **Manually:** the user taps "Rescan account".
- **Automatically:** once a day, along with the first monitoring period, when it starts.

Each rescan compares the new results with the stored ones. These changes are detected:
- a student added or removed
- a student moved to another bus run
- a new or removed run
- a changed stop address or stop coordinates
- a changed planned stop time

**When something changed:**
- The app sends a Schedule change notification listing each change as past value → new value.
- Time changes are applied at once, so the monitoring period recentres on the new planned time.
- The Schedule change notification only says that changes were found. Tapping it opens the **Changes screen**.

**Changes screen**

This is a dedicated screen, not a pop-up, so the user sees exactly what the rescan found before deciding anything.
- It shows every changed stop as a whole, on one line for the past stop and one line for the new stop, without picking out which field changed. Each line holds the run, the stop type, the planned time and the address.
  For example:
  ```
  past  B101 SMP_PU · AM Pickup · 7:33 · 12 This St
  new   B101 SMP_PU · AM Pickup · 7:40 · 8 The Other St
  ```
- An added or removed stop, run or student shows only its new or its past line.
- For each moved stop, or new run that replaces a previous one, the user chooses:
  - **Keep the same configuration:** the zones and monitoring settings carry over.
  - **Create a new one:** the run starts without zones, and the user draws them.
- Changes that are already applied (planned times) are marked as applied, and need no choice.
- The screen stays reachable from the run list until every choice is made.
- Until the user decides, monitoring continues with the current zones.
- A new run with no previous run starts without zones.

**As built in 0.6**
- Until zones can be drawn, a run's configuration is the stop its zones are taken from (today: whether it lies in the hardcoded Hillside zone). "Keep the same configuration" keeps the past stop's zones; "New configuration" takes those of the new stop, possibly none.
- A rescan saves the new runs at once. A moved stop or a replacing run keeps the past stop's zones until the user answers, so a rescan never changes the alarm by itself.
- A stop counts as moved when its address differs, or its position moved more than 30 m.
- A run whose key (bus + AM/PM) disappears is paired with a new run of the same half-day carrying one of its children: that is a replacing bus.
- A change still waiting when the next rescan changes the same run again becomes one change, from the oldest past to the latest new. One that went back to its past values disappears.
- The daily rescan runs when monitoring starts inside a monitoring period, once per day; automatic starts aren't built yet. It notifies on the Bus alerts channel and the notification opens the Changes screen. A manual Rescan that finds changes opens the screen directly.
- A monitored run whose key disappears after a rescan stops monitoring, with the reason logged.
- The first scan after logging in has nothing to compare with.

### 3.3 Data model

- **Account:** the email, and the password in encrypted storage (Android Keystore).
- **Student:** GUID and display name, both from the dropdown.
- **Bus:** the bus number (e.g. `B101`), taken from the start of the run label.
- **Run:** one trip of a bus, identified by its run label (e.g. `B101 SMP_PU` or `B101 SMP DO`). A run holds:
  - its students
  - its stop (coordinates, address and scheduled time)
  - its monitoring period and days
  - its alerts, in order (zone, event, action), and its end-of-monitoring choice (§5.1)
- **Zone:** a name typed by the user and a polygon of a few corner points. A zone can be used by several runs, or by one run only. Its role (heads-up or main) and order are not part of the zone: they come from the alert list of each run that uses it.

The AM and PM runs of the same bus are separate runs, each with its own alerts, but they can share zones. For B101, the Hillside part is the same loop in the AM and the PM; what differs is Main Street, which the bus goes through before Hillside in the PM and after it in the AM.

---

## 4. Runs and polling

- **One poll per run.** Students on the same run share one refresh.
- **The page is per child, not per run** (found 2026-11-06): the AM and PM runs of a child read the same Where's My Bus page, so the same bus position and the same stop. A run's map and distance always use the run's own stop from the scan; the page's stop only when the run has none. A page stop more than 50 m from the run's own is logged once.
- **Run membership** (which run each student is on) is checked once per half-day (AM/PM).
  A student only changes bus when changing school.
- **Interval:** 30 s by default, with a minimum of 20 s.
- **On an expired session:** log in again silently, then carry on.
- **Map refresh = countdown ring** (top right of the map). While the run is monitored, a ring empties over the 30 s until the next automatic check, with the seconds left in the middle; it spins during a check. A tap checks now, through the monitoring loop, and the 30 s start again from that tap (no second check a few seconds later). A tap during a check is ignored. Not monitored: a plain ↻; a tap checks once. Left of it, the run's Alarm switch with a bell in its thumb and no label (user, 2026-11-06: the "Alarm" label above it looked odd).

---

## 5. Monitoring periods

A monitoring period is the time span during which the app polls BusPlanner for one run.

- Each run has its own period, configurable per bus and per time of day.
- **Default:** centred on the planned stop time read from ChildTransportInfo, from 30 min before to 30 min after.
  For example, a 7:33 pickup is monitored 7:03–8:03.
- The period is shown as a status next to the planned stop time in the run list (§3.1).
- Monitoring starts with an exact alarm, which starts the foreground service.
- **Days:** a configuration page selects the weekdays on which the app monitors. The default is "All work days" (Monday to Friday).
  The app has no school-calendar information.
- Zone setup is the user's responsibility. If a period is configured to start after the bus has passed the heads-up zones, the main zone never arms.

**The Alarm switch** (decided 2026-11-06, not built; replaces Monitor now, Stop, Stop monitoring and Re-arm alarm). The user decides one thing per run: "ring me for this run, yes or no". Monitoring is how the app does it, not a separate choice. One **Alarm** switch per run, on the run card and in the map header, always about the run's current or next period:

| When | On | Off |
|---|---|---|
| Before the period | Starts by itself at the period start (on by default on the run's days) | Skips this period only; the next one is on again |
| During the period | Watching; rings when the bus enters the main zone | Stops watching |
| After it rang | Keeps watching (the map stays live) until an end rule below | Off then on again = ring again (the old Re-arm) |

- No confirmation and no Undo bar: the switch shows its own state, and pressing it again undoes it.
- The switch has a bell in its thumb and no label, on the card and on the map (user, 2026-11-06). On the card it sits on the Open map row, at the right. A short status shows left of it only while watching ("Watching until 8:03", "Rang 7:31 · watching until 8:03") or when the run has no days ("No days set"). The period's times aren't on the card: they're on the run's page (§5.1).
- **Silence** (on the ringing alarm only) stops the sound; the app keeps watching. It never changes the switch.
- The tracking notification's button turns the switch off.
- "Monitor now" for an hour outside a period is dropped. The map's ↻ ring checks the bus at any time; a late bus is covered by the notice hints (§7.3).
- While the global Automatic start switch is off (holidays), every run's Alarm switch is off; turning one on applies to that run's next period only.
- *As written 2026-11-06 (not built):* the choices are kept per period (skipped, forced on, ended by itself), as the period's start; older than two days they're dropped. The switch's period moves on every 30 s on screen. Opening the app during a period whose switch is on but which isn't watching (phone off at the start) starts it.

**Watching ends by itself when the first of these happens** (the switch then shows the next period):
1. The monitoring period ends, extended by a BusPlanner delay notice (§7.3).
2. The children are off the bus (user decision 2026-11-05):
   - **Pickup run** (AM: they get on at the home stop): the bus is parked at the school. That means three readings in a row at the same spot (within 15 m, GPS jitter), less than 100 m from the school's position, after the bus was seen away from the school during this monitoring. 100 m rather than 50 m: on 2026-11-05 the parked bus was about 83 m from BusPlanner's school point. Leaving the main zone doesn't stop a pickup run.
   - **Dropoff run** (PM: they get off at the home stop): the bus leaves the main zone after having entered it. The school rule doesn't apply.
   - The run's stop type ("AM Pickup", "PM Dropoff" from ChildTransportInfo) tells which rule applies.

Nothing on a zone alert stops monitoring: **Silence**, opening it or swiping it away only stop the sound.

**The main-zone alarm rings once per monitoring period** (once a day per run, remembered across app restarts). Turning the Alarm switch off and on again lets it ring again: at the next check if the bus is still in the zone, otherwise when it next enters.

Once watching has ended, the run waits for its next monitoring period. Nothing restarts it by itself, not even entering a heads-up zone again.

*As built up to 0.10:* Monitor now (1 hour outside a period), Stop on the card, Stop monitoring on the tracking notification, and Re-arm alarm on the card and the notification. These go away with the Alarm switch.

**As built in 0.7**
- One exact alarm is set, for the next period start of any run on one of its days. It's set again after it fires, after each rescan, change answer or days change, on app open, after a reboot, after an app update, and after a clock or time-zone change.
- Permissions: `USE_EXACT_ALARM` (granted at install, no prompt), plus `SCHEDULE_EXACT_ALARM` up to API 32. An exact alarm is what lets Android start the monitoring service in the background. If exact alarms are revoked, the alarm is inexact, and a refused start posts "monitoring didn't start" on the Bus alerts channel.
- When the alarm fires, a run starts only if today is one of its days, it's inside its period, and it isn't already monitored. Otherwise the skip is logged.
- Days are per run, part of its configuration. They carry over on rescans and reset to Monday to Friday on "New configuration". They're set on the Automatic start page, opened from the "Automatic start: Mon–Fri ›" line of the run card.
- **Global switch** (run list, above the cards): "Automatic start" with an on/off switch, for holidays. Turning it off asks:
  - **Off:** off until it's turned back on.
  - **Off until…:** opens a date picker (tomorrow at the earliest, opens on next Monday). The runs start by themselves again on that day, and the switch comes back on by itself.
  - **Cancel**

  Per-run days are kept untouched while the switch is off. A run's Alarm switch can still be turned on for its next period.
- The line under the switch shows the days of all runs ("Mon–Fri", or "Custom per run" when they differ). It opens the **All runs** days page, where a day some runs have shows as mixed, and tapping it gives that day to every run.
- While the switch is off, each card reads "Automatic start: off · 14:09–15:09 ›".
- Known gap: when the phone is off or rebooting at the period start, that period doesn't start by itself. Since the Alarm switch, opening the app during the period starts it.
- Samsung: Battery must be "Unrestricted" for Bus Tracker, or One UI may put it to deep sleep and its alarms never fire.

### 5.1 Run configuration page (planned, not built)

Everything about one run, on one page. **Tapping the run card opens it.** The card keeps what is needed quickly: Open map and the Alarm switch (§5). The global Automatic start switch stays on the run list.

**Title:** the whole stop, e.g. "B101 · Pickup 7:33 · This St & That St".

**Sections, in this order** (user, 2026-11-05: About comes first; the run cards no longer show the Automatic start line):

0. **About this run** (read-only), described under 7 below.
1. **Monitoring period:** minutes before and after the planned stop time (default 30 and 30), with the result shown, e.g. "7:03–8:03".
2. **Automatic start:** the weekdays (default Monday to Friday). This replaces the separate Automatic start page of 0.7.
3. **Alerts:** an ordered list. Each line is:
   - a **zone**, picked from the existing zones or drawn new (§6.2)
   - an **event:** entering or leaving
   - an **action:** **Alarm** (rings until acknowledged) or **Notification** (sounds once)

   Order matters: a line only counts once the line before it has fired (the heads-up chain of §6.1). Each line fires at most once per monitoring period; turning the Alarm switch off and on lets the alarm lines fire again. A **Test** button plays the alarm as it will ring.
4. **End of monitoring:** one choice.
   - **Bus parked at the school** (default for a pickup run), as defined in §5, stop condition 4.
   - **Bus leaves a zone** (default for a dropoff run, with the run's main zone).
   - **Bus enters a zone.**
   - **Only at the end of the period.**

   The Alarm switch and the period end always apply, whatever is chosen.
7. **About this run** (read-only): bus, stop type, planned time, address, students on the run, school.
   - **School:** a route serves exactly one school (user, 2026-11-05). The run's school is the name of the same child's school stop of the same half-day on the same route (AM Dropoff for an AM run, PM Pickup for a PM run), saved with the run at each scan. "Bus parked at the school" uses only that school's position. A school that differs between two scans is a change ("school changed", already applied) on the Changes screen.
   - **Stop change waiting:** only while a stop change or replaced bus waits for an answer on the Changes screen, a red line "Stop changed: choose a configuration ›" opens it. Once answered, keep or new, nothing shows.

**Example (B101):**

| Run | Alerts | End of monitoring |
|---|---|---|
| PM Dropoff 2:39 | 1. Main Street, entering → Notification<br>2. Hillside, entering → Alarm | Bus leaves Hillside |
| AM Pickup 7:33 | 1. Hillside, entering → Alarm | Bus parked at the school |

Main Street isn't on the AM run: the bus goes through it after the stop.

**Shared zones:**
- The **Zones** page, opened from the Alerts section, lists all zones and which runs use each.
- Changing a zone used by other runs asks: **Change for all runs using it**, **Copy for this run only**, or Cancel. This covers both the shared setup and the one-run setup.
- Removing a zone from a run's alerts doesn't delete it. A zone used by no run can be deleted on the Zones page.

**Defaults when a run is first found:** no alerts unless its stop lies inside an existing zone, which then becomes an "entering → Alarm" line; the end of monitoring follows the stop type. The hardcoded zone was not carried over: since 0.12 the user draws it on the phone (§6.2).

**Not on the page:** the 30 s check interval (fixed) and per-child settings (the children on one run share the bus). Sound and bus alerts are global, not per run (user, 2026-11-05): they live on the **Settings** page.

### 5.2 Settings page (global)

Opened from **Settings** in the run list's top bar, which replaces Log out there.
- **Main zone alarm:** Sound (Change opens the sound picker; "Use default" resets it), Vibration, **Test**, and **Android settings** for the channel (§7.1).
- **Bus alerts:** the "no vehicle" warning at monitoring start and BusPlanner's late or cancelled notices, each on or off (both on by default), then the notices' own Sound, Vibration, Test and Android settings.
- **Quiet mode:** the Quiet mode override switch (§7.1).
- These settings are kept apart from the login: logging out keeps them.
- **Account:** the BusPlanner email and **Log out**.
- **Children, schools, runs and zones:** opens the overview page (§5.3).

### 5.3 Overview page: children, schools, runs, zones (next improvement, not built)

Some things belong to the account, not to one run: a school's position is used by every run going there, and a zone can be used by several runs. One page lists everything the app knows, and each line opens its own page to see or change it (user, 2026-11-05).

| List | Each line shows | Opens |
|---|---|---|
| **Children** | name, school, runs | read-only: everything comes from BusPlanner |
| **Schools** | name, position source ("BusPlanner" or "corrected"), runs going there | the School page (below) |
| **Runs** | bus, stop, time | the run page (§5.1) |
| **Zones** | name, runs using it | the zone page (draw, edit, delete; §6.2) |

Anything waiting for an answer (a stop change, a school to choose) shows in red at the top, linking to the Changes screen.

**School page**
- A map with the school's point and the "at school" circle (100 m) around it.
- **Move:** drag the pin to where the bus really parks, in case BusPlanner's point is wrong or far from the bus loop (on 2026-11-05 the parked bus was 83 m from it).
- **Resize:** make the circle bigger or smaller (e.g. a slider from 25 m to 300 m, default 100 m), with the radius shown in metres while it changes. The size is part of the school's correction.
- **Use where the bus parked:** puts the pin where the "parked at the school" rule last saw the bus stop.
- **Reset to BusPlanner's position** (and the default 100 m size).
- The correction belongs to the school, so every run going there uses it. Rescans never overwrite it. If BusPlanner's own point changes later, the Changes screen says so and the corrected position is kept.

**Finding the school on BusPlanner** (the name on the child's school stop, looked up in the SchoolInfo dropdown, §3.1):
- **One exact match** (ignoring case and accents): used directly.
- **Several possible matches** (e.g. one name contained in another): no guess. The Changes screen shows "School: choose the right one", with each candidate's name, a small map preview of its point, and its distance from the run's stop. The choice is remembered.
- **No match:** the school shows as "position unknown", and the user can place it on the School page.
- Until the school's position is settled, "Bus parked at the school" is off for the runs going there: only the period's end and the Alarm switch end them, so a wrong school never ends monitoring early.

**Follow-up, once zone drawing exists:** the school area can be a drawn zone (e.g. the exact bus loop) instead of the circle around a point.

---

## 6. Zones

### 6.1 Logic (per run, per monitoring period)

- The zones are optional heads-up zones H1 … Hn in order, followed by one main zone M.
- Each stage is a state:

```
WAITING_H1 → … → WAITING_Hn → WAITING_M → IN_M → DONE
```

- A stage only counts once the previous stage has fired. A heads-up zone, or the main zone, entered out of order is ignored.
- Each zone fires at most once per run per monitoring period.
- `IN_M → DONE` happens when a GPS fix lands outside the main zone. Whether monitoring then stops depends on the run's End of monitoring choice (§5.1).
- With no heads-up zones, the run starts in `WAITING_M`.
- "Inside" uses ray-casting point-in-polygon on lat/lon, as in the prototype.
- Each fix is tested independently. Because fixes are 200–450 m apart, a zone should be at least about 500 m long along the route, or be chained behind heads-up zones.

### 6.2 Drawing

1. The map moves as usual the whole time (drag, pinch, double-tap; mouse drag and wheel on the emulator).
2. Each tap on the map places the next corner; corners are joined by straight lines, in the order they were tapped. **Undo** removes the last one.
3. **Done** (3 corners or more) is the user's confirmation that the outline is complete: only then is the shape closed (last corner back to the first) and filled.
4. The corners are dots that the user can drag to adjust the shape, before and after Done.
5. The user types the zone's name and picks its role: heads-up or main, plus its order.

*Until 2026-11-06 (0.12, and 0.13 builds of that day):* a finger trace, simplified to a few corners (Douglas–Peucker, 14 dp), first with the map locked, then with a Move / Draw toggle. Replaced by taps (user: pointing where the next dot goes is easier than dragging; no toggle needed, a tap and a drag are different gestures).

**Built in 0.12 (2026-11-06): the main zone only** (user: "start with the main zone, which is what I'm using").
- **Run page, "Main zone" section:** the zone's name and "Also used by …", or "None: no alarm for this run". Buttons: **Draw new**, **Edit**, **Choose** (a zone drawn for another run), **Remove** (the run has no main zone; the zone stays), **Test alarm**. Heads-up zones: not built yet.
- **Zone screen:** full-screen map on the run's stop, drawn by taps as above (0.13). While open: **Undo**, **Done**. Once closed (and for an existing zone): **Redraw** (clears the corners, back to taps). Name field, **Save** (closed shape only), **Cancel**. Zone names are unique, ignoring case (user, 2026-11-06): a name another zone has shows "Another zone has this name" and Save waits; "This run only" on a shared zone needs a new name too, as it makes a second zone.
- **Shared zones:** saving a changed zone that other runs use asks **Change for all runs** or **This run only** (a copy for this run), or Cancel. A new zone also becomes the main zone of every other run without one whose stop is inside it (the AM and PM runs of one stop): the run page says so.
- **Storage:** zones are kept on the phone apart from the login (logging out keeps them); a run points to its main zone by id (`Run.mainZone`). A rescan keeps each run's main zone, also when its stop moved, until "New configuration" on the Changes screen, which gives the zone containing the new stop, if any. A run seen for the first time gets the zone containing its stop, if any. The zone's corners are written to the Log when saved.
- The hardcoded zone of 0.4–0.11 is gone: after installing 0.12, each run has no main zone until one is drawn or chosen.

---

## 7. Notifications

### 7.1 Notification types

These are Android NotificationChannels, created on the first app launch. Android runs no app code at install.

| Type | Purpose | Default behaviour |
|---|---|---|
| Heads-up zone | The bus entered a heads-up zone. | Rings until acknowledged |
| Main zone | The bus entered the main zone. | Rings until acknowledged |
| BusPlanner notice | A late or cancelled bus, from the alerts API (§2.4). Title `B102: Delayed 15 minutes from original time`, the comment in full, then `RouteRun · posted 7:36 AM`. | Rings until acknowledged |
| Bus alert | A rescan found changes (§3.2), or BusPlanner has no vehicle at monitoring start. | Sounds once |
| Tracking | A live status line. | Silent and ongoing |

**Ringing types** work like a ringtone or a clock alarm, not a one-shot notification:
- The sound (and vibration, if on) loops until the user opens the notification, taps Silence, or swipes it away.
- This uses Android's insistent flag.

**Settings in the app**, per ringing type (main zone, BusPlanner notice), written in 0.10:
- **Sound:** Android's own sound picker (notification sounds, ringtones and alarm sounds). The default follows the phone's default notification sound. **Use default** (shown only when another sound is set) goes back to it. Known limits of the system picker, accepted by the user (2026-11-06): Samsung's picker doesn't scroll to the current sound, and Back keeps the selection (no Cancel). An in-app picker was declined.
- **Vibration:** on or off.
- **Versioned channels:** Android fixes a channel's sound once it exists, so each change makes a new channel (`main_zone_v1`, `main_zone_v2`, …) and deletes the old one. The version is kept apart from the login, so it survives a logout. The new channel starts from the old one's sound, vibration and importance, so changes made in Android's settings are kept.
- **Ringing:** rings until acknowledged, or sounds once. Not built.
- **Quiet mode override** (one switch for both ringing types, off by default): the channels' sound plays as an alarm (`USAGE_ALARM`) and the notification is marked as an alarm (`CATEGORY_ALARM`). Do Not Disturb lets alarms through by default, so no special access is needed; the sound plays at the alarm volume, like a clock alarm. The channel's bypass flag is set too.

### 7.2 Content

There is no decoration: no coloured icon, no map snapshot, nothing beyond what the user needs.

- **Title:** `B101 entered <zone name as typed by the user>`.
- **Text:** `1.1 km · <street> · GPS 7:41:20`. The parts are:
  - the distance from the stop
  - the street name from the phone's built-in Geocoder
  - the raw GPS fix time as reported by the server, with no "X s ago"
- **Buttons:** Open map and **Silence** (stops the sound only).
- **Tracking notification:** shows the same text line plus the run label, with Open map and **Turn off** (turns the run's Alarm switch off, §5). *Up to 0.10: Re-arm alarm and Stop monitoring.*
  - It updates silently on each fix.
  - It can be dismissed (Android 14+), and dismissing it turns the run's Alarm switch off, like Turn off.
- **No vehicle** (user, 2026-11-06: the full text in the header was annoying): the status line (card, map, tracking notification) just says "No vehicle". BusPlanner's full message pops up once at the top of the map area (user, 2026-11-06), just under the header, only as the answer to a tap on ↻ (user, 2026-11-06: simpler than any "once" rule); it goes away by itself after 8 s, as a snackbar that goes away by itself (not a toast: Android 12+ cuts toasts to two lines). The one-time "no vehicle" warning notification keeps the full text. Without a bulletin and without a position: "No bus position".

### 7.3 Notice hints (decided 2026-11-06, not built)

The user wants the app to act on what BusPlanner notices say, not only ring them. The newest notice for the run's bus (§2.4) is the one that counts.

- **Delay extends the period.** `Action` "Delayed N minutes from original time" (any case, "Minutes" or "minutes") → the run's period end moves to the planned end + N minutes. A newer notice replaces the older one, so 15 then 30 gives +30, not +45. On 2026-11-05, B102's 7:03–8:03 period would have run to 8:33.
  - Applies to a run being watched when the notice is seen, if the notice was posted in the same half-day as the run (AM before noon, PM after), so a morning notice still listed at 14:00 doesn't stretch the afternoon run. Such a notice doesn't ring or show on the afternoon run either; it's logged.
  - The start isn't moved: watching earlier costs nothing.
  - The status line under the Alarm switch shows the new end, e.g. "Watching until 8:33" (the red title already says "delayed 30 minutes"). The Log records the change.
  - An `Action` the app can't read (no number, hours, other wording) changes nothing; it still rings as a notice and is logged in full.
- **Shown on the run** (user, 2026-11-06). While a delay notice for the bus is listed, the run card's title gets " · delayed 30 minutes" appended in red, e.g. "B102 · Pickup 7:33 · **delayed 30 minutes**". The map header's title does the same. A notice the app can't read appends its `Action` text in red instead. No extra line.
- **Cancelled bus:** not seen yet. When the format is known, decide with the user whether it should turn watching off for that period.

---

## 8. Android implementation

**Platform**
- **Language:** Kotlin.
- **Target:** the user's Android phone.
- **Android versions** (checked 2026-11-09):
  - **compileSdk and targetSdk: 37** (Android 17), the latest. Google Play has required 36 for new apps and updates since 2026-08-31; the app is installed from its APK, so Play's rule doesn't bind it, but keeping the latest target keeps it ready.
  - **minSdk: 29** (Android 10). The floor comes from what the app needs, not from reach: the tracking service starts with a foreground service *type* (`dataSync`), which exists from API 29 on; going lower means a second code path for phones from 2019 and before. Google's distribution of 2026-10-01 has Android 16 first (25.4 %) and Android 11 still at 10.5 %, so Android 10 and newer is the large majority of phones in use.
  - **Raise minSdk only for a feature that needs it**, and write the reason here. Lowering it isn't planned: a phone that old is a poor choice for an alarm that must ring.
  - **Build:** Java 17 bytecode (the minimum of Android Gradle Plugin 9); any JDK 17 or newer builds it.
- **Versions** (user, 2026-11-09: no version to maintain by hand; the version only says which code is installed). Everything comes from git in `app/build.gradle.kts`:
  - **versionCode:** the build time, in minutes since 1970-01-01 UTC: 29903851 is 2026-11-09 13:31 UTC. To read one: `date -u -d @$((29903851 * 60)) '+%F %R'`. Android installs an update only when this number goes up (or stays equal), so each new build installs over the last one, whatever its branch or commit; rebuilding an older commit installs too. Only an APK built earlier than the installed one is a downgrade, which Android refuses (`adb install -r -d` forces it for debug builds). The same commit built twice gets two numbers; the versionName says which code it is.
  - **versionName:** `git describe --tags --long --always --dirty --match "[0-9]*"`: the last version tag (the `build-N` tags of the GitHub releases don't count), the commits since it, the commit, and `-dirty` for uncommitted changes. `0.13-1-g5a71da1` is one commit after tag `0.13`; on the tag itself it's `0.13-0-gc987f74`. A tag is put on `main` when a step is worth naming (`git tag -a 0.14 -m "…"`, then `git push origin 0.14`); between tags nothing changes by hand. Without any tag in reach (a copy with no tags), it falls back to the commit alone.
  - **App ID per kind of build**, so a test build never replaces the official app or its data, and there is never a downgrade to go back:

    | Build | App ID | Name on the phone |
    |---|---|---|
    | Release build of `main`, no uncommitted changes | `io.github.anderewrey.bustracker` | Bus Tracker |
    | Pull request 12 (GitHub Actions) | `….bustracker.pr12` | Bus Tracker PR 12 |
    | Anything else (any debug build, other branch, uncommitted changes, detached HEAD) | `….bustracker.dev` | Bus Tracker dev |

    Each app has its own data (sign-in, zones, runs); if two have the same run turned on, both ring. A bad official build is undone by a `git revert` on `main`: a newer commit, so it installs as an update.
- **Build types** (user, 2026-11-09): the official app is a **release** build, test apps are **debug** builds.
  - **Release:** optimized and shrunk by R8 (16 MB instead of 46 MB for the phone), not debuggable. Names are kept (`-dontobfuscate` in `app/proguard-rules.pro`), so logs and crash traces stay readable. The app has no reflection of its own; the libraries bring their own R8 rules.
  - **Debug:** not optimized; debuggable, so a computer with USB debugging can read the app's files (`adb shell run-as`) and force a downgrade.
- **Signing:** an update installs only if it is signed with the same key as the installed app, and changing the official app's key means uninstalling it (its data is lost). So there is **one key for everything**, release and debug, local and GitHub; the planned configuration import (ROADMAP) also needs the same key on both apps.
  - The key is a PKCS12 file outside the repo, made once by the owner with `keytool`, with one password for the file and the key (alias `bus-tracker`). Losing it means no more updates for the installed app: keep a backup.
  - Local builds read it from `~/.gradle/gradle.properties` (`busTrackerKeystore`, `busTrackerKeystorePassword`, optional `busTrackerKeyAlias`). GitHub reads it from the secrets `SIGNING_KEYSTORE` (the file in base64) and `SIGNING_PASSWORD`.
  - Without the key, builds are signed with Android's debug key: a clone builds and installs as is.
- **GitHub Actions** (`.github/workflows/ci.yml`):
  - Every push and pull request: license headers, unit tests, a debug build. The APKs (`bus-tracker.apk` for phones, `bus-tracker-emulator.apk`) are kept 30 days on the run's page, under Artifacts.
  - Push to `main`: also the release build, published as a GitHub release: tag `build-<versionCode>`, title the versionName, notes the commit message, the two APKs attached. Without the key's secrets, the run fails instead of publishing an APK with a throwaway key.
  - **In the logs:** the Debug screen (its button was "Log" until 2026-11-09) shows the build under its title (name, versionName, build time in UTC). Each app start writes an "app start" line with the build, the app ID, the versionCode, the build type (release or debug) and the Android version, so a log file says which build wrote it; Copy starts with the same line, and Export adds it as `bustracker-build.txt` in the zip.
- **UI language:** English first. Strings live in resources, so other languages can be added later.

**Background work**
- **Tracking:** a foreground service runs only during monitoring periods, with service type `dataSync`. `location` isn't used, because the app reads the bus position from the server, not the phone's GPS.
- **Scheduling:** `AlarmManager.setExactAndAllowWhileIdle` with the `SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM` permission. Alarms are re-registered after a reboot (`BOOT_COMPLETED`).

**Permissions and settings**
- `POST_NOTIFICATIONS` (Android 13+).
- A battery-optimization exemption is requested, so polling isn't throttled.

**Network and storage**
- **HTTP:** OkHttp with a cookie jar, no automatic redirects, and an HTML parser such as jsoup for hidden fields and labels.
- **Storage:** Room or DataStore for runs, zones and settings, and EncryptedSharedPreferences or Keystore for the password.

**Map**
- **Library:** MapLibre Native for Android. osmdroid was archived in November 2024.
- **Tiles:** a keyless OpenStreetMap-based source, with no Google account or API key.
- **Layers:**
  - the bus marker
  - the stop marker
  - the zone polygons (heads-up and main styled differently)
  - the drawing-mode overlay
- **Street names:** `android.location.Geocoder`, which is free and needs no key.

---

## 9. Open items

- **A cancelled bus notice.** Only "Delayed" notices have been seen (2026-11-05); a cancellation should look the same, with another `Action`.
- **Alert ranges, next time a notice is posted.** The Today/Future filter is done by the server (Future reloads the page, which sets `hfRangeType`; the table then sends it as `RangeType`). By 2026-11-05 noon the B102 notices were gone from every range, so nothing could be compared. Next time, call `GetBusNotifications` with `RangeType` `""`, `"0"`, `"1"`, `"2"`, `"-1"`, `"All"`, null and none, and see whether any returns Today and Future together. Notices are removed once over; there is no history.
- **Quiet mode override.** Check on the phone with Do Not Disturb on that the alarm rings through.