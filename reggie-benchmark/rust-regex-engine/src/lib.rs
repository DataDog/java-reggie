//! JNI shim exposing the Rust `regex` crate to the reggie benchmark suite as a
//! fourth comparison engine (java.util.regex / reggie / RE2J / rust).
//!
//! The engine is DELIBERATELY minimal: `compile`, `is_match` (unanchored),
//! `find` (byte offsets, packed), and `dispose`. JNI string marshalling
//! (GetStringUTFChars) is the same per-call UTF-8 conversion cost the
//! production dd_sds path pays, so benchmark numbers include it — do not
//! "optimize" it away by caching raw pointers.
//!
//! Compile failures (unsupported syntax, size limits) return a null handle so
//! the Java side can classify them as refusals — coverage on the real
//! logs-backend corpus is part of what this lane measures.
//!
//! Build: `cargo build --release` (or `./gradlew :reggie-benchmark:buildRustEngine`).

use regex::Regex;
use std::os::raw::{c_char, c_void};

// ---- minimal JNI FFI (hand-rolled; no jni crate dependency) ----
type JNIEnvPtr = *mut *const *const c_void; // JNIEnv* -> &JNINativeInterface*
type JString = *const c_void;
type JClass = *const c_void;
type JLong = i64;
type JBoolean = u8;

const JNI_TRUE: JBoolean = 1;
const JNI_FALSE: JBoolean = 0;

// JNINativeInterface function-table indices (fixed by the JNI spec).
const FN_GET_STRING_UTF_CHARS: usize = 169;
const FN_RELEASE_STRING_UTF_CHARS: usize = 170;

unsafe fn jni_table(env: JNIEnvPtr) -> *const *const c_void {
    *env
}

unsafe fn jni_string<'a>(
    env: JNIEnvPtr,
    s: JString,
) -> (std::borrow::Cow<'a, str>, *const c_char, JBoolean) {
    type GetUtf = unsafe extern "C" fn(JNIEnvPtr, JString, *mut JBoolean) -> *const c_char;
    let get: GetUtf = std::mem::transmute(*jni_table(env).add(FN_GET_STRING_UTF_CHARS));
    let mut is_copy: JBoolean = 0;
    let ptr = get(env, s, &mut is_copy);
    let bytes = std::ffi::CStr::from_ptr(ptr).to_bytes();
    (String::from_utf8_lossy(bytes), ptr, is_copy)
}

unsafe fn jni_release(env: JNIEnvPtr, s: JString, ptr: *const c_char) {
    type Rel = unsafe extern "C" fn(JNIEnvPtr, JString, *const c_char);
    let rel: Rel = std::mem::transmute(*jni_table(env).add(FN_RELEASE_STRING_UTF_CHARS));
    rel(env, s, ptr);
}

// ---- engine API ----

// The crate default (10MB) rejects the counted-quantifier families the benchmark
// corpus exercises; raised so refusals reflect true capability limits, and the
// counted-family wall is measured, not silently defaulted.
const NFA_SIZE_LIMIT: usize = 256 * 1024 * 1024;

#[no_mangle]
pub extern "C" fn Java_com_datadoghq_reggie_benchmark_engines_RustRegexEngine_rxCompile(
    env: JNIEnvPtr,
    _cls: JClass,
    pattern: JString,
) -> JLong {
    unsafe {
        let (pat, ptr, _copy) = jni_string(env, pattern);
        let built = regex::RegexBuilder::new(pat.as_ref())
            .size_limit(NFA_SIZE_LIMIT)
            .build();
        jni_release(env, pattern, ptr);
        match built {
            // Leak intentionally: handles are disposed explicitly via rxDispose.
            Ok(r) => Box::into_raw(Box::new(r)) as JLong,
            Err(_) => 0,
        }
    }
}

#[no_mangle]
pub extern "C" fn Java_com_datadoghq_reggie_benchmark_engines_RustRegexEngine_rxIsMatch(
    env: JNIEnvPtr,
    _cls: JClass,
    handle: JLong,
    input: JString,
) -> JBoolean {
    if handle == 0 {
        return JNI_FALSE;
    }
    let rx = unsafe { &*(handle as *const Regex) };
    unsafe {
        let (hay, ptr, _copy) = jni_string(env, input);
        let matched = rx.is_match(hay.as_ref());
        jni_release(env, input, ptr);
        if matched {
            JNI_TRUE
        } else {
            JNI_FALSE
        }
    }
}

/// Returns (start << 32) | (end & 0xFFFFFFFF) as UTF-8 byte offsets, or -1 when
/// there is no match. Byte offsets, not Java char offsets — documented on the
/// Java side; the boolean benchmark lane only tests the -1 sentinel.
#[no_mangle]
pub extern "C" fn Java_com_datadoghq_reggie_benchmark_engines_RustRegexEngine_rxFind(
    env: JNIEnvPtr,
    _cls: JClass,
    handle: JLong,
    input: JString,
) -> JLong {
    if handle == 0 {
        return -1;
    }
    let rx = unsafe { &*(handle as *const Regex) };
    unsafe {
        let (hay, ptr, _copy) = jni_string(env, input);
        let packed = rx
            .find(hay.as_ref())
            .map(|m| ((m.start() as JLong) << 32) | (m.end() as JLong & 0xFFFF_FFFF))
            .unwrap_or(-1);
        jni_release(env, input, ptr);
        packed
    }
}

#[no_mangle]
pub extern "C" fn Java_com_datadoghq_reggie_benchmark_engines_RustRegexEngine_rxDispose(
    _env: JNIEnvPtr,
    _cls: JClass,
    handle: JLong,
) {
    if handle != 0 {
        drop(unsafe { Box::from_raw(handle as *mut Regex) });
    }
}
