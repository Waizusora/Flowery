use jni::objects::JClass;
use jni::sys::{jboolean, jint, jlong, jlongArray, JNI_TRUE};
use jni::JNIEnv;
use std::cell::UnsafeCell;
use std::collections::HashMap;
use std::ffi::c_void;
use std::ptr;
use crate::pcm_format::PcmFormat;
use std::sync::{Arc, Mutex, OnceLock};
use std::sync::atomic::{AtomicBool, AtomicU64, AtomicI32, AtomicU32, Ordering};
use std::thread::{self, JoinHandle};
use std::time::Duration;
use rtrb::{Consumer, Producer};

#[repr(C)] struct AAudioStream(c_void);
#[repr(C)] struct AAudioStreamBuilder(c_void);
type ResultCode = i32;
type DataCallback = Option<unsafe extern "C" fn(*mut AAudioStream, *mut c_void, *mut c_void, i32) -> i32>;
type ErrorCallback = Option<unsafe extern "C" fn(*mut AAudioStream, *mut c_void, i32)>;

#[link(name = "aaudio")]
extern "C" {
    fn AAudio_createStreamBuilder(builder: *mut *mut AAudioStreamBuilder) -> ResultCode;
    fn AAudioStreamBuilder_delete(builder: *mut AAudioStreamBuilder);
    fn AAudioStreamBuilder_setDirection(builder: *mut AAudioStreamBuilder, direction: i32);

    fn AAudioStreamBuilder_setFormat(builder: *mut AAudioStreamBuilder, format: i32);
    fn AAudioStreamBuilder_setSampleRate(builder: *mut AAudioStreamBuilder, rate: i32);
    fn AAudioStreamBuilder_setChannelCount(builder: *mut AAudioStreamBuilder, channels: i32);
    fn AAudioStreamBuilder_setSharingMode(builder: *mut AAudioStreamBuilder, mode: i32);
    fn AAudioStreamBuilder_setPerformanceMode(builder: *mut AAudioStreamBuilder, mode: i32);


    fn AAudioStreamBuilder_setDataCallback(builder: *mut AAudioStreamBuilder, cb: DataCallback, data: *mut c_void);
    fn AAudioStreamBuilder_setErrorCallback(builder: *mut AAudioStreamBuilder, cb: ErrorCallback, data: *mut c_void);
    fn AAudioStreamBuilder_openStream(builder: *mut AAudioStreamBuilder, stream: *mut *mut AAudioStream) -> ResultCode;
    fn AAudioStream_close(stream: *mut AAudioStream) -> ResultCode;
    fn AAudioStream_requestStart(stream: *mut AAudioStream) -> ResultCode;
    fn AAudioStream_requestPause(stream: *mut AAudioStream) -> ResultCode;
    fn AAudioStream_requestStop(stream: *mut AAudioStream) -> ResultCode;
    fn AAudioStream_getState(stream: *mut AAudioStream) -> i32;
    fn AAudioStream_waitForStateChange(stream: *mut AAudioStream, input: i32, next: *mut i32, timeout: i64) -> ResultCode;
    fn AAudioStream_getSharingMode(stream: *mut AAudioStream) -> i32;
    fn AAudioStream_getDeviceId(stream: *mut AAudioStream) -> i32;
    fn AAudioStream_getSampleRate(stream: *mut AAudioStream) -> i32;
    fn AAudioStream_getChannelCount(stream: *mut AAudioStream) -> i32;
    fn AAudioStream_getFormat(stream: *mut AAudioStream) -> i32;
    fn AAudioStream_getPerformanceMode(stream: *mut AAudioStream) -> i32;
    fn AAudioStream_getXRunCount(stream: *mut AAudioStream) -> i32;
    fn AAudioStream_getBufferSizeInFrames(stream: *mut AAudioStream) -> i32;
    fn AAudioStream_getBufferCapacityInFrames(stream: *mut AAudioStream) -> i32;
    fn AAudioStream_getFramesPerBurst(stream: *mut AAudioStream) -> i32;
    fn AAudioStream_setBufferSizeInFrames(stream: *mut AAudioStream, frames: i32) -> i32;
}

const OK:i32=0; const INVALID_STATE:i32=-895; const DEVICE_SELECTION_UNAVAILABLE:i32=-896; const DEVICE_MISMATCH:i32=-897; const INVALID_FORMAT:i32=-898;

fn output_should_complete(eof: bool, track_eof: bool, buffered_frames: u64, active: bool) -> bool {
    (eof || track_eof) && buffered_frames == 0 && !active
}

/// Non-terminal notification for a queue owner when a track has ended but
/// buffered PCM remains; terminal completion is reported only after drain.
fn output_scheduler_status(track_eof: bool, complete: bool, boundary_notified: bool) -> Option<i32> {
    if complete { Some(if boundary_notified { 4 } else { 1 }) }
    else if track_eof { Some(3) }
    else { None }
}
fn producer_epoch_matches(current: u64, expected: u64) -> bool {
    current != 0 && current == expected
}

fn output_track_position_frames(played: u64, track_start: u64) -> u64 {
    played.saturating_sub(track_start)
}

fn output_identity_matches(
    actual_device_id: i32, actual_sharing_mode: i32,
    expected_device_id: i32, expected_sharing_mode: i32,
) -> bool {
    expected_device_id >= 0
        && matches!(expected_sharing_mode, SHARING_EXCLUSIVE | SHARING_SHARED)
        && actual_device_id == expected_device_id
        && actual_sharing_mode == expected_sharing_mode
}

/// Advances producer ownership without replacing the SPSC ring or discarding
/// PCM already queued by the previous track. The old worker must be joined
/// before the new producer reads the returned epoch.
fn attach_output_producer_for_next_track(
    engine: &mut Engine,
    persistent: bool,
    expected_rate: i32,
    expected_channels: i32,
    expected_format: i32,
    expected_device_id: i32,
    expected_sharing_mode: i32,
) -> Result<u64, i32> {
    if !persistent { return Err(INVALID_STATE); }
    let output = engine.output.as_ref().ok_or(INVALID_STATE)?;
    if output.rate != expected_rate
        || output.channels != expected_channels as usize
        || output.format != expected_format
    {
        return Err(INVALID_FORMAT);
    }
    if !output_identity_matches(
        engine.last_open[5] as i32,
        engine.last_open[6] as i32,
        expected_device_id,
        expected_sharing_mode,
    ) {
        return Err(DEVICE_MISMATCH);
    }
    let producer = engine.producer.as_ref().ok_or(INVALID_STATE)?;
    let bytes_per_frame = output.channels.checked_mul(output.bytes_per_sample).ok_or(INVALID_FORMAT)?;
    if bytes_per_frame == 0 { return Err(INVALID_FORMAT); }
    // The current ring contains only the previous track's remaining tail at
    // this ownership boundary. The next track's clock begins after that tail.
    let free_frames = producer.slots() / bytes_per_frame;
    let queued_tail_frames = output.capacity.saturating_sub(free_frames as u64);
    let played_frames = output.played.load(Ordering::Acquire);
    OUTPUT_TRACK_START_FRAME.store(
        played_frames.saturating_add(queued_tail_frames),
        Ordering::Release,
    );

    engine.producer_epoch = engine.producer_epoch.wrapping_add(1).max(1);
    let epoch = engine.producer_epoch;
    let output = engine.output.as_ref().ok_or(INVALID_STATE)?;
    output.eof.store(false, Ordering::Release);
    output.track_eof.store(false, Ordering::Release);
    output.active.store(true, Ordering::Release);
    output.error.store(0, Ordering::Release);
    // Keep the queued tail and played-frame counter intact across the handoff.
    Ok(epoch)
}

fn valid_output_format(rate: i32, channels: i32, format: i32) -> bool {
    if rate <= 0 || channels <= 0 {
        return false;
    }
    if format == FMT_FLOAT {
        return channels <= 2;
    }
    let (valid_bits, subslot_bytes) = match format {
        FMT_I16 => (16, 2),
        FMT_I24 => (24, 3),
        FMT_I32 => (32, 4),
        _ => return false,
    };
    PcmFormat {
        sample_rate: rate as u32,
        channels: channels as u16,
        valid_bits,
        subslot_bytes,
        little_endian: true,
    }.validate().is_ok()
}
const FMT_I16:i32=1; const FMT_FLOAT:i32=2; const FMT_I24:i32=3; const FMT_I32:i32=4;
const SHARING_EXCLUSIVE:i32=0; const SHARING_SHARED:i32=1;
const STATE_OPEN:i32=2; const STATE_STARTING:i32=3; const STATE_STARTED:i32=4;
const STATE_PAUSING:i32=5; const STATE_PAUSED:i32=6; const STATE_STOPPED:i32=10; const STATE_DISCONNECTED:i32=13;

