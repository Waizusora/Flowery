use jni::objects::JClass;
use jni::sys::jstring;
use jni::JNIEnv;

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeEngineVersion(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    let version = env
        .new_string(format!("Flowery Rust audio engine {}", env!("CARGO_PKG_VERSION")))
        .expect("Unable to allocate engine version string");
    version.into_raw()
}
