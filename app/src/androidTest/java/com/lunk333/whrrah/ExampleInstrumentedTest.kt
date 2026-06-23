package com.lunk333.whrrah

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals

// Placeholder instrumentation test. The real first instrumentation test to
// write is the libxrk JNI/UniFFI load-and-parse spike from design doc §3
// step 5 - replace or add alongside this once that spike starts.
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun useAppContext() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.lunk333.whrrah", appContext.packageName)
    }
}