struct Output {
    // Raw interleaved integer PCM bytes. The decoder's sample codes are never
    // normalized through floating point on the Android-managed output path.
    consumer: UnsafeCell<Consumer<u8>>, capacity:u64, channels:usize, format:i32, rate:i32,
    bytes_per_sample:usize,
    played:AtomicU64, volume:AtomicU32,
    active:AtomicBool, eof:AtomicBool, track_eof:AtomicBool, underruns:AtomicU64, error:AtomicI32,
}
impl Output {
    fn gain_q31(&self)->i64 {
        let gain = f32::from_bits(self.volume.load(Ordering::Relaxed)).clamp(0.0, 1.0);
        (gain as f64 * 2_147_483_648.0).round() as i64
    }
}
struct Engine { stream:*mut AAudioStream, output:Option<Box<Output>>, producer:Option<Producer<u8>>, producer_epoch:u64, last_open:[i64;11] }
unsafe impl Send for Engine {}
impl Engine {
    fn new()->Self { Self{stream:ptr::null_mut(),output:None,producer:None,producer_epoch:0,last_open:[0;11]} }
    unsafe fn stop(&mut self) {
        if !self.stream.is_null() {
            let s=AAudioStream_getState(self.stream);
            if s!=STATE_DISCONNECTED && s!=STATE_STOPPED && s!=12 {
                let _=AAudioStream_requestStop(self.stream);
                let mut next=s;
                let _=AAudioStream_waitForStateChange(self.stream,s,&mut next,500_000_000);
            }
            let _=AAudioStream_close(self.stream); self.stream=ptr::null_mut();
        }
        self.producer_epoch = self.producer_epoch.wrapping_add(1);
        self.producer=None;
        self.output=None;
    }
    unsafe fn open(&mut self, out:&mut Output, rate:i32, sharing:i32, device:i32)->i32 {
        let mut builder=ptr::null_mut();
        let mut result=AAudio_createStreamBuilder(&mut builder);
        if result!=OK || builder.is_null(){return result;}
        AAudioStreamBuilder_setDirection(builder,0);
        if device>0 {
            let name=std::ffi::CString::new("AAudioStreamBuilder_setDeviceId").unwrap();
            let symbol=libc::dlsym(libc::RTLD_DEFAULT,name.as_ptr());
            if symbol.is_null() {
                AAudioStreamBuilder_delete(builder);
                self.last_open[10]=5; // Explicit device selection is unavailable on this Android runtime.
                return DEVICE_SELECTION_UNAVAILABLE;
            }
            let setter:unsafe extern "C" fn(*mut AAudioStreamBuilder,i32)=std::mem::transmute(symbol);
            setter(builder,device);
        }
        AAudioStreamBuilder_setFormat(builder,out.format);
        AAudioStreamBuilder_setSampleRate(builder,rate);
        AAudioStreamBuilder_setChannelCount(builder,out.channels as i32);
        AAudioStreamBuilder_setSharingMode(builder,sharing);
        AAudioStreamBuilder_setPerformanceMode(builder,10); // AAudio PERFORMANCE_MODE_NONE (Balanced).
        let data=(out as *mut Output).cast::<c_void>();
        AAudioStreamBuilder_setDataCallback(builder,Some(data_callback),data);
        AAudioStreamBuilder_setErrorCallback(builder,Some(error_callback),data);
        result=AAudioStreamBuilder_openStream(builder,&mut self.stream);
        AAudioStreamBuilder_delete(builder);
        if result!=OK {return result;}
        let actual_device=AAudioStream_getDeviceId(self.stream);
        self.last_open[5]=actual_device as i64;
        if device>0 && actual_device!=device {
            let _=AAudioStream_close(self.stream);
            self.stream=ptr::null_mut();
            self.last_open[10]=6; // Android opened a different output than the requested device.
            return DEVICE_MISMATCH;
        }
        if AAudioStream_getSharingMode(self.stream)!=sharing ||
           AAudioStream_getFormat(self.stream)!=out.format ||
           AAudioStream_getChannelCount(self.stream)!=out.channels as i32 ||
           (rate>0 && AAudioStream_getSampleRate(self.stream)!=rate) {
            let _=AAudioStream_close(self.stream); self.stream=ptr::null_mut(); return INVALID_FORMAT;
        }
        out.rate=AAudioStream_getSampleRate(self.stream); OK
    }
}
static ENGINE:OnceLock<Mutex<Engine>>=OnceLock::new();
static OUTPUT_PERSISTENT: AtomicBool = AtomicBool::new(false);
static OUTPUT_TRACK_START_FRAME: AtomicU64 = AtomicU64::new(0);
fn engine()-> &'static Mutex<Engine>{ENGINE.get_or_init(||Mutex::new(Engine::new()))}

/// Whether a completed track may release its session while retaining the device output.
pub(crate) fn output_persistence_enabled() -> bool {
    OUTPUT_PERSISTENT.load(Ordering::Acquire)
}

/// Replaces the SPSC ring only while the AAudio callback is stopped.
fn reset_output_ring(engine: &mut Engine, expected_rate: i32, expected_channels: i32, expected_format: i32) -> Result<(), i32> {
    let output = engine.output.as_mut().ok_or(INVALID_STATE)?;
    if output.rate != expected_rate || output.channels != expected_channels as usize || output.format != expected_format {
        return Err(INVALID_FORMAT);
    }
    let bytes_per_frame = output.channels.checked_mul(output.bytes_per_sample).ok_or(INVALID_FORMAT)?;
    let capacity_bytes = usize::try_from(output.capacity)
        .ok().and_then(|frames| frames.checked_mul(bytes_per_frame))
        .filter(|bytes| *bytes > 0).ok_or(INVALID_FORMAT)?;
    let (producer, consumer) = crate::pcm_transport::new_pcm_byte_ring(capacity_bytes)
        .map_err(|_| INVALID_FORMAT)?;
    // Caller holds the engine lock and has verified the stream is not running;
    // therefore the callback cannot concurrently access this consumer endpoint.
    unsafe { *output.consumer.get() = consumer; }
    output.eof.store(false, Ordering::Release);
    output.track_eof.store(false, Ordering::Release);
    output.active.store(true, Ordering::Release);
    output.error.store(0, Ordering::Release);
    output.played.store(0, Ordering::Release);
    OUTPUT_TRACK_START_FRAME.store(0, Ordering::Release);
    engine.producer_epoch = engine.producer_epoch.wrapping_add(1).max(1);
    engine.producer = Some(producer);
    Ok(())
}

/// Returns free PCM frames in the bounded output ring.
/// Returns queued frames, free ring capacity, and the producer's latency budget.
pub(crate) fn output_producer_epoch() -> Option<u64> {
    let engine = engine().lock().unwrap_or_else(|poisoned| poisoned.into_inner());
    if engine.output.is_some() && engine.producer.is_some() { Some(engine.producer_epoch) } else { None }
}

pub(crate) fn output_queue_snapshot(epoch: u64) -> Option<(usize, u32)> {
    let engine = engine().lock().unwrap_or_else(|poisoned| poisoned.into_inner());
    if !producer_epoch_matches(engine.producer_epoch, epoch) { return None; }
    let output = engine.output.as_ref()?;
    let producer = engine.producer.as_ref()?;
    let bytes_per_frame = output.channels.checked_mul(output.bytes_per_sample)?;
    if bytes_per_frame == 0 || output.rate <= 0 { return None; }
    let free = producer.slots() / bytes_per_frame;
    let queued = (output.capacity as usize).saturating_sub(free);
    Some((queued, output.rate as u32))
}

pub(crate) fn output_queue_budget(epoch: u64) -> Option<(usize, usize, usize)> {
    let engine = engine().lock().unwrap_or_else(|poisoned| poisoned.into_inner());
    if !producer_epoch_matches(engine.producer_epoch, epoch) { return None; }
    let output = engine.output.as_ref()?;
    let producer = engine.producer.as_ref()?;
    let bytes_per_frame = output.channels.checked_mul(output.bytes_per_sample)?;
    if bytes_per_frame == 0 { return None; }
    let free = producer.slots() / bytes_per_frame;
    let queued = (output.capacity as usize).saturating_sub(free);
    let target = crate::playback_policy::target_queue_frames(
        output.rate,
        output.capacity.min(i32::MAX as u64) as i32,
    );
    Some((queued, free.min(output.capacity as usize), target))
}

/// Copies complete interleaved PCM frames into the bounded SPSC transport.
pub(crate) fn write_output_pcm_bytes(epoch: u64, samples: &[u8]) -> Result<usize, i32> {
    let mut engine = engine().lock().unwrap_or_else(|poisoned| poisoned.into_inner());
    if !producer_epoch_matches(engine.producer_epoch, epoch) { return Err(INVALID_STATE); }
    let output = engine.output.as_ref().ok_or(INVALID_STATE)?;
    let bytes_per_frame = output.channels.checked_mul(output.bytes_per_sample).ok_or(INVALID_FORMAT)?;
    if bytes_per_frame == 0 || samples.len() % bytes_per_frame != 0 {
        return Err(INVALID_FORMAT);
    }
    let producer = engine.producer.as_mut().ok_or(INVALID_STATE)?;
    let (written, _) = producer.push_partial_slice(samples);
    Ok(written.len() / bytes_per_frame)
}

unsafe fn wait_stream_state(stream:*mut AAudioStream, transient:i32, target:i32)->i32 {
    let mut state=AAudioStream_getState(stream);
    for _ in 0..4 {
        if state==target{return OK;}
        if state==STATE_DISCONNECTED{return -899;}
        if state!=transient{return INVALID_STATE;}
        let mut next=state;
        let result=AAudioStream_waitForStateChange(stream,state,&mut next,1_000_000_000);
        if result!=OK{return result;}
        state=next;
    }
    if state==target{OK}else{-889}
}

