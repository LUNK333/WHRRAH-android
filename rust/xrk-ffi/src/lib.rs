//! JNI bridge exposing libxrk to the WHRRAH Android app.
//!
//! Returns a JSON summary (channel list, lap boundaries, file metadata)
//! rather than mirroring libxrk's Rust types one-to-one across the FFI
//! boundary — libxrk's `ChannelValues`/`Metadata` aren't UniFFI-annotated,
//! so a hand-written JNI shim with a JSON payload is the pragmatic spike
//! approach (see WHRRAH-android docs/design-doc.md §3). Per-sample channel
//! data is summarized (count + first/last value), not streamed in full —
//! a binary bulk-fetch API can be added once M6 needs real sample data in
//! the UI.

use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;
use libxrk::ChannelValues;
use serde::Serialize;

#[derive(Serialize)]
struct ChannelSummary {
    name: String,
    units: String,
    group: Option<String>,
    sample_count: usize,
    first_value: Option<f64>,
    last_value: Option<f64>,
    first_timecode_ms: Option<i64>,
    last_timecode_ms: Option<i64>,
    dec_pts: u8,
    interpolate: bool,
}

#[derive(Serialize)]
struct LapSummary {
    num: i32,
    start_time_ms: i64,
    end_time_ms: i64,
}

#[derive(Serialize)]
struct MetadataSummary {
    driver: Option<String>,
    vehicle: Option<String>,
    venue: Option<String>,
    session: Option<String>,
    log_date: Option<String>,
    log_time: Option<String>,
}

#[derive(Serialize)]
struct XrkSummary {
    channels: Vec<ChannelSummary>,
    laps: Vec<LapSummary>,
    metadata: MetadataSummary,
    gps_present: bool,
}

#[derive(Serialize)]
struct XrkError {
    error: String,
}

#[derive(Serialize)]
struct ChannelFull {
    name: String,
    units: String,
    group: Option<String>,
    dec_pts: u8,
    interpolate: bool,
    /// Parallel to `values` - this channel's own timebase. Channels in an
    /// XRK file are NOT on a shared timebase like CSV rows are, so each one
    /// carries its own timecodes for scrubbing lookup.
    timecodes_ms: Vec<i64>,
    values: Vec<f64>,
}

#[derive(Serialize)]
struct XrkFull {
    channels: Vec<ChannelFull>,
    laps: Vec<LapSummary>,
    metadata: MetadataSummary,
    duration_ms: i64,
}

fn channel_values_to_endpoints(values: &ChannelValues) -> (usize, Option<f64>, Option<f64>) {
    macro_rules! endpoints {
        ($v:expr) => {{
            let len = $v.len();
            let first = $v.first().map(|x| *x as f64);
            let last = $v.last().map(|x| *x as f64);
            (len, first, last)
        }};
    }
    match values {
        ChannelValues::UInt8(v) => endpoints!(v),
        ChannelValues::UInt16(v) => endpoints!(v),
        ChannelValues::Int16(v) => endpoints!(v),
        ChannelValues::Int32(v) => endpoints!(v),
        ChannelValues::UInt32(v) => endpoints!(v),
        ChannelValues::Float32(v) => endpoints!(v),
        ChannelValues::Float64(v) => endpoints!(v),
    }
}

fn channel_values_to_f64_vec(values: &ChannelValues) -> Vec<f64> {
    macro_rules! to_vec {
        ($v:expr) => {
            $v.iter().map(|x| *x as f64).collect()
        };
    }
    match values {
        ChannelValues::UInt8(v) => to_vec!(v),
        ChannelValues::UInt16(v) => to_vec!(v),
        ChannelValues::Int16(v) => to_vec!(v),
        ChannelValues::Int32(v) => to_vec!(v),
        ChannelValues::UInt32(v) => to_vec!(v),
        ChannelValues::Float32(v) => to_vec!(v),
        ChannelValues::Float64(v) => to_vec!(v),
    }
}

