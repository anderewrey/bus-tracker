// SPDX-FileCopyrightText: 2026 anderewrey
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.anderewrey.bustracker

import io.github.anderewrey.bustracker.data.Run
import io.github.anderewrey.bustracker.data.buildRuns
import io.github.anderewrey.bustracker.net.BUS_FIELD
import io.github.anderewrey.bustracker.net.POSITION_FIELD
import io.github.anderewrey.bustracker.net.STOP_FIELD
import io.github.anderewrey.bustracker.net.STUDENTS_DDL
import io.github.anderewrey.bustracker.net.Student
import io.github.anderewrey.bustracker.net.studentOptions
import io.github.anderewrey.bustracker.net.formFields
import io.github.anderewrey.bustracker.net.hiddenFields
import io.github.anderewrey.bustracker.net.parseDelta
import io.github.anderewrey.bustracker.net.parseSchoolPosition
import io.github.anderewrey.bustracker.net.reading
import io.github.anderewrey.bustracker.net.timeLabels
import io.github.anderewrey.bustracker.net.transportRows
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ParsersTest {
    private fun entry(kind: String, id: String, content: String) = "${content.length}|$kind|$id|$content|"

    @Test
    fun deltaKeepsPipesInsideContent() {
        val html = "<label>7:45:10 AM</label><label>7:44:45 AM</label><p>a|b|c</p>"
        val text = entry("updatePanel", "MainContent_NestContent_UpdatePanel_Map", html) +
            entry("hiddenField", BUS_FIELD, """[{"Latitude":44.7495861,"Longitude":-63.2907251,"Label":""}]""") +
            entry("hiddenField", STOP_FIELD, """[{"Latitude":44.737825,"Longitude":-63.3050693,"Label":"B101 SMP_PU"}]""")
        val entries = parseDelta(text)
        assertEquals(listOf("updatePanel", "hiddenField", "hiddenField"), entries.map { it.kind })
        assertEquals(html, entries[0].content)

        val fields = entries.filter { it.kind == "hiddenField" }.associate { it.id to it.content }
        val r = reading(fields, timeLabels(Jsoup.parse(html)), null)
        assertEquals(44.7495861, r.busLat!!, 1e-9)
        assertEquals("B101 SMP_PU", r.runLabel)
        assertEquals("7:44:45 AM", r.reported)
    }

    @Test
    fun studentDropdown() {
        val doc = Jsoup.parse(
            """<select name="$STUDENTS_DDL" id="MainContent_ddlStudents">
               <option value="g1">Student A</option><option selected="selected" value="g2">Student B</option></select>"""
        )
        val (students, selected) = studentOptions(doc)
        assertEquals(listOf(Student("g1", "Student A"), Student("g2", "Student B")), students)
        assertEquals("g2", selected)
    }

    @Test
    fun formFieldsLikeABrowser() {
        val doc = Jsoup.parse(
            """<form><input type="hidden" name="__VIEWSTATE" value="v"><input name="txt" value="t">
               <input type="checkbox" name="on" value="1" checked><input type="checkbox" name="off" value="1">
               <input type="submit" name="btn" value="Go"><select name="sel"><option value="a">A</option>
               <option value="b" selected>B</option></select><textarea name="ta">x</textarea></form>"""
        )
        assertEquals(mapOf("__VIEWSTATE" to "v", "txt" to "t", "on" to "1", "sel" to "b", "ta" to "x"), formFields(doc))
    }

    @Test
    fun schoolPosition() {
        val raw = "[[{^Id^:{^ObjId^:1000001,^ObjType^:1},^Name^:^Sample School^,^SchoolID^:^SMP^," +
            "^Location^:{^Address^:^999 DAPHNE WAY^,^Latitude^:44.7481159,^Longitude^:-63.2802613}}]]"
        val s = parseSchoolPosition("Sample School", raw)!!
        assertEquals(44.7481159, s.lat, 1e-9)
        assertEquals(-63.2802613, s.lon, 1e-9)
    }

    @Test
    fun noVehicleGivesNoBusPosition() {
        val r = reading(mapOf(BUS_FIELD to "", STOP_FIELD to ""), emptyList(), "Information unavailable")
        assertNull(r.busLat)
        assertEquals("Information unavailable", r.bulletin)
    }

    private fun row(n: Int, type: String, time: String, place: String) = """
        <span id="MainContent_NestContent_rpTransportation_rTransportation_${n}_lblStopType_$n">$type</span>
        <span id="MainContent_NestContent_rpTransportation_rTransportation_${n}_lblArrival_$n">Time</span>
        <span id="MainContent_NestContent_rpTransportation_rTransportation_${n}_lblArrivalValue_$n">$time</span>
        <span id="MainContent_NestContent_rpTransportation_rTransportation_${n}_lblDetailLocation_$n">Stop</span>
        <span id="MainContent_NestContent_rpTransportation_rTransportation_${n}_lblDetailLocationValue_$n">$place</span>
        <span id="MainContent_NestContent_rpTransportation_rTransportation_${n}_RunRouteInfo_$n">B101</span>
    """

    private val positions =
        """[{"Time":"7:32 AM","Description":"12 THIS ST","Latitude":"44.737825","Longitude":"-63.3050693","DrawColour":"#00D500"},""" +
            """{"Time":"2:39 PM","Description":"12 THIS ST","Latitude":"44.737825","Longitude":"-63.3050693","DrawColour":"#8000FF"}]"""

    private val infoHtml = "<form><input type=\"hidden\" name=\"$POSITION_FIELD\" value='$positions'>" +
        row(0, "AM Pickup", "7:33 AM", "12 THIS ST") +
        row(1, "AM Dropoff", "7:51 AM", "Sample School") +
        row(2, "PM Pickup", "2:15 PM", "Sample School") +
        row(3, "PM Dropoff", "2:39 PM", "12 THIS ST") + "</form>"

    @Test
    fun transportRowsAndRuns() {
        val doc = Jsoup.parse(infoHtml)
        val rows = transportRows(doc, hiddenFields(doc)[POSITION_FIELD])
        assertEquals(4, rows.size)
        assertEquals("AM Pickup", rows[0].stopType)
        assertEquals("B101", rows[0].route)
        assertEquals(44.737825, rows[0].lat!!, 1e-9)
        assertNull(rows[1].lat)

        val a = Student("a", "Student A")
        val b = Student("b", "Student B")
        val runs = buildRuns(mapOf(a to rows, b to rows))
        assertEquals(listOf("B101 AM", "B101 PM"), runs.map { it.key })
        assertEquals(listOf(a, b), runs[0].students)
        assertEquals("Pickup 7:33", runs[0].plannedLine)
        assertEquals("monitoring 7:03–8:03", runs[0].periodLine)
        assertEquals("Dropoff 14:39", runs[1].plannedLine)
        assertEquals("monitoring 14:09–15:09", runs[1].periodLine)
        assertEquals("Sample School", runs[0].school)
        assertEquals("Sample School", runs[1].school)
        assertEquals("Sample School", Run.fromJson(runs[0].toJson()).school)
    }
}