unsafe extern "C" fn error_callback(_: *mut AAudioStream, data:*mut c_void, error:i32){
    if !data.is_null(){let o=&*(data as *const Output);o.error.store(error,Ordering::Release);o.active.store(false,Ordering::Release);}
}
unsafe extern "C" fn data_callback(_: *mut AAudioStream, data:*mut c_void, audio:*mut c_void, frames:i32)->i32{
    if data.is_null() || audio.is_null() || frames<=0{return 0;}
    // AAudio serializes data callbacks for a stream; the callback is the sole
    // consumer of this SPSC endpoint.
    let o=&*(data as *const Output);
    let requested=frames as usize;
    let bytes_per_frame=o.channels*o.bytes_per_sample;
    let requested_bytes=requested*bytes_per_frame;
    let destination=std::slice::from_raw_parts_mut(audio as *mut u8,requested_bytes);
    let consumer = &mut *o.consumer.get();
    let (popped, _) = consumer.pop_partial_slice(destination);
    let byte_count=popped.len();
    let count=byte_count/bytes_per_frame;
    // Integer output keeps its byte-preserving unity-gain path. MP3's
    // decoder-native float PCM uses a separate float gain path.
    let gain=o.gain_q31();
    if gain != 2_147_483_648_i64 {
        if o.format == FMT_FLOAT {
            let output=std::slice::from_raw_parts_mut(audio as *mut f32,count*o.channels);
            let scale=(gain as f64 / 2_147_483_648.0) as f32;
            for sample in output { *sample *= scale; }
        } else {
            let valid_bits=match o.format { FMT_I16=>16, FMT_I24=>24, FMT_I32=>32, _=>0 };
            let output=std::slice::from_raw_parts_mut(audio as *mut u8,byte_count);
            if crate::pcm_format::apply_integer_pcm_gain(
                output, valid_bits, count*o.channels, gain,
            ).is_err() {
                o.error.store(INVALID_FORMAT, Ordering::Release);
                ptr::write_bytes(audio as *mut u8, 0, requested_bytes);
                o.active.store(false, Ordering::Release);
                return 1;
            }
        }
    }
    let remaining=requested_bytes-byte_count;
    if remaining>0 {
        ptr::write_bytes((audio as *mut u8).add(byte_count),0,remaining);
        if !o.eof.load(Ordering::Acquire){o.underruns.fetch_add(1,Ordering::Relaxed);}
    }
    o.played.fetch_add(count as u64,Ordering::Relaxed);
    if remaining>0 && o.eof.load(Ordering::Acquire) && consumer.slots()==0 {
        o.active.store(false,Ordering::Release);
        1
    }else{0}
}


struct OutputScheduler {
    stop: Arc<AtomicBool>,
    status: Arc<AtomicI32>,
    thread: JoinHandle<()>,
}
static OUTPUT_SCHEDULERS: OnceLock<Mutex<HashMap<jlong, OutputScheduler>>> = OnceLock::new();
static OUTPUT_SCHEDULER_LIFECYCLE: OnceLock<Mutex<()>> = OnceLock::new();

fn output_schedulers() -> &'static Mutex<HashMap<jlong, OutputScheduler>> {
    OUTPUT_SCHEDULERS.get_or_init(|| Mutex::new(HashMap::new()))
}

fn output_scheduler_lifecycle() -> &'static Mutex<()> {
    OUTPUT_SCHEDULER_LIFECYCLE.get_or_init(|| Mutex::new(()))
}

fn stop_scheduler_worker(worker: OutputScheduler) {
    worker.stop.store(true, Ordering::Release);
    if worker.thread.thread().id() != thread::current().id() {
        let _ = worker.thread.join();
    }
}

fn take_scheduler_for_session(
    schedulers: &mut HashMap<jlong, OutputScheduler>,
    session_handle: jlong,
) -> Option<OutputScheduler> {
    schedulers.remove(&session_handle)
}

fn take_all_schedulers(
    schedulers: &mut HashMap<jlong, OutputScheduler>,
) -> Vec<OutputScheduler> {
    schedulers.drain().map(|(_, worker)| worker).collect()
}

fn scheduler_status_from_registry(
    schedulers: &HashMap<jlong, OutputScheduler>,
    session_handle: jlong,
) -> jint {
    schedulers.get(&session_handle)
        .map(|worker| worker.status.load(Ordering::Acquire))
        // A missing scheduler is terminal for this session, not a global
        // registry error that could be confused with another session's worker.
        .unwrap_or(2)
}

/// Removes and joins only the scheduler owned by the supplied playback session.
fn stop_output_scheduler_locked(session_handle: jlong) {
    let worker = take_scheduler_for_session(
        &mut output_schedulers().lock().unwrap_or_else(|p| p.into_inner()),
        session_handle,
    );
    if let Some(worker) = worker {
        stop_scheduler_worker(worker);
    }
}

/// Full output replacement/shutdown invalidates every scheduler using the shared engine.
fn stop_all_output_schedulers_locked() {
    let workers = {
        let mut schedulers = output_schedulers().lock().unwrap_or_else(|p| p.into_inner());
        take_all_schedulers(&mut schedulers)
    };
    for worker in workers {
        stop_scheduler_worker(worker);
    }
}

fn stop_scheduler_for_session(session_handle: jlong) {
    let _lifecycle = output_scheduler_lifecycle()
        .lock()
        .unwrap_or_else(|p| p.into_inner());
    stop_output_scheduler_locked(session_handle);
}

fn stop_output_scheduler() {
    let _lifecycle = output_scheduler_lifecycle()
        .lock()
        .unwrap_or_else(|p| p.into_inner());
    stop_all_output_schedulers_locked();
}

fn output_scheduler_status_for_session(session_handle: jlong) -> jint {
    scheduler_status_from_registry(
        &output_schedulers().lock().unwrap_or_else(|p| p.into_inner()),
        session_handle,
    )
}

