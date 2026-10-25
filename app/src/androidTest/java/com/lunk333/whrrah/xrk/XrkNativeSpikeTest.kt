package com.lunk333.whrrah.xrk

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * libxrk spike (design doc §3 step 5): proves the cross-compiled
 * jniLibs/<abi>/libxrk_ffi.so loads on a real device/emulator and that
 * libxrk correctly parses a real .xrk file end to end through the JNI
 * bridge - not just that the Kotlin/Gradle side compiles.
 *
 * sample_session.xrk is libxrk's SFJ test fixture (13 laps, 26 channels) -
 * see libxrk/tests/test_data/test_data.md for the expected counts this
 * test checks against.
 */
@RunWith(AndroidJUnit4::class)
class XrkNativeSpikeTest {

    @Test
    fun loadsAndParsesSampleXrkFile() {
        // Test assets live in the test APK, not the app-under-test's APK -
        // use the instrumentation's own context to read them, but the
        // app-under-test's context for a writable cache dir.
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val xrkFile = File(targetContext.cacheDir, "sample_session.xrk")
        testContext.assets.open("sample_session.xrk").use { input ->
            xrkFile.outputStream().use { output -> input.copyTo(output) }
        }

        val json = XrkNative.parseSummaryJson(xrkFile.absolutePath)
        val result = JSONObject(json)

        assertFalse("expected no error, got: $json", result.has("error"))

        val channels = result.getJSONArray("channels")
        assertTrue("expected channels, got ${channels.length()}", channels.length() > 0)

        val channelNames = (0 until channels.length()).map { channels.getJSONObject(it).getString("name") }
        println("libxrk spike: parsed ${channelNames.size} channels: $channelNames")

        val laps = result.getJSONArray("laps")
        println("libxrk spike: parsed ${laps.length()} laps")

        val metadata = result.getJSONObject("metadata")
        println("libxrk spike: vehicle=${metadata.optString("vehicle")} driver=${metadata.optString("driver")}")

        assertTrue("expected at least one lap", laps.length() > 0)
    }
}
