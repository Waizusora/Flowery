//! Android USB Host file-descriptor bridge backed by libusb.
//!
//! The Java UsbDeviceConnection owns the file descriptor and must remain open
//! until every native transfer has been cancelled and drained.

#[cfg(target_os = "android")]
use std::collections::HashMap;
#[cfg(target_os = "android")]
use std::sync::{Arc, Mutex, OnceLock};
#[cfg(target_os = "android")]
use std::sync::atomic::{AtomicI64, Ordering};

#[cfg(target_os = "android")]
pub(super) struct UsbSession {
    pub(super) context: *mut libusb1_sys::libusb_context,
    pub(super) handle: *mut libusb1_sys::libusb_device_handle,
    pub(super) interface_number: u8,
}

// libusb handles are only used by the serialized owner worker. The Arc keeps
// the context and handle alive until that worker has drained all callbacks.
#[cfg(target_os = "android")]
unsafe impl Send for UsbSession {}
#[cfg(target_os = "android")]
unsafe impl Sync for UsbSession {}

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

/// `LIBUSB_ERROR_BUSY`: the interface is owned by another driver or process.
#[cfg(target_os = "android")]
const LIBUSB_ERROR_BUSY: i32 = -6;

#[cfg(target_os = "android")]
static NEXT_SESSION: AtomicI64 = AtomicI64::new(1);
#[cfg(target_os = "android")]
static SESSIONS: OnceLock<Mutex<HashMap<i64, Arc<UsbSession>>>> = OnceLock::new();

#[cfg(target_os = "android")]
// Android's UsbManager grants access to one already-open fd. Avoid libusb's
// global /sys and /dev enumeration, which is denied to unrooted apps.
static LIBUSB_NO_DEVICE_DISCOVERY: OnceLock<i32> = OnceLock::new();

#[cfg(target_os = "android")]
fn sessions() -> &'static Mutex<HashMap<i64, Arc<UsbSession>>> {
    SESSIONS.get_or_init(|| Mutex::new(HashMap::new()))
}

#[cfg(target_os = "android")]
pub(super) fn session_ref(session: i64) -> Option<Arc<UsbSession>> {
    if session <= 0 { return None; }
    sessions().lock().unwrap_or_else(|e| e.into_inner()).get(&session).cloned()
}

/// Returns libusb's negotiated bus speed for the opened device.
/// Values follow libusb_speed: 1 low, 2 full, 3 high, 4 super, 5 super-plus; 0 is unknown.
/// The stream lifecycle gate protects the query from concurrent session close.
#[cfg(target_os = "android")]
pub(super) fn bus_speed(session: i64) -> i32 {
    crate::usb_stream::bus_speed(session)
}

/// Wrap an already permission-authorized Android USB fd. The fd remains owned
/// by UsbDeviceConnection and must outlive this session.
#[cfg(target_os = "android")]
pub fn open_android_fd(fd: i32, interface_number: u8, alternate_setting: u8) -> Result<i64, String> {
    if fd < 0 {
        return Err("Android USB connection returned an invalid file descriptor".into());
    }

    // Android apps cannot enumerate arbitrary /dev/bus/usb devices or sysfs
    // entries. The permission-authorized UsbDeviceConnection fd is sufficient:
    // tell libusb to skip discovery before creating a context, then wrap that fd.
    // Use the process-wide default option exactly once, before the first context.
    let no_device_discovery = *LIBUSB_NO_DEVICE_DISCOVERY.get_or_init(|| unsafe {
        libusb1_sys::libusb_set_option(
            std::ptr::null_mut(),
            2, // LIBUSB_OPTION_NO_DEVICE_DISCOVERY
            std::ptr::null_mut::<libc::c_void>(),
        )
    });
    if no_device_discovery != 0 {
        return Err(format!(
            "libusb_set_option(NO_DEVICE_DISCOVERY) failed with code {no_device_discovery}"
        ));
    }

    let mut context = std::ptr::null_mut();
    let init = unsafe { libusb1_sys::libusb_init(&mut context) };
    if init != 0 || context.is_null() {
        if !context.is_null() { unsafe { libusb1_sys::libusb_exit(context); } }
        return Err(format!("libusb_init failed with code {init}"));
    }

    let mut handle = std::ptr::null_mut();
    // libusb_wrap_sys_device takes a pointer-sized system handle, represented
    // by the fd value cast to the pointer type in libusb1-sys 0.7.
    let wrapped = unsafe {
        libusb1_sys::libusb_wrap_sys_device(
            context,
            fd as usize as *mut libc::c_int,
            &mut handle,
        )
    };
    if wrapped != 0 || handle.is_null() {
        unsafe { libusb1_sys::libusb_exit(context); }
        return Err(format!(
            "libusb_wrap_sys_device failed with code {wrapped}; this Android USB backend may not support fd wrapping"
        ));
    }

    // The Android kernel binds snd-usb-audio to AudioStreaming interfaces of
    // class-compliant DACs. Without detaching it, libusb_claim_interface fails
    // with LIBUSB_ERROR_BUSY (-6). Request automatic detach so that the kernel
    // driver is released on claim; this is a prerequisite for exclusive access.
    // Platform backends that do not support the option report
    // LIBUSB_ERROR_NOT_SUPPORTED, which is tolerated here and handled below.
    let _ = unsafe { libusb1_sys::libusb_set_auto_detach_kernel_driver(handle, 1) };

    let mut claimed =
        unsafe { libusb1_sys::libusb_claim_interface(handle, i32::from(interface_number)) };
    if claimed == LIBUSB_ERROR_BUSY {
        // Fallback: explicitly detach an active kernel driver, then retry once.
        let active =
            unsafe { libusb1_sys::libusb_kernel_driver_active(handle, i32::from(interface_number)) };
        if active == 1 {
            let detached = unsafe {
                libusb1_sys::libusb_detach_kernel_driver(handle, i32::from(interface_number))
            };
            if detached != 0 {
                unsafe {
                    libusb1_sys::libusb_close(handle);
                    libusb1_sys::libusb_exit(context);
                }
                return Err(format!(
                    "libusb_detach_kernel_driver failed with code {detached} after the interface was reported busy"
                ));
            }
            claimed =
                unsafe { libusb1_sys::libusb_claim_interface(handle, i32::from(interface_number)) };
        }
    }
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
        Arc::new(UsbSession { context, handle, interface_number }),
    );
    Ok(session)
}

#[cfg(target_os = "android")]
pub fn close_session(session: i64) -> Result<(), String> {
    if session <= 0 {
        return Err("Invalid USB session handle".into());
    }
    crate::usb_stream::close_session(session)
}

/// Remove the registry's session reference. Caller must hold the stream
/// lifecycle gate so start/seek cannot race this removal.
#[cfg(target_os = "android")]
pub(super) fn remove_session(session: i64) {
    sessions().lock().unwrap_or_else(|e| e.into_inner()).remove(&session);
}