fn output_scheduler_tick(low_watermark: u64, phase: i32, start_requested: &mut bool) -> Result<(bool, bool), i32> {
    let e = engine().lock().unwrap_or_else(|p| p.into_inner());
    if e.stream.is_null() { return Err(INVALID_STATE); }
    let output = e.output.as_ref().ok_or(INVALID_STATE)?;
    let error = output.error.load(Ordering::Acquire);
    if error != 0 { return Err(error); }
    let bytes_per_frame = output.channels * output.bytes_per_sample;
    let buffered = if bytes_per_frame == 0 { 0 } else {
        (output.capacity as u64).saturating_sub((e.producer.as_ref().ok_or(INVALID_STATE)?.slots() / bytes_per_frame) as u64)
    };
    let eof = output.eof.load(Ordering::Acquire);
    let track_eof = output.track_eof.load(Ordering::Acquire);
    let active = output.active.load(Ordering::Acquire);
    unsafe {
        let state = AAudioStream_getState(e.stream);
        if state == STATE_DISCONNECTED { return Err(-899); }
        if state == STATE_STARTED {
            *start_requested = false;
        }
        if phase == 2 && (state == STATE_OPEN || state == STATE_PAUSED || state == STATE_STOPPED) &&
            (buffered >= low_watermark || eof) && !*start_requested {
            let result = AAudioStream_requestStart(e.stream);
            if result != OK { return Err(result); }
            *start_requested = true;
        }
        let current_state = AAudioStream_getState(e.stream);
        if current_state == STATE_STOPPED && !eof && !track_eof && buffered == 0 { return Ok((false, false)); }
        let xruns = AAudioStream_getXRunCount(e.stream);
        if xruns > 0 {
            let capacity = AAudioStream_getBufferCapacityInFrames(e.stream);
            let current = AAudioStream_getBufferSizeInFrames(e.stream);
            let burst = AAudioStream_getFramesPerBurst(e.stream).max(1);
            if current > 0 && capacity > current {
                let _ = AAudioStream_setBufferSizeInFrames(e.stream, (current + burst).min(capacity));
            }
        }
    }
    Ok((output_should_complete(eof, track_eof, buffered, active), track_eof))
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeStartOutputScheduler(
    mut env: JNIEnv, _class: JClass, session_handle: jlong, low_watermark: jint,
) -> jint {
    if session_handle <= 0 || low_watermark <= 0 {
        let _ = env.throw_new("java/lang/IllegalArgumentException", "Invalid AAudio output scheduler configuration");
        return -1;
    }
    let Some(session) = crate::playback_session::session_ref(session_handle) else {
        let _ = env.throw_new("java/lang/IllegalArgumentException", "Invalid playback session handle");
        return -1;
    };
    if session.route != 0 || session.cancelled.load(Ordering::Acquire) {
        let _ = env.throw_new("java/lang/IllegalStateException", "Playback session is not an active AAudio route");
        return -1;
    }
    // Serialize replacement through registry insertion so concurrent starts
    // cannot detach a live scheduler's JoinHandle or leave duplicate schedulers.
    let _lifecycle = output_scheduler_lifecycle()
        .lock()
        .unwrap_or_else(|p| p.into_inner());
    stop_output_scheduler_locked(session_handle);
    let stop = Arc::new(AtomicBool::new(false));
    let status = Arc::new(AtomicI32::new(0));
    let thread_stop = Arc::clone(&stop);
    let thread_status = Arc::clone(&status);
    let worker_session = Arc::clone(&session);
    let watermark = low_watermark as u64;
    let worker = thread::Builder::new().name("flowery-aaudio-output".into()).spawn(move || {
        let mut start_requested = false;
        let mut boundary_notified = false;
        loop {
            if thread_stop.load(Ordering::Acquire) || worker_session.cancelled.load(Ordering::Acquire) {
                thread_status.store(2, Ordering::Release);
                break;
            }
            let phase = worker_session.phase.load(Ordering::Acquire) as i32;
            if phase == 5 {
                thread_status.store(1, Ordering::Release);
                break;
            }
            if phase == 6 || phase == 7 {
                thread_status.store(2, Ordering::Release);
                break;
            }
            let pending_seek = worker_session.seek_request
                .lock()
                .unwrap_or_else(|error| error.into_inner())
                .take();
            if let Some(request) = pending_seek {
                match crate::streaming::process_aaudio_seek(Arc::clone(&worker_session), request) {
                    Ok(_) => {
                        start_requested = false;
                    }
                    Err(error) => {
                        *worker_session.seek_error.lock().unwrap_or_else(|poison| poison.into_inner()) = error;
                        worker_session.error_code.store(-903, Ordering::Release);
                        crate::playback_session::transition_session_phase(&worker_session, 7);
                        thread_status.store(-903, Ordering::Release);
                        break;
                    }
                }
                continue;
            }
            if phase == 2 {
                // The session scheduler owns decode-worker health monitoring. Kotlin
                // observes only this scheduler's terminal status, not a second worker.
                match crate::streaming::stream_pump_status(worker_session.decoder_handle) {
                    Some((0 | 1, _)) => {}
                    Some((2, worker_epoch))
                        if worker_epoch != worker_session.epoch.load(Ordering::Acquire) => {
                            // Do not feed the old output ring while the new seek request
                            // is being published and consumed by the scheduler.
                            thread::sleep(Duration::from_millis(1));
                            continue;
                        }
                    Some((error, _)) => {
                        worker_session.error_code.store(if error < 0 { error } else { -901 }, Ordering::Release);
                        crate::playback_session::transition_session_phase(&worker_session, 7);
                        thread_status.store(if error < 0 { error } else { -901 }, Ordering::Release);
                        break;
                    }
                    None => {
                        worker_session.error_code.store(-902, Ordering::Release);
                        crate::playback_session::transition_session_phase(&worker_session, 7);
                        thread_status.store(-902, Ordering::Release);
                        break;
                    }
                }
                match output_scheduler_tick(watermark, phase, &mut start_requested) {
                    Ok((complete, track_eof)) => {
                        // Publish an early queue-boundary notification as soon as the
                        // decoder reaches EOF while buffered PCM is still draining.
                        // Status 3 is non-terminal: single-track playback continues
                        // waiting for status 1, while a queue owner may hand off now.
                        match output_scheduler_status(track_eof, complete, boundary_notified) {
                            Some(3) => {
                                boundary_notified = true;
                                thread_status.store(3, Ordering::Release);
                            }
                            Some(1 | 4) => {
                                if crate::playback_session::transition_session_phase(&worker_session, 4) &&
                                    crate::playback_session::transition_session_phase(&worker_session, 5) {
                                    thread_status.store(if boundary_notified { 4 } else { 1 }, Ordering::Release);
                                    break;
                                }
                            }
                            _ => {}
                        }
                    }
                    Err(error) => {
                        worker_session.error_code.store(if error < 0 { error } else { -error }, Ordering::Release);
                        crate::playback_session::transition_session_phase(&worker_session, 7);
                        thread_status.store(if error < 0 { error } else { -error }, Ordering::Release);
                        break;
                    }
                }
            }
            thread::sleep(Duration::from_millis(4));
        }
    });
    match worker {
        Ok(thread) => {
            let session_gate = session.gate.lock().unwrap_or_else(|error| error.into_inner());
            if session.cancelled.load(Ordering::Acquire) ||
                matches!(session.phase.load(Ordering::Acquire) as i32, 5 | 6 | 7) {
                drop(session_gate);
                stop.store(true, Ordering::Release);
                let _ = thread.join();
                let _ = env.throw_new("java/lang/IllegalStateException", "Playback session ended while the AAudio scheduler was starting");
                return -1;
            }
            output_schedulers().lock().unwrap_or_else(|p| p.into_inner())
                .insert(session_handle, OutputScheduler { stop, status, thread });
            drop(session_gate);
            0
        }
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalStateException", error.to_string());
            -1
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeGetOutputSchedulerStatus(
    _env: JNIEnv, _class: JClass, session_handle: jlong,
) -> jint {
    output_scheduler_status_for_session(session_handle)
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeStopOutputScheduler(
    _env: JNIEnv, _class: JClass, session_handle: jlong,
) {
    stop_scheduler_for_session(session_handle);
}

#[no_mangle] pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeStartStreamOutput(mut env:JNIEnv,_:JClass,rate:jint,channels:jint,volume:f32,device:jint,exclusive:jboolean,fail_on_shared:jboolean,format:jint,capacity:jint)->jint{
    // A fresh device open always starts a new output ownership scope.
    OUTPUT_PERSISTENT.store(false, Ordering::Release);
    if !(channels==1||channels==2) || capacity<2048 || !valid_output_format(rate,channels,format){return INVALID_FORMAT;}
    let mut cap=1u64;while cap<capacity as u64{cap<<=1;}
    let bytes_per_sample=match format{FMT_I16=>2,FMT_FLOAT=>4,FMT_I24=>3,FMT_I32=>4,_=>return INVALID_FORMAT};
    let ring_bytes=(cap as usize).saturating_mul(channels as usize).saturating_mul(bytes_per_sample);
    let (producer, consumer)=match crate::pcm_transport::new_pcm_byte_ring(ring_bytes) { Ok(ring)=>ring, Err(_)=>return INVALID_FORMAT };
    let mut out=Box::new(Output{consumer:UnsafeCell::new(consumer),capacity:cap,channels:channels as usize,format,rate,bytes_per_sample,played:AtomicU64::new(0),volume:AtomicU32::new(volume.clamp(0.0,1.0).to_bits()),active:AtomicBool::new(false),eof:AtomicBool::new(false),track_eof:AtomicBool::new(false),underruns:AtomicU64::new(0),error:AtomicI32::new(0)});
    stop_output_scheduler();
    let mut e=engine().lock().unwrap_or_else(|p|p.into_inner());unsafe{e.stop();}
    let sharing=if exclusive==JNI_TRUE{SHARING_EXCLUSIVE}else{SHARING_SHARED};
    e.last_open=[device as i64,sharing as i64,rate as i64,channels as i64,INVALID_STATE as i64,0,-1,0,0,-1,1];
    let mut result=unsafe{e.open(&mut out,rate,sharing,device)};
    e.last_open[4]=result as i64;
    let mut actual_sharing=sharing;
    if result!=OK && sharing==SHARING_EXCLUSIVE && fail_on_shared!=JNI_TRUE {
        unsafe{e.stop();}
        actual_sharing=SHARING_SHARED;
        result=unsafe{e.open(&mut out,rate,actual_sharing,device)};
        e.last_open[4]=result as i64;
        e.last_open[10]=4; // Exclusive open failed; shared retry was attempted.
    } else if result!=OK && sharing==SHARING_EXCLUSIVE && fail_on_shared==JNI_TRUE {
        e.last_open[10]=7; // Exclusive mode is mandatory; shared fallback is prohibited.
    }
    if result!=OK{unsafe{e.stop();}return result;}
    out.active.store(true,Ordering::Release);e.last_open[5]=unsafe{AAudioStream_getDeviceId(e.stream)} as i64;e.last_open[6]=actual_sharing as i64;e.last_open[7]=out.rate as i64;e.last_open[8]=channels as i64;e.last_open[9]=format as i64;if e.last_open[10]!=4{e.last_open[10]=0;}e.output=Some(out);OUTPUT_TRACK_START_FRAME.store(0,Ordering::Release);e.producer_epoch=e.producer_epoch.wrapping_add(1).max(1);e.producer=Some(producer);let _=&mut env;OK
}
#[no_mangle] pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeStartStreamPlayback(_:JNIEnv,_:JClass)->jint{
 let e=engine().lock().unwrap_or_else(|p|p.into_inner());if e.output.is_none()||e.stream.is_null(){return INVALID_STATE;}unsafe{let s=AAudioStream_getState(e.stream);if s==STATE_STARTED{return OK;}if s!=STATE_OPEN&&s!=STATE_PAUSED&&s!=STATE_STOPPED{return INVALID_STATE;}AAudioStream_requestStart(e.stream)}
}

/// Enables/disables the explicit queue-level lease that keeps AAudio open
/// after an individual decoder session is closed.
#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeSetOutputPersistence(
    _env: JNIEnv, _class: JClass, enabled: jboolean,
) {
    OUTPUT_PERSISTENT.store(enabled == JNI_TRUE, Ordering::Release);
}

/// Installs a fresh empty SPSC ring for the next same-format track.
/// Must be called after the previous session is closed and its callback stopped.
/// Invalidates the previous producer epoch while preserving queued PCM and
/// the running AAudio callback. This is the same-signature gapless handoff path.
#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeAttachStreamProducerForNextTrack(
    mut env: JNIEnv, _class: JClass, expected_rate: jint, expected_channels: jint, expected_format: jint,
    expected_device_id: jint, expected_sharing_mode: jint,
) -> jint {
    if !valid_output_format(expected_rate, expected_channels, expected_format) {
        let _ = env.throw_new("java/lang/IllegalArgumentException", "Invalid expected PCM signature for producer handoff");
        return INVALID_FORMAT;
    }
    if !OUTPUT_PERSISTENT.load(Ordering::Acquire) {
        let _ = env.throw_new("java/lang/IllegalStateException", "Persistent AAudio output lease is not enabled");
        return INVALID_STATE;
    }
    stop_output_scheduler();
    let mut e = engine().lock().unwrap_or_else(|poisoned| poisoned.into_inner());
    if e.stream.is_null() || e.output.is_none() {
        let _ = env.throw_new("java/lang/IllegalStateException", "Persistent AAudio output is not open");
        return INVALID_STATE;
    }
    let (actual_device_id, actual_sharing_mode, state) = unsafe {
        (AAudioStream_getDeviceId(e.stream), AAudioStream_getSharingMode(e.stream), AAudioStream_getState(e.stream))
    };
    if !output_identity_matches(actual_device_id, actual_sharing_mode, expected_device_id, expected_sharing_mode) {
        let _ = env.throw_new("java/lang/IllegalStateException", "Persistent AAudio device or sharing mode does not match the requested queue output");
        return DEVICE_MISMATCH;
    }
    if state == STATE_DISCONNECTED {
        let _ = env.throw_new("java/lang/IllegalStateException", "AAudio output disconnected before producer handoff");
        return INVALID_STATE;
    }
    match attach_output_producer_for_next_track(
        &mut e, OUTPUT_PERSISTENT.load(Ordering::Acquire), expected_rate, expected_channels,
        expected_format, expected_device_id, expected_sharing_mode,
    ) {
        Ok(_) => OK,
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalStateException", "Unable to attach the next AAudio producer");
            error
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativePrepareStreamOutputForNextTrack(
    mut env: JNIEnv, _class: JClass, expected_rate: jint, expected_channels: jint, expected_format: jint,
    expected_device_id: jint, expected_sharing_mode: jint,
) -> jint {
    if !valid_output_format(expected_rate, expected_channels, expected_format) {
        let _ = env.throw_new("java/lang/IllegalArgumentException", "Invalid expected PCM signature for persistent AAudio output");
        return INVALID_FORMAT;
    }
    if !OUTPUT_PERSISTENT.load(Ordering::Acquire) {
        let _ = env.throw_new("java/lang/IllegalStateException", "Persistent AAudio output lease is not enabled");
        return INVALID_STATE;
    }
    stop_output_scheduler();
    let mut e = engine().lock().unwrap_or_else(|poisoned| poisoned.into_inner());
    if e.stream.is_null() || e.output.is_none() {
        let _ = env.throw_new("java/lang/IllegalStateException", "Persistent AAudio output is not open");
        return INVALID_STATE;
    }
    let (actual_device_id, actual_sharing_mode) = unsafe {
        (AAudioStream_getDeviceId(e.stream), AAudioStream_getSharingMode(e.stream))
    };
    if !output_identity_matches(
        actual_device_id, actual_sharing_mode, expected_device_id, expected_sharing_mode,
    ) {
        let _ = env.throw_new("java/lang/IllegalStateException", "Persistent AAudio device or sharing mode does not match the requested queue output");
        return DEVICE_MISMATCH;
    }
    let state = unsafe { AAudioStream_getState(e.stream) };
    if state != STATE_STOPPED && state != STATE_OPEN && state != STATE_PAUSED {
        let _ = env.throw_new("java/lang/IllegalStateException", "AAudio callback must be stopped before replacing the PCM ring");
        return INVALID_STATE;
    }
    match reset_output_ring(&mut e, expected_rate, expected_channels, expected_format) {
        Ok(()) => OK,
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalStateException", "Unable to reset AAudio PCM ring");
            error
        }
    }
}

#[no_mangle] pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeFlushStreamOutput(_:JNIEnv,_:JClass,position:jlong,start:jboolean)->jint{
 let result=flush_output_for_seek(position);
 if result!=OK{return result;}
 if start==JNI_TRUE{
  let e=engine().lock().unwrap_or_else(|p|p.into_inner());
  unsafe{AAudioStream_requestStart(e.stream)}
 }else{OK}
}

