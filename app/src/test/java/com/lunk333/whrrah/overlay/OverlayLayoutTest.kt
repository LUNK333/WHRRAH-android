package com.lunk333.whrrah.overlay

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OverlayLayoutTest {
    private val bundled = File("src/main/assets/layouts/layout_phone.json").readText()

    @Test
    fun readsTheDesktopLayout() {
        val l = OverlayLayout.parse(bundled)
        assertEquals(2688, l.width); assertEquals(2016, l.height)
        assertEquals("16:9", l.aspectRatio)
        assertEquals(listOf("Lap Timer", "Numeric Display", "Track Map", "G Meter"), l.widgets.map { it.type })
        val speed = l.widgets[1]
        assertEquals("GPS Speed", speed.channel); assertEquals("MPH", speed.label)
        assertEquals(0, speed.decimals); assertEquals(62, speed.labelFontSize); assertEquals(13, speed.labelGap)
        assertEquals((24 * 6.575).toInt(), speed.fontSize)
        assertEquals(180, l.widgets[2].mapRotation)
        assertEquals("GPS LonAcc", l.widgets[3].longChannel)
        assertArrayEquals(intArrayOf(0, 200, 255), l.widgets[3].color)
    }

    @Test
    fun cropMatchesDesktop() {
        // 4:3 frame, 16:9 crop: full width, 1512 tall, centred (252) then moved down 141.
        assertArrayEquals(intArrayOf(0, 393, 2688, 1512), OverlayLayout.parse(bundled).cropRect())
        // Offsets clamp so the crop can't leave the frame.
        assertArrayEquals(intArrayOf(0, 504, 2688, 1512), OverlayLayout(2688, 2016, "16:9", 0, 9999).cropRect())
        // Too tall to fit full-width: full height, crop the width instead.
        assertArrayEquals(intArrayOf(240, 0, 1440, 1080), OverlayLayout(1920, 1080, "4:3").cropRect())
        assertArrayEquals(intArrayOf(0, 0, 1920, 1080), OverlayLayout(1920, 1080, "Original").cropRect())
    }

    @Test
    fun roundTripKeepsEverythingIncludingUnknownFields() {
        val o = JSONObject(bundled)
        o.put("future_setting", 7)
        o.getJSONArray("widgets").getJSONObject(0).put("future_widget_field", "x")
        val back = OverlayLayout.parse(o.toString()).toJson()
        assertEquals(7, back.getInt("future_setting"))
        // Every widget field the desktop wrote comes back with the same value.
        for (i in 0 until o.getJSONArray("widgets").length()) {
            val a = o.getJSONArray("widgets").getJSONObject(i)
            val b = back.getJSONArray("widgets").getJSONObject(i)
            for (k in a.keys()) assertEquals("widget $i $k", a.get(k).toString(), b.get(k).toString().let {
                // JSON numbers: 0.0 vs 0 — compare numerically.
                if (a.get(k) is Number) (b.get(k) as Number).toDouble().let { d -> if ((a.get(k) as Number).toDouble() == d) a.get(k).toString() else it } else it
            })
        }
        assertEquals("x", back.getJSONArray("widgets").getJSONObject(0).getString("future_widget_field"))
    }

    @Test
    fun oldBareArrayFormatAndDefaults() {
        val l = OverlayLayout.parse("""[{"widget_type": "G Meter", "x": 5, "y": 6, "scale": 2.0}, {"no_type": 1}]""")
        assertEquals(1, l.widgets.size)
        val g = l.widgets[0]
        assertEquals(2.0, g.scaleX, 0.0); assertEquals(2.0, g.scaleY, 0.0)
        assertEquals(1, g.decimals)
        assertArrayEquals(intArrayOf(0, 200, 255), g.color)
        assertTrue(g.label == "G Meter")
    }
}
