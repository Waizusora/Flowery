mod streaming;
mod native_pcm;
mod lyrics;
mod library;
mod catalog;
mod queue;
mod playback_controller;
mod playlists;
mod usb_pcm;
mod usb_encoded;
mod usb_encoded_formats;
mod usb_producer;
mod playback_policy;
mod usb_descriptors;
#[cfg(target_os = "android")]
mod aaudio;
use base64::Engine;

use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jbyteArray, jint, jstring};
use jni::JNIEnv;