/// Pauses the native stream and atomically discards buffered samples for a seek.
pub(crate) fn flush_output_for_seek(position: i64) -> i32 {
    if position < 0 { return INVALID_STATE; }
    let mut e = engine().lock().unwrap_or_else(|p| p.into_inner());
    if e.output.is_none() || e.stream.is_null() { return INVALID_STATE; }
    unsafe {
        let mut state = AAudioStream_getState(e.stream);
        if state == STATE_DISCONNECTED { return -899; }
        if state == STATE_STARTED {
            let result = AAudioStream_requestPause(e.stream);
            if result != OK { return result; }
            let result = wait_stream_state(e.stream, STATE_PAUSING, STATE_PAUSED);
            if result != OK { return result; }
            state = STATE_PAUSED;
        }
        if state == STATE_PAUSING {
            let result = wait_stream_state(e.stream, STATE_PAUSING, STATE_PAUSED);
            if result != OK { return result; }
            state = STATE_PAUSED;
        }
        if state != STATE_PAUSED && state != STATE_OPEN && state != STATE_STOPPED {
            return INVALID_STATE;
        }
    }
    let output = e.output.as_ref().unwrap();
    let consumer = unsafe { &mut *output.consumer.get() };
    let mut discarded = [0u8; 4096];
    while !consumer.is_empty() {
        let (popped, _) = consumer.pop_partial_slice(&mut discarded);
        if popped.is_empty() { break; }
    }
    output.played.store(position as u64, Ordering::Release);
    OUTPUT_TRACK_START_FRAME.store(0, Ordering::Release);
    output.eof.store(false, Ordering::Release);
    output.active.store(true, Ordering::Release);
    OK
}

/// Signals end-of-stream to the AAudio callback without crossing JNI.
/// Marks only the current decoder producer as finished. The AAudio device
/// callback remains alive and may be fed by the next queue item.
pub(crate) fn signal_stream_track_eof(epoch: u64) -> bool {
    let engine = engine().lock().unwrap_or_else(|poisoned| poisoned.into_inner());
    if !producer_epoch_matches(engine.producer_epoch, epoch) { return false; }
    if let Some(output) = engine.output.as_ref() {
        output.track_eof.store(true, Ordering::Release);
        output.active.store(false, Ordering::Release);
        return true;
    }
    false
}

pub(crate) fn signal_stream_eof(epoch: u64) -> bool {
    let engine = engine().lock().unwrap_or_else(|poisoned| poisoned.into_inner());
    if !producer_epoch_matches(engine.producer_epoch, epoch) { return false; }
    if let Some(output) = engine.output.as_ref() {
        output.eof.store(true, Ordering::Release);
        return true;
    }
    false
}

#[no_mangle] pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeSetStreamEof(_:JNIEnv,_:JClass){if let Some(o)=engine().lock().unwrap_or_else(|p|p.into_inner()).output.as_ref(){o.eof.store(true,Ordering::Release);}}
#[no_mangle] pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeGetStreamBufferedFrames(_:JNIEnv,_:JClass)->jint{let e=engine().lock().unwrap_or_else(|p|p.into_inner());match (e.output.as_ref(),e.producer.as_ref()){(Some(o),Some(p))=>{let bpf=o.channels*o.bytes_per_sample;if bpf==0{0}else{o.capacity.saturating_sub((p.slots()/bpf) as u64).min(i32::MAX as u64) as i32}},_=>0}}
#[no_mangle] pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeGetOutputPositionFrames(_:JNIEnv,_:JClass)->jlong{engine().lock().unwrap_or_else(|p|p.into_inner()).output.as_ref().map(|o|output_track_position_frames(o.played.load(Ordering::Acquire),OUTPUT_TRACK_START_FRAME.load(Ordering::Acquire)) as i64).unwrap_or(0)}
#[no_mangle] pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeSetOutputVolume(_:JNIEnv,_:JClass,v:f32){if let Some(o)=engine().lock().unwrap_or_else(|p|p.into_inner()).output.as_ref(){o.volume.store(v.clamp(0.0,1.0).to_bits(),Ordering::Relaxed);}}
pub(crate) fn pause_output_for_session() -> Option<i32> {
    let e=engine().lock().unwrap_or_else(|p|p.into_inner());
    if e.stream.is_null() { return None; }
    Some(unsafe {
        let state=AAudioStream_getState(e.stream);
        if state==STATE_OPEN || state==STATE_PAUSED { OK }
        else if state==STATE_STARTED {
            let result=AAudioStream_requestPause(e.stream);
            if result!=OK { result } else { wait_stream_state(e.stream,STATE_PAUSING,STATE_PAUSED) }
        } else if state==STATE_PAUSING {
            wait_stream_state(e.stream,STATE_PAUSING,STATE_PAUSED)
        } else if state==STATE_STARTING {
            let mut next=state;
            let result=AAudioStream_waitForStateChange(e.stream,state,&mut next,1_000_000_000);
            if result!=OK { result }
            else if next==STATE_PAUSED { OK }
            else if next==STATE_STARTED {
                let pause_result=AAudioStream_requestPause(e.stream);
                if pause_result!=OK { pause_result }
                else { wait_stream_state(e.stream,STATE_PAUSING,STATE_PAUSED) }
            } else { INVALID_STATE }
        } else { INVALID_STATE }
    })
}

pub(crate) fn resume_output_for_session() -> Option<i32> {
    let e=engine().lock().unwrap_or_else(|p|p.into_inner());
    if e.stream.is_null() { return None; }
    Some(unsafe {
        let s=AAudioStream_getState(e.stream);
        if s==STATE_OPEN || s==STATE_STARTED || s==STATE_PAUSED { OK }
        else if s==STATE_STARTING { wait_stream_state(e.stream,STATE_STARTING,STATE_STARTED) }
        else { INVALID_STATE }
    })
}
pub(crate) fn stop_output_scheduler_for_session(session_handle: jlong) {
    stop_scheduler_for_session(session_handle);
}

/// Stops one session's scheduler. The shared device is shut down only when no
/// other live AAudio session can own or be preparing it.
pub(crate) fn stop_output_for_session(session_handle: jlong) {
    stop_scheduler_for_session(session_handle);
    if crate::playback_session::has_other_active_aaudio_session(session_handle) {
        return;
    }
    stop_output_completely();
}

