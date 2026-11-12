//! Android USB Host file-descriptor bridge backed by libusb.
//!
//! The Java UsbDeviceConnection must remain open for the entire native session.
//! libusb_close() does not close the Android-owned file descriptor.

#[cfg(target_os = "android")]
use std::collections::HashMap;
#[cfg(target_os = "android")]
use std::sync::{Mutex, OnceLock};
#[cfg(target_os = "android")]
use std::sync::atomic::{AtomicI64, Ordering};

#[cfg(target_os = "android")]
struct UsbSession {
    context: *mut libusb1_sys::libusb_context,
    handle: *mut libusb1_sys::libusb_device_handle,
    interface_number: u8,
}

// The session is only accessed while holding the registry mutex. libusb event
// handling and transfer callbacks will be serialized by the owning worker.
#[cfg(target_os = "android")]
unsafe impl Send for UsbSession {}

#[cfg(target_os = "android")]
impl Drop for UsbSession {
    fn drop(&mut self) {
        unsafe {
            if !self.handle.is_null() {
                let _ = libusb1_sys::libusb_release_interface(
                    self.handle,
                    i32::from(self.interface_number),
                );
                libusb1_sys::libusb_close(self.handle);
                self.handle = std::ptr::null_mut();
            }
            if !self.context.is_null() {
                libusb1_sys::libusb_exit(self.context);
                self.context = std::ptr::null_mut();
            }
        }
    }
}

#[cfg(target_os = "android")]
static NEXT_SESSION: AtomicI64 = AtomicI64::new(1);
#[cfg(target_os = "android")]
static SESSIONS: OnceLock<Mutex<HashMap<i64, UsbSession>>> = OnceLock::new();

#[cfg(target_os = "android")]
fn sessions() -> &'static Mutex<HashMap<i64, UsbSession>> {
    SESSIONS.get_or_init(|| Mutex::new(HashMap::new()))
}

/// Wrap an already permission-authorized Android USB fd. The fd remains owned
/// by UsbDeviceConnection and must outlive this session.
#[cfg(target_os = "android")]
pub fn open_android_fd(fd: i32, interface_number: u8, alternate_setting: u8) -> Result<i64, String> {
    if fd < 0 {
        return Err("Android USB connection returned an invalid file descriptor".into());
    }

    let mut context = std::ptr::null_mut();
    let init = unsafe { libusb1_sys::libusb_init(&mut context) };
    if init != 0 || context.is_null() {
        return Err(format!("libusb_init failed with code {init}"));
    }

    let mut handle = std::ptr::null_mut();
    let wrapped = unsafe {
        libusb1_sys::libusb_wrap_sys_device(context, fd as isize, &mut handle)
    };
    if wrapped != 0 || handle.is_null() {
        unsafe { libusb1_sys::libusb_exit(context); }
        return Err(format!(
            "libusb_wrap_sys_device failed with code {wrapped}; this Android USB backend may not support fd wrapping"
        ));
    }

    let claimed = unsafe { libusb1_sys::libusb_claim_interface(handle, i32::from(interface_number)) };
    if claimed != 0 {
        unsafe {
            libusb1_sys::libusb_close(handle);
            libusb1_sys::libusb_exit(context);
        }
        return Err(format!("libusb_claim_interface failed with code {claimed}"));
    }

    let selected = unsafe {
        libusb1_sys::libusb_set_interface_alt_setting(
            handle,
            i32::from(interface_number),
            i32::from(alternate_setting),
        )
    };
    if selected != 0 {
        unsafe {
            let _ = libusb1_sys::libusb_release_interface(handle, i32::from(interface_number));
            libusb1_sys::libusb_close(handle);
            libusb1_sys::libusb_exit(context);
        }
        return Err(format!("libusb_set_interface_alt_setting failed with code {selected}"));
    }

    let session = NEXT_SESSION.fetch_add(1, Ordering::Relaxed);
    if session <= 0 {
        unsafe {
            let _ = libusb1_sys::libusb_release_interface(handle, i32::from(interface_number));
            libusb1_sys::libusb_close(handle);
            libusb1_sys::libusb_exit(context);
        }
        return Err("Native USB session handle space exhausted".into());
    }
    sessions().lock().unwrap_or_else(|e| e.into_inner()).insert(
        session,
        UsbSession { context, handle, interface_number },
    );
    Ok(session)
}

#[cfg(target_os = "android")]
pub fn close_session(session: i64) {
    if session <= 0 { return; }
    sessions().lock().unwrap_or_else(|e| e.into_inner()).remove(&session);
}