fn parse_summary(path: &str) -> Result<XrkSummary, String> {
    let file = libxrk::read_xrk_file(path).map_err(|e| e.to_string())?;

    let channels = file
        .channels
        .iter()
        .map(|ch| {
            let (sample_count, first_value, last_value) = channel_values_to_endpoints(&ch.values);
            ChannelSummary {
                name: ch.name.clone(),
                units: ch.units.clone(),
                group: ch.group.clone(),
                sample_count,
                first_value,
                last_value,
                first_timecode_ms: ch.timecodes.first().copied(),
                last_timecode_ms: ch.timecodes.last().copied(),
                dec_pts: ch.dec_pts,
                interpolate: ch.interpolate,
            }
        })
        .collect();

    let laps = file
        .laps
        .iter()
        .map(|lap| LapSummary {
            num: lap.num,
            start_time_ms: lap.start_time,
            end_time_ms: lap.end_time,
        })
        .collect();

    let metadata = MetadataSummary {
        driver: file.metadata.driver.clone(),
        vehicle: file.metadata.vehicle.clone(),
        venue: file.metadata.venue.clone(),
        session: file.metadata.session.clone(),
        log_date: file.metadata.log_date.clone(),
        log_time: file.metadata.log_time.clone(),
    };

    Ok(XrkSummary {
        channels,
        laps,
        metadata,
        gps_present: file.gps.is_some(),
    })
}

fn parse_full(path: &str) -> Result<XrkFull, String> {
    let file = libxrk::read_xrk_file(path).map_err(|e| e.to_string())?;

    let channels: Vec<ChannelFull> = file
        .channels
        .iter()
        .map(|ch| ChannelFull {
            name: ch.name.clone(),
            units: ch.units.clone(),
            group: ch.group.clone(),
            dec_pts: ch.dec_pts,
            interpolate: ch.interpolate,
            timecodes_ms: ch.timecodes.clone(),
            values: channel_values_to_f64_vec(&ch.values),
        })
        .collect();

    let laps = file
        .laps
        .iter()
        .map(|lap| LapSummary {
            num: lap.num,
            start_time_ms: lap.start_time,
            end_time_ms: lap.end_time,
        })
        .collect();

    let metadata = MetadataSummary {
        driver: file.metadata.driver.clone(),
        vehicle: file.metadata.vehicle.clone(),
        venue: file.metadata.venue.clone(),
        session: file.metadata.session.clone(),
        log_date: file.metadata.log_date.clone(),
        log_time: file.metadata.log_time.clone(),
    };

    let duration_ms = channels
        .iter()
        .filter_map(|ch| ch.timecodes_ms.last().copied())
        .max()
        .unwrap_or(0);

    Ok(XrkFull {
        channels,
        laps,
        metadata,
        duration_ms,
    })
}

/// JNI entry point: `com.lunk333.whrrah.xrk.XrkNative.parseFullJson(path: String): String`
///
/// Like [`parseSummaryJson`] but includes every channel's full timecodes +
/// values (each channel keeps its own timebase - they are NOT resampled
/// onto a shared one). This is what drives the scrubber UI. JSON-over-JNI
/// is fine for the file sizes seen so far (tens of MB) but should become a
/// binary bulk-fetch if M6's real usage needs better throughput.
#[no_mangle]
pub extern "system" fn Java_com_lunk333_whrrah_xrk_XrkNative_parseFullJson<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    path: JString<'local>,
) -> jstring {
    let path: String = match env.get_string(&path) {
        Ok(s) => s.into(),
        Err(e) => {
            let err = serde_json::to_string(&XrkError { error: e.to_string() }).unwrap();
            return env.new_string(err).unwrap().into_raw();
        }
    };

    let json = match parse_full(&path) {
        Ok(full) => serde_json::to_string(&full)
            .unwrap_or_else(|e| serde_json::to_string(&XrkError { error: e.to_string() }).unwrap()),
        Err(e) => serde_json::to_string(&XrkError { error: e }).unwrap(),
    };

    env.new_string(json).unwrap().into_raw()
}

/// JNI entry point: `com.lunk333.whrrah.xrk.XrkNative.parseSummaryJson(path: String): String`
///
/// Returns a JSON object on success (see [`XrkSummary`]), or
/// `{"error": "..."}` on failure — callers should check for the `error`
/// key rather than relying on exceptions crossing the FFI boundary.
#[no_mangle]
pub extern "system" fn Java_com_lunk333_whrrah_xrk_XrkNative_parseSummaryJson<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    path: JString<'local>,
) -> jstring {
    let path: String = match env.get_string(&path) {
        Ok(s) => s.into(),
        Err(e) => {
            let err = serde_json::to_string(&XrkError { error: e.to_string() }).unwrap();
            return env.new_string(err).unwrap().into_raw();
        }
    };

    let json = match parse_summary(&path) {
        Ok(summary) => serde_json::to_string(&summary).unwrap_or_else(|e| {
            serde_json::to_string(&XrkError { error: e.to_string() }).unwrap()
        }),
        Err(e) => serde_json::to_string(&XrkError { error: e }).unwrap(),
    };

    env.new_string(json).unwrap().into_raw()
}
