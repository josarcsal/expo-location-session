package com.margelo.nitro.locationsession.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateTest {
    private val rec = FixRecord(
        uuid = "u-1",
        sessionId = "s1",
        fix = Fix(latitude = 1.5, longitude = 2.5, accuracy = 7.0, altitude = 30.0, timestampMs = 1_700_000_000_000),
        battery = Battery(level = 0.5, charging = true)
    )
    private val extras = """{"orderId":"o1","n":3,"vip":true}"""

    private fun obj(s: String) = JSONObject(s)

    @Test
    fun wholePlaceholderKeepsJsonType() {
        val t = """{"lat":"{{latitude}}","ts":"{{timestamp}}","c":"{{batteryCharging}}","id":"{{id}}","s":"{{sessionId}}","n":"{{extras.n}}","vip":"{{extras.vip}}","o":"{{extras.orderId}}"}"""
        val o = obj(renderTemplate(t, rec, extras))
        assertEquals(1.5, (o.get("lat") as Number).toDouble(), 0.0)
        assertEquals(1_700_000_000_000L, (o.get("ts") as Number).toLong())
        assertEquals(true, o.get("c"))
        assertEquals("u-1", o.get("id"))
        assertEquals("s1", o.get("s"))
        assertEquals(3, o.get("n"))
        assertEquals(true, o.get("vip"))
        assertEquals("o1", o.get("o"))
    }

    @Test
    fun timestampRendersAsInteger() {
        val out = renderTemplate("""{"ts":"{{timestamp}}"}""", rec, "{}")
        assertTrue(out.contains("1700000000000"))
        assertFalse(out.contains("1700000000000.") || out.lowercase().contains("e+") || out.contains("E12"))
    }

    @Test
    fun missingOptionalOmitsKeyAndNullsArrayElement() {
        val o = obj(renderTemplate("""{"speed":"{{speed}}","arr":["{{heading}}","{{altitude}}"]}""", rec, "{}"))
        assertFalse(o.has("speed"))
        val arr = o.get("arr") as JSONArray
        assertEquals(2, arr.length())
        assertTrue(arr.isNull(0))
        assertEquals(30.0, (arr.get(1) as Number).toDouble(), 0.0)
    }

    @Test
    fun missingFieldInInterpolationIsEmpty() {
        assertEquals("v=.", obj(renderTemplate("""{"s":"v={{speed}}."}""", rec, "{}")).getString("s"))
    }

    @Test
    fun interpolationEscapesAndDoesNotRescanValues() {
        val tricky = """{"note":"say \"hi\"\\\n{{latitude}}"}"""
        val o = obj(renderTemplate("""{"label":"x {{extras.note}} y {{latitude}}"}""", rec, tricky))
        assertEquals("x say \"hi\"\\\n{{latitude}} y 1.5", o.getString("label"))
    }

    @Test
    fun isoAndNestingAndLiterals() {
        val o = obj(renderTemplate("""{"a":{"b":["{{timestampIso}}",5,true,null,"lit }} {"]}}""", rec, "{}"))
        val b = o.getJSONObject("a").getJSONArray("b")
        assertEquals("2023-11-14T22:13:20.000Z", b.get(0))
        assertEquals(5, b.get(1))
        assertEquals(true, b.get(2))
        assertTrue(b.isNull(3))
        assertEquals("lit }} {", b.get(4))
    }

    @Test
    fun rejects() {
        val cases = listOf(
            Triple("[1]", "{}", "must be a JSON object"),
            Triple("not json", "{}", "must be a JSON object"),
            Triple("""{"a":"{{altitud}}"}""", "{}", "unknown field altitud"),
            Triple("""{"a":"{{ latitude }}"}""", "{}", "unknown field  latitude "),
            Triple("""{"a":"{{extras.missing}}"}""", """{"n":3}""", "unknown extras key missing"),
            Triple("""{"a":[{"b":"{{nope}}"}]}""", "{}", "unknown field nope"),
            Triple("""{"a":"{{latitude"}""", "{}", """malformed placeholder in "{{latitude""""),
            Triple("""{"a":"{{}}"}""", "{}", """malformed placeholder in "{{}}"""")
        )
        for ((template, ex, reason) in cases) {
            assertEquals(template, reason, validateTemplate(template, ex))
        }
    }

    @Test
    fun sizeLimitIs8192Bytes() {
        fun pad(n: Int) = """{"a":"""" + "x".repeat(n) + """"}"""
        assertNull(validateTemplate(pad(8192 - 8), "{}"))
        assertEquals("larger than 8 KB", validateTemplate(pad(8193 - 8), "{}"))
    }

    @Test
    fun validateSyncPrefixesTemplateReasonAndAllowsLatestMode() {
        assertEquals("template: must be a JSON object", validateSync(SyncConfig(templateJson = "[1]"), "{}"))
        assertNull(validateSync(SyncConfig(mode = QueueMode.LATEST, templateJson = """{"a":"{{id}}"}"""), "{}"))
    }

    @Test
    fun nonFiniteValueRendersAsNull() {
        val bad = FixRecord("u-1", "s1", Fix(latitude = 1.5, longitude = 2.5, accuracy = 7.0, speed = Double.NaN, timestampMs = 1), null)
        val o = obj(renderTemplate("""{"speed":"{{speed}}","lat":"{{latitude}}","s":"v={{speed}}"}""", bad, "{}"))
        assertTrue(o.has("speed"))
        assertTrue(o.isNull("speed"))
        assertEquals(1.5, (o.get("lat") as Number).toDouble(), 0.0)
        assertEquals("v=", o.getString("s"))
    }

    @Test
    fun trailingInputIsRejected() {
        assertEquals("must be a JSON object", validateTemplate("""{"a":1} xyz""", "{}"))
    }

    @Test
    fun nonJsonIsRejected() {
        val vectors = listOf(
            """{"a":1} // x""", """/*c*/{"a":1}""", """{'a':1}""", """{a:1}""", """{"a":01}""", """{"a":NaN}""", """{"a":Infinity}""",
            """{"a":"x\q"}""", """{"a":+1}""", """{"a":.5}""", """{"a":1.}""", """{"a":0x1}""", "{\"a\":\"tab\there\"}", """{"a":1}#"""
        )
        for (json in vectors) assertEquals(json, "must be a JSON object", validateTemplate(json, "{}"))
    }

    @Test
    fun strictJsonIsAccepted() {
        assertNull(validateTemplate(""" {"a":[true,false,null,-0.5e+3,1E2,0,"\n\"é","é",{},[]]} """, "{}"))
    }

    @Test
    fun trailingCommasAndLeadingBomAreAccepted() {
        val vectors = listOf("""{"a":1,}""", """{"a":[1,2,],}""", "\uFEFF{\"a\":1}", "\uFEFF {\"a\":[1,]}")
        for (json in vectors) assertNull(json, validateTemplate(json, "{}"))
    }

    @Test
    fun misplacedCommasAndBomAreRejected() {
        val vectors = listOf(
            """{,}""", """{"a":[,]}""", """{"a":1,,}""", """{"a":[1,,]}""", """[1,]""", " \uFEFF{\"a\":1}", "{\"a\":1}\uFEFF", "\uFEFF\uFEFF{}"
        )
        for (json in vectors) assertEquals(json, "must be a JSON object", validateTemplate(json, "{}"))
    }

    @Test
    fun trailingCommasAreDroppedWhenRendering() {
        val o = obj(renderTemplate("\uFEFF{\"a\":[1,],\"s\":\"{{sessionId}}\",}", rec, """{"k":1,}"""))
        assertEquals(listOf(1), (0 until o.getJSONArray("a").length()).map { o.getJSONArray("a").getInt(it) })
        assertEquals("s1", o.getString("s"))
        assertEquals(2, o.length())
    }

    @Test
    fun emptyTemplateIsRequired() {
        assertEquals("body.template is required", validateSync(SyncConfig(templateJson = ""), "{}"))
    }

    @Test
    fun timestampIsoAndExtrasNumbersInterpolate() {
        val o = obj(renderTemplate("""{"at":"at {{timestampIso}}","n":"n={{extras.n}} f={{extras.f}}"}""", rec, """{"n":3,"f":2.5}"""))
        assertEquals("at 2023-11-14T22:13:20.000Z", o.getString("at"))
        assertEquals("n=3 f=2.5", o.getString("n"))
    }
}
