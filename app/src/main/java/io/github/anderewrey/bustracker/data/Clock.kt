// SPDX-FileCopyrightText: 2026 anderewrey
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.anderewrey.bustracker.data

import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

// Times on screen are in 12-hour style, like BusPlanner's ("2:51:12 PM"), hardcoded for now
// (DESIGN.md §8, UI language). The Log keeps 24-hour times.

private val CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
private val CLOCK_S = DateTimeFormatter.ofPattern("h:mm:ss a", Locale.US)
private val HOUR_MIN = DateTimeFormatter.ofPattern("h:mm", Locale.US)
private val DAY_CLOCK = DateTimeFormatter.ofPattern("EEE MMM d, h:mm a", Locale.US)

/** e.g. "7:33 AM", "2:39 PM". */
fun clock(t: LocalTime): String = t.format(CLOCK)

fun clock(t: LocalDateTime): String = clock(t.toLocalTime())

/** e.g. "2:51:12 PM". */
fun clockSeconds(t: LocalTime): String = t.format(CLOCK_S)

/** e.g. "Mon Nov 16, 7:03 AM". */
fun dayClock(t: LocalDateTime): String = t.format(DAY_CLOCK)

/** e.g. "7:03–8:03 AM", or "11:30 AM–12:30 PM" across noon. */
fun clockRange(start: LocalTime, end: LocalTime): String =
    if ((start.hour < 12) == (end.hour < 12)) "${start.format(HOUR_MIN)}–${clock(end)}"
    else "${clock(start)}–${clock(end)}"

fun clockRange(start: LocalDateTime, end: LocalDateTime): String = clockRange(start.toLocalTime(), end.toLocalTime())