fn stop_output_completely() {
    OUTPUT_PERSISTENT.store(false, Ordering::Release);
    stop_output_scheduler();
    let mut e=engine().lock().unwrap_or_else(|p|p.into_inner());
    if let Some(o)=e.output.as_ref(){o.active.store(false,Ordering::Release);}
    unsafe { e.stop(); }
}
#[no_mangle] pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativePauseOutput(_:JNIEnv,_:JClass)->jint{
    pause_output_for_session().unwrap_or(INVALID_STATE)
}
#[no_mangle] pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeResumeOutput(_:JNIEnv,_:JClass)->jint{
    resume_output_for_session().unwrap_or(INVALID_STATE)
}
#[no_mangle] pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeStopOutput(_:JNIEnv,_:JClass){
    stop_output_completely();
}
#[no_mangle] pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeIsOutputActive(_:JNIEnv,_:JClass)->jboolean{if engine().lock().unwrap_or_else(|p|p.into_inner()).output.as_ref().map(|o|o.active.load(Ordering::Acquire)).unwrap_or(false){JNI_TRUE}else{0}}
#[no_mangle] pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeGetOutputUnderrunCount(_:JNIEnv,_:JClass)->jint{engine().lock().unwrap_or_else(|p|p.into_inner()).output.as_ref().map(|o|o.underruns.load(Ordering::Acquire).min(i32::MAX as u64) as i32).unwrap_or(0)}
#[no_mangle] pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeGetOutputError(_:JNIEnv,_:JClass)->jint{engine().lock().unwrap_or_else(|p|p.into_inner()).output.as_ref().map(|o|o.error.load(Ordering::Acquire)).unwrap_or(INVALID_STATE)}
#[no_mangle] pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeGetLastOutputOpenDiagnostics(env:JNIEnv,_:JClass)->jlongArray{let e=engine().lock().unwrap_or_else(|p|p.into_inner());let a=env.new_long_array(11).unwrap();let _=env.set_long_array_region(&a,0,&e.last_open);a.into_raw()}
#[no_mangle] pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeGetOutputDiagnostics(env:JNIEnv,_:JClass)->jlongArray{let e=engine().lock().unwrap_or_else(|p|p.into_inner());let mut v=[0i64;39];if let Some(o)=e.output.as_ref(){v[0]=o.underruns.load(Ordering::Acquire) as i64;v[2]=e.producer.as_ref().map(|p|{let bpf=o.channels*o.bytes_per_sample;if bpf==0{0}else{o.capacity.saturating_sub((p.slots()/bpf) as u64) as i64}}).unwrap_or(0);v[3]=o.capacity as i64;v[19]=e.last_open[4];v[23]=o.rate as i64;v[31]=e.last_open[1];v[32]=e.last_open[6];v[33]=e.last_open[0];}if !e.stream.is_null(){unsafe{v[1]=AAudioStream_getXRunCount(e.stream) as i64;v[4]=AAudioStream_getBufferSizeInFrames(e.stream) as i64;v[5]=AAudioStream_getFramesPerBurst(e.stream) as i64;v[6]=AAudioStream_getState(e.stream) as i64;v[7]=AAudioStream_getSampleRate(e.stream) as i64;v[8]=AAudioStream_getDeviceId(e.stream) as i64;v[9]=AAudioStream_getSharingMode(e.stream) as i64;v[10]=AAudioStream_getFormat(e.stream) as i64;v[11]=AAudioStream_getChannelCount(e.stream) as i64;v[12]=AAudioStream_getPerformanceMode(e.stream) as i64;v[26]=AAudioStream_getBufferCapacityInFrames(e.stream) as i64;}}let a=env.new_long_array(39).unwrap();let _=env.set_long_array_region(&a,0,&v);a.into_raw()}
#[no_mangle] pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeTuneOutputBuffer(_:JNIEnv,_:JClass)->jint{let e=engine().lock().unwrap_or_else(|p|p.into_inner());if e.stream.is_null()||e.output.is_none(){return INVALID_STATE;}unsafe{let x=AAudioStream_getXRunCount(e.stream);if x<0{return x;}let cap=AAudioStream_getBufferCapacityInFrames(e.stream);let cur=AAudioStream_getBufferSizeInFrames(e.stream);let burst=AAudioStream_getFramesPerBurst(e.stream).max(1);if x>0&&cur>0&&cap>cur{let _=AAudioStream_setBufferSizeInFrames(e.stream,(cur+burst).min(cap));}OK}}

#[cfg(test)]
mod tests {
    #[test]
    fn track_position_excludes_previous_track_tail_and_never_goes_negative() {
        assert_eq!(super::output_track_position_frames(50_000, 44_100), 5_900);
        assert_eq!(super::output_track_position_frames(40_000, 44_100), 0);
    }

    use super::*;
    use rtrb::RingBuffer;

    fn test_scheduler(status: i32) -> OutputScheduler {
        let stop = Arc::new(AtomicBool::new(false));
        let worker_stop = Arc::clone(&stop);
        let thread = thread::spawn(move || {
            while !worker_stop.load(Ordering::Acquire) {
                thread::sleep(Duration::from_millis(1));
            }
        });
        OutputScheduler {
            stop,
            status: Arc::new(AtomicI32::new(status)),
            thread,
        }
    }

    #[test]
    fn scheduler_registry_isolated_by_playback_session() {
        let mut schedulers = HashMap::new();
        schedulers.insert(101, test_scheduler(0));
        schedulers.insert(202, test_scheduler(3));

        assert_eq!(scheduler_status_from_registry(&schedulers, 101), 0);
        assert_eq!(scheduler_status_from_registry(&schedulers, 202), 3);

        let old_session = take_scheduler_for_session(&mut schedulers, 101).unwrap();
        stop_scheduler_worker(old_session);

        assert_eq!(scheduler_status_from_registry(&schedulers, 101), 2);
        assert_eq!(scheduler_status_from_registry(&schedulers, 202), 3);
        assert!(schedulers.contains_key(&202));

        for worker in take_all_schedulers(&mut schedulers) {
            stop_scheduler_worker(worker);
        }
        assert!(schedulers.is_empty());
    }

    #[test]
    fn replacing_one_scheduler_does_not_remove_another_session() {
        let mut schedulers = HashMap::new();
        schedulers.insert(11, test_scheduler(0));
        schedulers.insert(22, test_scheduler(4));

        let previous = take_scheduler_for_session(&mut schedulers, 11).unwrap();
        stop_scheduler_worker(previous);
        schedulers.insert(11, test_scheduler(0));

        assert_eq!(scheduler_status_from_registry(&schedulers, 11), 0);
        assert_eq!(scheduler_status_from_registry(&schedulers, 22), 4);

        for worker in take_all_schedulers(&mut schedulers) {
            stop_scheduler_worker(worker);
        }
    }

    fn test_output_format(
        capacity_frames: usize,
        channels: usize,
        format: i32,
        bytes_per_sample: usize,
        queued: &[u8],
    ) -> Output {
        let (mut producer, consumer) = RingBuffer::<u8>::new(
            capacity_frames * channels * bytes_per_sample,
        );
        producer.push_entire_slice(queued).unwrap();
        Output {
            consumer: UnsafeCell::new(consumer),
            capacity: capacity_frames as u64,
            channels,
            format,
            rate: 48_000,
            bytes_per_sample,
            played: AtomicU64::new(0),
            volume: AtomicU32::new(1.0_f32.to_bits()),
            active: AtomicBool::new(true),
            eof: AtomicBool::new(false),
            track_eof: AtomicBool::new(false),
            underruns: AtomicU64::new(0),
            error: AtomicI32::new(0),
        }
    }

    fn test_output(capacity_frames: usize, channels: usize, queued: &[u8]) -> Output {
        test_output_format(capacity_frames, channels, FMT_I16, 2, queued)
    }

    #[test]
    fn reset_output_ring_discards_old_track_and_accepts_new_pcm() {
        let (mut producer, consumer) = RingBuffer::<u8>::new(16);
        producer.push_entire_slice(&[1, 2, 3, 4]).unwrap();
        let output = Output {
            consumer: UnsafeCell::new(consumer),
            capacity: 4,
            channels: 2,
            format: FMT_I16,
            rate: 48_000,
            bytes_per_sample: 2,
            played: AtomicU64::new(12),
            volume: AtomicU32::new(1.0_f32.to_bits()),
            active: AtomicBool::new(false),
            eof: AtomicBool::new(true),
            track_eof: AtomicBool::new(false),
            underruns: AtomicU64::new(3),
            error: AtomicI32::new(-1),
        };
        let mut engine = Engine {
            stream: ptr::null_mut(),
            output: Some(Box::new(output)),
            producer: Some(producer),
            producer_epoch: 1,
            last_open: [0; 11],
        };
        let previous_epoch = engine.producer_epoch;
        reset_output_ring(&mut engine, 48_000, 2, FMT_I16).unwrap();
        assert_ne!(engine.producer_epoch, previous_epoch);
        assert!(!producer_epoch_matches(engine.producer_epoch, previous_epoch));
        let next = [9_u8, 8, 7, 6];
        let producer = engine.producer.as_mut().unwrap();
        producer.push_entire_slice(&next).unwrap();
        let output = engine.output.as_ref().unwrap();
        let consumer = unsafe { &mut *output.consumer.get() };
        let mut received = [0_u8; 4];
        consumer.pop_entire_slice(&mut received).unwrap();
        assert_eq!(received, next);
        assert_eq!(output.played.load(Ordering::Acquire), 0);
        assert_eq!(output.eof.load(Ordering::Acquire), false);
        assert_eq!(output.active.load(Ordering::Acquire), true);
        assert_eq!(output.error.load(Ordering::Acquire), 0);
    }

