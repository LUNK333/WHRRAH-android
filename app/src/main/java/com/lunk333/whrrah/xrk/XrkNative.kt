package com.lunk333.whrrah.xrk

/**
 * JNI bridge to the xrk_ffi Rust crate (rust/xrk-ffi), which wraps libxrk
 * (https://github.com/m3rlin45/libxrk). See WHRRAH-android docs/design-doc.md
 * §3 for the integration rationale.
 */
object XrkNative {
    init {
        System.loadLibrary("xrk_ffi")
    }

    /**
     * Parses the .xrk/.xrz file at [path] and returns a JSON summary
     * (channels, laps, metadata) - see XrkSummaryJson for the shape, or
     * `{"error": "..."}` on failure.
     */
    @JvmStatic
    external fun parseSummaryJson(path: String): String

    /**
     * Like [parseSummaryJson] but includes each channel's full timecodes +
     * values (own timebase per channel, not resampled to a shared one).
     * This is what feeds the scrubber - see XrkLog/XrkLogParser.
     */
    @JvmStatic
    external fun parseFullJson(path: String): String
}