    #[test]
    fn reset_output_ring_rejects_a_different_pcm_signature() {
        let (_, consumer) = RingBuffer::<u8>::new(16);
        let output = Output {
            consumer: UnsafeCell::new(consumer),
            capacity: 4,
            channels: 2,
            format: FMT_I16,
            rate: 48_000,
            bytes_per_sample: 2,
            played: AtomicU64::new(0),
            volume: AtomicU32::new(1.0_f32.to_bits()),
            active: AtomicBool::new(false),
            eof: AtomicBool::new(true),
            track_eof: AtomicBool::new(false),
            underruns: AtomicU64::new(0),
            error: AtomicI32::new(0),
        };
        let mut engine = Engine {
            stream: ptr::null_mut(),
            output: Some(Box::new(output)),
            producer: None,
            producer_epoch: 1,
            last_open: [0; 11],
        };
        assert_eq!(reset_output_ring(&mut engine, 44_100, 2, FMT_I16), Err(INVALID_FORMAT));
        assert_eq!(reset_output_ring(&mut engine, 48_000, 1, FMT_I16), Err(INVALID_FORMAT));
        assert_eq!(reset_output_ring(&mut engine, 48_000, 2, FMT_I24), Err(INVALID_FORMAT));
    }

    #[test]
    fn persistent_handoff_requires_same_device_and_sharing_mode() {
        assert!(output_identity_matches(7, SHARING_EXCLUSIVE, 7, SHARING_EXCLUSIVE));
        assert!(!output_identity_matches(8, SHARING_EXCLUSIVE, 7, SHARING_EXCLUSIVE));
        assert!(!output_identity_matches(7, SHARING_SHARED, 7, SHARING_EXCLUSIVE));
        assert!(!output_identity_matches(7, SHARING_EXCLUSIVE, 7, 9));
        assert!(!output_identity_matches(7, SHARING_EXCLUSIVE, -1, SHARING_EXCLUSIVE));
    }

    #[test]
    fn same_signature_handoff_preserves_buffered_pcm_and_invalidates_old_epoch() {
        let (mut producer, consumer) = rtrb::RingBuffer::<u8>::new(64);
        producer.push_entire_slice(&[1, 2, 3, 4, 5, 6, 7, 8]).unwrap();
        let free_before = producer.slots();
        let output = Output {
            consumer: UnsafeCell::new(consumer), capacity: 64, channels: 2,
            format: FMT_I16, rate: 48_000, bytes_per_sample: 2,
            played: AtomicU64::new(123), volume: AtomicU32::new(1.0f32.to_bits()),
            active: AtomicBool::new(false), eof: AtomicBool::new(true),
            track_eof: AtomicBool::new(false),
            underruns: AtomicU64::new(0), error: AtomicI32::new(-1),
        };
        let mut engine = Engine::new();
        engine.output = Some(Box::new(output));
        engine.producer = Some(producer);
        engine.producer_epoch = 9;
        engine.last_open[5] = 7;
        engine.last_open[6] = SHARING_EXCLUSIVE as i64;

        let next_epoch = attach_output_producer_for_next_track(
            &mut engine, true, 48_000, 2, FMT_I16, 7, SHARING_EXCLUSIVE,
        ).unwrap();

        assert_eq!(next_epoch, 10);
        assert!(!producer_epoch_matches(engine.producer_epoch, 9));
        let output = engine.output.as_ref().unwrap();
        assert_eq!(engine.producer.as_ref().unwrap().slots(), free_before);
        assert!(!output.eof.load(Ordering::Acquire));
        assert!(output.active.load(Ordering::Acquire));
        assert_eq!(output.error.load(Ordering::Acquire), 0);
        assert_eq!(output.played.load(Ordering::Acquire), 123);

        // Prebuffer the next track before the callback consumes the old tail.
        // One callback request spanning both tracks must receive contiguous PCM.
        engine.producer.as_mut().unwrap()
            .push_entire_slice(&[9, 10, 11, 12, 13, 14, 15, 16])
            .unwrap();
        let output_ptr = engine.output.as_deref_mut().unwrap() as *mut Output;
        let mut cross_track = [0u8; 16];
        let callback_result = unsafe {
            data_callback(
                ptr::null_mut(),
                output_ptr.cast(),
                cross_track.as_mut_ptr().cast(),
                4,
            )
        };
        assert_eq!(callback_result, 0);
        assert_eq!(
            cross_track,
            [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16],
            "a callback spanning the track boundary must receive old-tail PCM followed immediately by next-track PCM"
        );
    }

    #[test]
    fn callback_keeps_short_and_long_prefilled_track_tails_contiguous() {
        for old_frames in [1usize, 2, 8] {
            for next_frames in [1usize, 2, 8] {
                let (mut producer, consumer) = rtrb::RingBuffer::<u8>::new(256);
                let old_pcm: Vec<u8> = (0..old_frames * 4).map(|value| value as u8).collect();
                let next_pcm: Vec<u8> = (0..next_frames * 4)
                    .map(|value| (128 + value) as u8)
                    .collect();
                producer.push_entire_slice(&old_pcm).unwrap();
                let output = Output {
                    consumer: UnsafeCell::new(consumer), capacity: 32, channels: 2,
                    format: FMT_I16, rate: 48_000, bytes_per_sample: 2,
                    played: AtomicU64::new(0), volume: AtomicU32::new(1.0f32.to_bits()),
                    active: AtomicBool::new(false), eof: AtomicBool::new(true),
                    track_eof: AtomicBool::new(false),
                    underruns: AtomicU64::new(0), error: AtomicI32::new(-1),
                };
                let mut engine = Engine::new();
                engine.output = Some(Box::new(output));
                engine.producer = Some(producer);
                engine.producer_epoch = 20;
                engine.last_open[5] = 7;
                engine.last_open[6] = SHARING_EXCLUSIVE as i64;

                attach_output_producer_for_next_track(
                    &mut engine, true, 48_000, 2, FMT_I16, 7, SHARING_EXCLUSIVE,
                ).unwrap();
                engine.producer.as_mut().unwrap().push_entire_slice(&next_pcm).unwrap();

                let mut expected = old_pcm.clone();
                expected.extend_from_slice(&next_pcm);
                let mut actual = vec![0u8; expected.len()];
                let output_ptr = engine.output.as_deref_mut().unwrap() as *mut Output;
                let callback_result = unsafe {
                    data_callback(
                        ptr::null_mut(),
                        output_ptr.cast(),
                        actual.as_mut_ptr().cast(),
                        (old_frames + next_frames) as i32,
                    )
                };
                assert_eq!(callback_result, 0);
                assert_eq!(
                    actual, expected,
                    "PCM discontinuity for old tail {old_frames} frames and next prebuffer {next_frames} frames"
                );
            }
        }
    }

    #[test]
    fn same_signature_handoff_rejects_mismatch_without_changing_epoch() {
        let (producer, consumer) = rtrb::RingBuffer::<u8>::new(64);
        let output = Output {
            consumer: UnsafeCell::new(consumer), capacity: 64, channels: 2,
            format: FMT_I16, rate: 48_000, bytes_per_sample: 2,
            played: AtomicU64::new(0), volume: AtomicU32::new(1.0f32.to_bits()),
            active: AtomicBool::new(false), eof: AtomicBool::new(true),
            track_eof: AtomicBool::new(false),
            underruns: AtomicU64::new(0), error: AtomicI32::new(0),
        };
        let mut engine = Engine::new();
        engine.output = Some(Box::new(output));
        engine.producer = Some(producer);
        engine.producer_epoch = 3;
        engine.last_open[5] = 7;
        engine.last_open[6] = SHARING_EXCLUSIVE as i64;

        assert_eq!(attach_output_producer_for_next_track(&mut engine, true, 96_000, 2, FMT_I16, 7, SHARING_EXCLUSIVE), Err(INVALID_FORMAT));
        assert_eq!(attach_output_producer_for_next_track(&mut engine, true, 48_000, 2, FMT_I16, 8, SHARING_EXCLUSIVE), Err(DEVICE_MISMATCH));
        assert_eq!(attach_output_producer_for_next_track(&mut engine, false, 48_000, 2, FMT_I16, 7, SHARING_EXCLUSIVE), Err(INVALID_STATE));
        assert_eq!(engine.producer_epoch, 3);
    }

    #[test]
    fn scheduler_publishes_nonterminal_boundary_before_final_drain() {
        assert_eq!(output_scheduler_status(true, false, false), Some(3));
        assert_eq!(output_scheduler_status(false, false, false), None);
        assert_eq!(output_scheduler_status(true, true, false), Some(1));
        assert_eq!(output_scheduler_status(false, true, false), Some(1));
        assert_eq!(output_scheduler_status(false, true, true), Some(4));
    }
    #[test]
    fn track_eof_completes_only_after_buffer_drains_without_closing_queue_output() {
        assert!(!output_should_complete(false, true, 128, false));
        assert!(output_should_complete(false, true, 0, false));
        assert!(!output_should_complete(false, true, 0, true));
        assert!(!output_should_complete(false, false, 0, false));
    }

    #[test]
    fn track_eof_does_not_stop_callback_when_ring_is_empty() {
        let mut output = test_output(4, 2, &[]);
        output.track_eof.store(true, Ordering::Release);
        output.active.store(false, Ordering::Release);
        let mut audio = [0x55; 8];
        let result = unsafe {
            data_callback(ptr::null_mut(), (&mut output as *mut Output).cast(),
                audio.as_mut_ptr().cast(), 2)
        };
        assert_eq!(result, 0);
        assert!(!output.active.load(Ordering::Acquire));
        assert_eq!(audio, [0; 8]);
    }

    #[test]
    fn queue_eof_remains_a_terminal_output_condition() {
        assert!(!output_should_complete(true, false, 64, false));
        assert!(output_should_complete(true, false, 0, false));
    }

    #[test]
    fn stale_output_producer_epoch_is_rejected() {
        assert!(producer_epoch_matches(9, 9));
        assert!(!producer_epoch_matches(10, 9));
        assert!(!producer_epoch_matches(0, 0));
    }

    #[test]
    fn data_callback_drains_spsc_pcm_and_zero_fills_underrun() {
        let samples = [100_i16, -100, 200, -200, 300, -300];
        let bytes = samples.iter().flat_map(|sample| sample.to_le_bytes()).collect::<Vec<_>>();
        let mut output = test_output(4, 2, &bytes);
        let mut audio = [0u8; 16];
        let result = unsafe {
            data_callback(ptr::null_mut(), (&mut output as *mut Output).cast(), audio.as_mut_ptr().cast(), 4)
        };
        assert_eq!(result, 0);
        assert_eq!(&audio[..12], bytes.as_slice());
        assert_eq!(&audio[12..], &[0; 4]);
        assert_eq!(output.played.load(Ordering::Acquire), 3);
        assert_eq!(output.underruns.load(Ordering::Acquire), 1);
    }

    #[test]
    fn data_callback_drains_wrapped_spsc_ring_without_reordering_pcm() {
        let (mut producer, mut consumer) = RingBuffer::<u8>::new(8);
        producer.push_entire_slice(&[1, 2, 3, 4, 5, 6]).unwrap();
        let mut discarded = [0; 4];
        consumer.pop_entire_slice(&mut discarded).unwrap();
        producer.push_entire_slice(&[7, 8, 9, 10]).unwrap();

        let mut output = Output {
            consumer: UnsafeCell::new(consumer),
            capacity: 4,
            channels: 1,
            format: FMT_I16,
            rate: 48_000,
            bytes_per_sample: 2,
            played: AtomicU64::new(0),
            volume: AtomicU32::new(1.0_f32.to_bits()),
            active: AtomicBool::new(true),
            eof: AtomicBool::new(false),
            track_eof: AtomicBool::new(false),
            underruns: AtomicU64::new(0),
            error: AtomicI32::new(0),
        };
        let mut audio = [0; 6];
        let result = unsafe {
            data_callback(ptr::null_mut(), (&mut output as *mut Output).cast(),
                audio.as_mut_ptr().cast(), 3)
        };
        assert_eq!(result, 0);
        assert_eq!(audio, [5, 6, 7, 8, 9, 10]);
        assert_eq!(output.played.load(Ordering::Acquire), 3);
        assert_eq!(output.underruns.load(Ordering::Acquire), 0);
    }

    #[test]
    fn data_callback_stops_after_eof_and_ring_drain() {
        let samples = [100_i16, -100];
        let bytes = samples.iter().flat_map(|sample| sample.to_le_bytes()).collect::<Vec<_>>();
        let mut output = test_output(4, 2, &bytes);
        output.eof.store(true, Ordering::Relaxed);
        let mut audio = [0u8; 8];
        let result = unsafe {
            data_callback(ptr::null_mut(), (&mut output as *mut Output).cast(), audio.as_mut_ptr().cast(), 2)
        };
        assert_eq!(result, 1);
        assert!(!output.active.load(Ordering::Acquire));
        assert_eq!(output.played.load(Ordering::Acquire), 1);
        assert_eq!(&audio[4..], &[0; 4]);
        assert_eq!(output.underruns.load(Ordering::Acquire), 0);
    }

    #[test]
    fn data_callback_applies_integer_gain_without_changing_frame_accounting() {
        let samples = [1000_i16, -1000];
        let bytes = samples.iter().flat_map(|sample| sample.to_le_bytes()).collect::<Vec<_>>();
        let mut output = test_output(4, 2, &bytes);
        output.volume.store(0.5_f32.to_bits(), Ordering::Relaxed);
        let mut audio = [0u8; 4];
        let result = unsafe {
            data_callback(ptr::null_mut(), (&mut output as *mut Output).cast(), audio.as_mut_ptr().cast(), 1)
        };
        assert_eq!(result, 0);
        let actual = [i16::from_le_bytes([audio[0], audio[1]]), i16::from_le_bytes([audio[2], audio[3]])];
        assert_eq!(actual, [500, -500]);
        assert_eq!(output.played.load(Ordering::Acquire), 1);
    }

    #[test]
    fn data_callback_preserves_pcm24_and_pcm32_bytes_and_zero_fills_tail() {
        let cases: [(i32, usize, Vec<u8>); 2] = [
            (FMT_I24, 3, vec![0xff, 0xff, 0x7f, 0x00, 0x00, 0x80]),
            (FMT_I32, 4, i32::MAX.to_le_bytes().into_iter()
                .chain(i32::MIN.to_le_bytes()).collect()),
        ];
        for (format, bytes_per_sample, source) in cases {
            let capacity_bytes = 2 * bytes_per_sample;
            let (mut producer, mut consumer) = RingBuffer::<u8>::new(capacity_bytes);
            producer.push_entire_slice(&vec![0; bytes_per_sample]).unwrap();
            let mut discarded = vec![0; bytes_per_sample];
            consumer.pop_entire_slice(&mut discarded).unwrap();
            producer.push_entire_slice(&source).unwrap();
            let mut output = Output {
                consumer: UnsafeCell::new(consumer),
                capacity: 2,
                channels: 1,
                format,
                rate: 48_000,
                bytes_per_sample,
                played: AtomicU64::new(0),
                volume: AtomicU32::new(1.0_f32.to_bits()),
                active: AtomicBool::new(true),
                eof: AtomicBool::new(false),
            track_eof: AtomicBool::new(false),
                underruns: AtomicU64::new(0),
                error: AtomicI32::new(0),
            };
            let mut audio = vec![0x55; 3 * bytes_per_sample];
            let result = unsafe {
                data_callback(
                    ptr::null_mut(),
                    (&mut output as *mut Output).cast(),
                    audio.as_mut_ptr().cast(),
                    3,
                )
            };
            let mut expected = source;
            expected.extend(vec![0; bytes_per_sample]);
            assert_eq!(result, 0, "format {format}");
            assert_eq!(audio, expected, "format {format}");
            assert_eq!(output.played.load(Ordering::Acquire), 2);
            assert_eq!(output.underruns.load(Ordering::Acquire), 1);
        }
    }

    #[test]
    fn data_callback_applies_float_gain_and_zero_fills_underrun() {
        let source = [0.5_f32, -0.25_f32].iter()
            .flat_map(|sample| sample.to_le_bytes()).collect::<Vec<_>>();
        let mut output = test_output_format(4, 1, FMT_FLOAT, 4, &source);
        output.volume.store(0.5_f32.to_bits(), Ordering::Relaxed);
        let mut audio = [0x55_u8; 12];
        let result = unsafe {
            data_callback(ptr::null_mut(), (&mut output as *mut Output).cast(),
                audio.as_mut_ptr().cast(), 3)
        };
        let samples: Vec<f32> = audio.chunks_exact(4)
            .map(|bytes| f32::from_le_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]))
            .collect();
        assert_eq!(result, 0);
        assert_eq!(samples, [0.25, -0.125, 0.0]);
        assert_eq!(output.played.load(Ordering::Acquire), 2);
        assert_eq!(output.underruns.load(Ordering::Acquire), 1);
    }

    #[test]
    fn data_callback_applies_integer_gain_for_pcm16_pcm24_and_pcm32() {
        let cases: [(i32, usize, Vec<u8>, Vec<u8>); 3] = [
            (
                FMT_I16, 2,
                [-2_i16, 2_i16].iter().flat_map(|v| v.to_le_bytes()).collect(),
                [-1_i16, 1_i16].iter().flat_map(|v| v.to_le_bytes()).collect(),
            ),
            (
                FMT_I24, 3,
                vec![0xfe, 0xff, 0xff, 0x02, 0x00, 0x00],
                vec![0xff, 0xff, 0xff, 0x01, 0x00, 0x00],
            ),
            (
                FMT_I32, 4,
                [-2_i32, 2_i32].iter().flat_map(|v| v.to_le_bytes()).collect(),
                [-1_i32, 1_i32].iter().flat_map(|v| v.to_le_bytes()).collect(),
            ),
        ];
        for (format, bytes_per_sample, source, expected) in cases {
            let mut output = test_output_format(2, 1, format, bytes_per_sample, &source);
            output.volume.store(0.5_f32.to_bits(), Ordering::Relaxed);
            let mut audio = vec![0; source.len()];
            let result = unsafe {
                data_callback(ptr::null_mut(), (&mut output as *mut Output).cast(),
                    audio.as_mut_ptr().cast(), 2)
            };
            assert_eq!(result, 0, "format {format}");
            assert_eq!(audio, expected, "format {format}");
            assert_eq!(output.played.load(Ordering::Acquire), 2);
        }
    }

    #[test]
    fn data_callback_silences_output_and_stops_on_invalid_gain_format() {
        let source = [1, 2, 3, 4];
        let mut output = test_output_format(2, 1, 99, 2, &source);
        output.volume.store(0.5_f32.to_bits(), Ordering::Relaxed);
        let mut audio = [0x55_u8; 4];
        let result = unsafe {
            data_callback(ptr::null_mut(), (&mut output as *mut Output).cast(),
                audio.as_mut_ptr().cast(), 2)
        };
        assert_eq!(result, 1);
        assert_eq!(audio, [0; 4]);
        assert_eq!(output.error.load(Ordering::Acquire), INVALID_FORMAT);
        assert!(!output.active.load(Ordering::Acquire));
    }
}
