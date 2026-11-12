use jni::objects::JClass;
use jni::sys::{jboolean, jint, jlong, jlongArray, JNI_TRUE};
use jni::JNIEnv;
use std::ffi::c_void;
use std::ptr;
use crate::pcm_format::PcmFormat;
use std::sync::{Arc, Mutex, OnceLock};
use std::sync::atomic::{AtomicBool, AtomicU64, AtomicI32, AtomicU32, Ordering};
use std::thread::{self, JoinHandle};
use std::time::Duration;

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

const OK:i32=0; const INVALID_STATE:i32=-895; const INVALID_FORMAT:i32=-898; const UNAVAILABLE:i32=-890;

fn valid_integer_output_format(rate: i32, channels: i32, format: i32) -> bool {
    let (valid_bits, subslot_bytes) = match format {
        FMT_I16 => (16, 2),
        FMT_I24 => (24, 3),
        _ => return false,
    };
    if rate <= 0 || channels <= 0 {
        return false;
    }
    PcmFormat {
        sample_rate: rate as u32,
        channels: channels as u16,
        valid_bits,
        subslot_bytes,
        little_endian: true,
    }.validate().is_ok()
}
const FMT_I16:i32=1; const FMT_I24:i32=3;
const SHARING_EXCLUSIVE:i32=0; const SHARING_SHARED:i32=1;
const STATE_OPEN:i32=2; const STATE_STARTING:i32=3; const STATE_STARTED:i32=4;
const STATE_PAUSING:i32=5; const STATE_PAUSED:i32=6; const STATE_STOPPED:i32=10; const STATE_DISCONNECTED:i32=13;

struct Output {
    ring: Vec<f32>, capacity:u64, mask:u64, channels:usize, format:i32, rate:i32,
    read:AtomicU64, write:AtomicU64, played:AtomicU64, volume:AtomicU32,
    active:AtomicBool, eof:AtomicBool, underruns:AtomicU64, error:AtomicI32,
}
impl Output {
    fn gain(&self)->f32 { f32::from_bits(self.volume.load(Ordering::Relaxed)) }
}
struct Engine { stream:*mut AAudioStream, output:Option<Box<Output>>, last_open:[i64;11] }
unsafe impl Send for Engine {}
impl Engine {
    fn new()->Self { Self{stream:ptr::null_mut(),output:None,last_open:[0;11]} }
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
        self.output=None;
    }
    unsafe fn open(&mut self, out:&mut Output, rate:i32, sharing:i32, device:i32)->i32 {
        let mut builder=ptr::null_mut();
        let mut result=AAudio_createStreamBuilder(&mut builder);
        if result!=OK || builder.is_null(){return result;}
        AAudioStreamBuilder_setDirection(builder,0);
        if device>0 {
            if let Ok(name)=std::ffi::CString::new("AAudioStreamBuilder_setDeviceId") {
                let symbol=libc::dlsym(libc::RTLD_DEFAULT,name.as_ptr());
                if !symbol.is_null(){let setter:unsafe extern "C" fn(*mut AAudioStreamBuilder,i32)=std::mem::transmute(symbol);setter(builder,device);}
            }
        }
        AAudioStreamBuilder_setFormat(builder,out.format);
        AAudioStreamBuilder_setSampleRate(builder,rate);
        AAudioStreamBuilder_setChannelCount(builder,out.channels as i32);
        AAudioStreamBuilder_setSharingMode(builder,sharing);
        AAudioStreamBuilder_setPerformanceMode(builder,12);
        let data=(out as *mut Output).cast::<c_void>();
        AAudioStreamBuilder_setDataCallback(builder,Some(data_callback),data);
        AAudioStreamBuilder_setErrorCallback(builder,Some(error_callback),data);
        result=AAudioStreamBuilder_openStream(builder,&mut self.stream);
        AAudioStreamBuilder_delete(builder);
        if result!=OK {return result;}
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
fn engine()-> &'static Mutex<Engine>{ENGINE.get_or_init(||Mutex::new(Engine::new()))}

/// Returns free PCM frames in the bounded output ring.
/// Returns queued frames, free ring capacity, and the producer's latency budget.
pub(crate) fn output_queue_budget() -> Option<(usize, usize, usize)> {
    let engine = engine().lock().unwrap_or_else(|poisoned| poisoned.into_inner());
    engine.output.as_ref().map(|output| {
        let read = output.read.load(Ordering::Acquire);
        let write = output.write.load(Ordering::Acquire);
        let queued = write.saturating_sub(read);
        let free = output.capacity.saturating_sub(queued);
        let target = crate::playback_policy::target_queue_frames(
            output.rate,
            output.capacity.min(i32::MAX as u64) as i32,
        );
        (queued as usize, free as usize, target)
    })
}

/// Copies interleaved float PCM into the output ring without crossing JNI.
pub(crate) fn write_output_samples(samples: &[f32]) -> Result<usize, i32> {
    let mut engine = engine().lock().unwrap_or_else(|poisoned| poisoned.into_inner());
    let output = engine.output.as_mut().ok_or(INVALID_STATE)?;
    if samples.len() % output.channels != 0 {
        return Err(INVALID_FORMAT);
    }
    let requested_frames = samples.len() / output.channels;
    let read = output.read.load(Ordering::Acquire);
    let write = output.write.load(Ordering::Relaxed);
    let queued = write.saturating_sub(read);
    let frames = requested_frames.min(output.capacity.saturating_sub(queued) as usize);
    if frames == 0 {
        return Ok(0);
    }
    let sample_count = frames * output.channels;
    let ring_len = output.ring.len();
    for (index, sample) in samples[..sample_count].iter().enumerate() {
        let ring_index = (write as usize * output.channels + index) % ring_len;
        output.ring[ring_index] = *sample;
    }
    output.write.store(write + frames as u64, Ordering::Release);
    Ok(frames)
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
    let o=&*(data as *const Output); let requested=frames as u64;
    let read=o.read.load(Ordering::Relaxed); let write=o.write.load(Ordering::Acquire);
    let count=requested.min(write.saturating_sub(read)); let gain=o.gain();
    let bps=match o.format{FMT_I16=>2,FMT_I24=>3,_=>3};
    for f in 0..count { for c in 0..o.channels {
        let sample=(o.ring[(((read+f)&o.mask) as usize)*o.channels+c]*gain).clamp(-1.0,1.0);
        let index=f as usize*o.channels+c;
        match o.format {
            FMT_I16=>(audio as *mut i16).add(index).write((sample*if sample<0.0{32768.0}else{32767.0}).round() as i16),
            FMT_I24=>{let v=(sample*if sample<0.0{8388608.0}else{8388607.0}).round() as i32;let p=(audio as *mut u8).add(index*3);p.write(v as u8);p.add(1).write((v>>8) as u8);p.add(2).write((v>>16) as u8);},
            _=>{}
        }
    }}
    let remaining=requested-count;
    if remaining>0 {ptr::write_bytes((audio as *mut u8).add(count as usize*o.channels*bps),0,remaining as usize*o.channels*bps);
        if !o.eof.load(Ordering::Acquire){o.underruns.fetch_add(1,Ordering::Relaxed);}}
    o.read.store(read+count,Ordering::Release);o.played.fetch_add(count,Ordering::Relaxed);
    if remaining>0 && o.eof.load(Ordering::Acquire) && o.write.load(Ordering::Acquire)==read+count {o.active.store(false,Ordering::Release);1}else{0}
}


struct OutputScheduler {
    stop: Arc<AtomicBool>,
    status: Arc<AtomicI32>,
    thread: JoinHandle<()>,
}
static OUTPUT_SCHEDULER: OnceLock<Mutex<Option<OutputScheduler>>> = OnceLock::new();
static OUTPUT_SCHEDULER_LIFECYCLE: OnceLock<Mutex<()>> = OnceLock::new();

fn output_scheduler() -> &'static Mutex<Option<OutputScheduler>> {
    OUTPUT_SCHEDULER.get_or_init(|| Mutex::new(None))
}

fn output_scheduler_lifecycle() -> &'static Mutex<()> {
    OUTPUT_SCHEDULER_LIFECYCLE.get_or_init(|| Mutex::new(()))
}

fn stop_output_scheduler_locked() {
    let worker = output_scheduler().lock().unwrap_or_else(|p| p.into_inner()).take();
    if let Some(worker) = worker {
        worker.stop.store(true, Ordering::Release);
        if worker.thread.thread().id() != thread::current().id() {
            let _ = worker.thread.join();
        }
    }
}

fn stop_output_scheduler() {
    let _lifecycle = output_scheduler_lifecycle()
        .lock()
        .unwrap_or_else(|p| p.into_inner());
    stop_output_scheduler_locked();
}

fn output_scheduler_tick(low_watermark: u64, phase: i32, start_requested: &mut bool) -> Result<bool, i32> {
    let e = engine().lock().unwrap_or_else(|p| p.into_inner());
    if e.stream.is_null() { return Err(INVALID_STATE); }
    let output = e.output.as_ref().ok_or(INVALID_STATE)?;
    let error = output.error.load(Ordering::Acquire);
    if error != 0 { return Err(error); }
    let read = output.read.load(Ordering::Acquire);
    let write = output.write.load(Ordering::Acquire);
    let buffered = write.saturating_sub(read);
    let eof = output.eof.load(Ordering::Acquire);
    let active = output.active.load(Ordering::Acquire);
    unsafe {
        let state = AAudioStream_getState(e.stream);
        if state == STATE_DISCONNECTED { return Err(-899); }
        if state == STATE_STARTED {
            *start_requested = false;
        }
        if phase == 2 && (state == STATE_OPEN || state == STATE_PAUSED) &&
            (buffered >= low_watermark || eof) && !*start_requested {
            let result = AAudioStream_requestStart(e.stream);
            if result != OK { return Err(result); }
            *start_requested = true;
        }
        let current_state = AAudioStream_getState(e.stream);
        if current_state == STATE_STOPPED && !eof { return Err(INVALID_STATE); }
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
    Ok(eof && buffered == 0 && !active)
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
    stop_output_scheduler_locked();
    let stop = Arc::new(AtomicBool::new(false));
    let status = Arc::new(AtomicI32::new(0));
    let thread_stop = Arc::clone(&stop);
    let thread_status = Arc::clone(&status);
    let worker_session = Arc::clone(&session);
    let watermark = low_watermark as u64;
    let worker = thread::Builder::new().name("flowery-aaudio-output".into()).spawn(move || {
        let mut start_requested = false;
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
                    Ok(true) => {
                        if crate::playback_session::transition_session_phase(&worker_session, 4) &&
                            crate::playback_session::transition_session_phase(&worker_session, 5) {
                            let mut output_engine = engine().lock().unwrap_or_else(|p| p.into_inner());
                            unsafe { output_engine.stop(); }
                            thread_status.store(1, Ordering::Release);
                            break;
                        }
                    }
                    Ok(false) => {}
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
            *output_scheduler().lock().unwrap_or_else(|p| p.into_inner()) =
                Some(OutputScheduler { stop, status, thread });
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
    _env: JNIEnv, _class: JClass, _session_handle: jlong,
) -> jint {
    output_scheduler().lock().unwrap_or_else(|p| p.into_inner())
        .as_ref().map(|worker| worker.status.load(Ordering::Acquire)).unwrap_or(-1)
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeStopOutputScheduler(
    _env: JNIEnv, _class: JClass, _session_handle: jlong,
) {
    stop_output_scheduler();
}

#[no_mangle] pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeStartStreamOutput(mut env:JNIEnv,_:JClass,rate:jint,channels:jint,volume:f32,device:jint,exclusive:jboolean,format:jint,capacity:jint)->jint{
    if !(channels==1||channels==2) || capacity<2048 || !valid_integer_output_format(rate,channels,format){return INVALID_FORMAT;}
    let mut cap=1u64;while cap<capacity as u64{cap<<=1;}
    let mut out=Box::new(Output{ring:vec![0.0;(cap as usize)*channels as usize],capacity:cap,mask:cap-1,channels:channels as usize,format,rate,read:AtomicU64::new(0),write:AtomicU64::new(0),played:AtomicU64::new(0),volume:AtomicU32::new(volume.clamp(0.0,1.0).to_bits()),active:AtomicBool::new(false),eof:AtomicBool::new(false),underruns:AtomicU64::new(0),error:AtomicI32::new(0)});
    stop_output_scheduler();
    let mut e=engine().lock().unwrap_or_else(|p|p.into_inner());unsafe{e.stop();}
    let sharing=if exclusive==JNI_TRUE{SHARING_EXCLUSIVE}else{SHARING_SHARED};
    e.last_open=[device as i64,sharing as i64,rate as i64,channels as i64,INVALID_STATE as i64,0,-1,0,0,-1,1];
    let mut result=unsafe{e.open(&mut out,rate,sharing,device)};
    e.last_open[4]=result as i64;
    let mut actual_sharing=sharing;
    if result!=OK && sharing==SHARING_EXCLUSIVE {
        unsafe{e.stop();}
        actual_sharing=SHARING_SHARED;
        result=unsafe{e.open(&mut out,rate,actual_sharing,device)};
        e.last_open[4]=result as i64;
        e.last_open[10]=4; // Exclusive open failed; shared retry was attempted.
    }
    if result!=OK{unsafe{e.stop();}return result;}
    out.active.store(true,Ordering::Release);e.last_open[5]=unsafe{AAudioStream_getDeviceId(e.stream)} as i64;e.last_open[6]=actual_sharing as i64;e.last_open[7]=out.rate as i64;e.last_open[8]=channels as i64;e.last_open[9]=format as i64;if e.last_open[10]!=4{e.last_open[10]=0;}e.output=Some(out);let _=&mut env;OK
}
#[no_mangle] pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeStartStreamPlayback(_:JNIEnv,_:JClass)->jint{
 let e=engine().lock().unwrap_or_else(|p|p.into_inner());if e.output.is_none()||e.stream.is_null(){return INVALID_STATE;}unsafe{let s=AAudioStream_getState(e.stream);if s==STATE_STARTED{return OK;}if s!=STATE_OPEN&&s!=STATE_PAUSED&&s!=STATE_STOPPED{return INVALID_STATE;}AAudioStream_requestStart(e.stream)}
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
    let e = engine().lock().unwrap_or_else(|p| p.into_inner());
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
    output.read.store(0, Ordering::Relaxed);
    output.write.store(0, Ordering::Relaxed);
    output.played.store(position as u64, Ordering::Release);
    output.eof.store(false, Ordering::Release);
    output.active.store(true, Ordering::Release);
    OK
}

/// Signals end-of-stream to the AAudio callback without crossing JNI.
pub(crate) fn signal_stream_eof() {
    if let Some(output) = engine().lock().unwrap_or_else(|poisoned| poisoned.into_inner()).output.as_ref() {
        output.eof.store(true, Ordering::Release);
    }
}

#[no_mangle] pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeSetStreamEof(_:JNIEnv,_:JClass){if let Some(o)=engine().lock().unwrap_or_else(|p|p.into_inner()).output.as_ref(){o.eof.store(true,Ordering::Release);}}
#[no_mangle] pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeGetStreamBufferedFrames(_:JNIEnv,_:JClass)->jint{let e=engine().lock().unwrap_or_else(|p|p.into_inner());e.output.as_ref().map(|o|o.write.load(Ordering::Acquire).saturating_sub(o.read.load(Ordering::Acquire)).min(i32::MAX as u64) as i32).unwrap_or(0)}
#[no_mangle] pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeGetOutputPositionFrames(_:JNIEnv,_:JClass)->jlong{engine().lock().unwrap_or_else(|p|p.into_inner()).output.as_ref().map(|o|o.played.load(Ordering::Acquire) as i64).unwrap_or(0)}
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
pub(crate) fn stop_output_for_session() {
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
    stop_output_for_session();
}
#[no_mangle] pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeIsOutputActive(_:JNIEnv,_:JClass)->jboolean{if engine().lock().unwrap_or_else(|p|p.into_inner()).output.as_ref().map(|o|o.active.load(Ordering::Acquire)).unwrap_or(false){JNI_TRUE}else{0}}
#[no_mangle] pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeGetOutputUnderrunCount(_:JNIEnv,_:JClass)->jint{engine().lock().unwrap_or_else(|p|p.into_inner()).output.as_ref().map(|o|o.underruns.load(Ordering::Acquire).min(i32::MAX as u64) as i32).unwrap_or(0)}
#[no_mangle] pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeGetOutputError(_:JNIEnv,_:JClass)->jint{engine().lock().unwrap_or_else(|p|p.into_inner()).output.as_ref().map(|o|o.error.load(Ordering::Acquire)).unwrap_or(INVALID_STATE)}
#[no_mangle] pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeGetLastOutputOpenDiagnostics(env:JNIEnv,_:JClass)->jlongArray{let e=engine().lock().unwrap_or_else(|p|p.into_inner());let a=env.new_long_array(11).unwrap();let _=env.set_long_array_region(&a,0,&e.last_open);a.into_raw()}
#[no_mangle] pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeGetOutputDiagnostics(env:JNIEnv,_:JClass)->jlongArray{let e=engine().lock().unwrap_or_else(|p|p.into_inner());let mut v=[0i64;39];if let Some(o)=e.output.as_ref(){v[0]=o.underruns.load(Ordering::Acquire) as i64;v[2]=o.write.load(Ordering::Acquire) as i64-o.read.load(Ordering::Acquire) as i64;v[3]=o.capacity as i64;v[19]=e.last_open[4];v[23]=o.rate as i64;v[31]=e.last_open[1];v[32]=e.last_open[6];v[33]=e.last_open[0];}if !e.stream.is_null(){unsafe{v[1]=AAudioStream_getXRunCount(e.stream) as i64;v[4]=AAudioStream_getBufferSizeInFrames(e.stream) as i64;v[5]=AAudioStream_getFramesPerBurst(e.stream) as i64;v[6]=AAudioStream_getState(e.stream) as i64;v[7]=AAudioStream_getSampleRate(e.stream) as i64;v[8]=AAudioStream_getDeviceId(e.stream) as i64;v[9]=AAudioStream_getSharingMode(e.stream) as i64;v[10]=AAudioStream_getFormat(e.stream) as i64;v[11]=AAudioStream_getChannelCount(e.stream) as i64;v[12]=AAudioStream_getPerformanceMode(e.stream) as i64;v[26]=AAudioStream_getBufferCapacityInFrames(e.stream) as i64;}}let a=env.new_long_array(39).unwrap();let _=env.set_long_array_region(&a,0,&v);a.into_raw()}
#[no_mangle] pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeTuneOutputBuffer(_:JNIEnv,_:JClass)->jint{let e=engine().lock().unwrap_or_else(|p|p.into_inner());if e.stream.is_null()||e.output.is_none(){return INVALID_STATE;}unsafe{let x=AAudioStream_getXRunCount(e.stream);if x<0{return x;}let cap=AAudioStream_getBufferCapacityInFrames(e.stream);let cur=AAudioStream_getBufferSizeInFrames(e.stream);let burst=AAudioStream_getFramesPerBurst(e.stream).max(1);if x>0&&cur>0&&cap>cur{let _=AAudioStream_setBufferSizeInFrames(e.stream,(cur+burst).min(cap));}OK}}

#[cfg(test)]
mod tests {
    use super::*;

    fn test_output(capacity: usize, channels: usize) -> Output {
        Output {
            ring: vec![0.0; capacity * channels],
            capacity: capacity as u64,
            mask: capacity as u64 - 1,
            channels,
            format: FMT_I16,
            rate: 48_000,
            read: AtomicU64::new(0),
            write: AtomicU64::new(0),
            played: AtomicU64::new(0),
            volume: AtomicU32::new(1.0_f32.to_bits()),
            active: AtomicBool::new(true),
            eof: AtomicBool::new(false),
            underruns: AtomicU64::new(0),
            error: AtomicI32::new(0),
        }
    }

    #[test]
    fn data_callback_drains_wrapped_ring_and_zero_fills_underrun() {
        let mut output = test_output(4, 2);
        output.read.store(3, Ordering::Relaxed);
        output.write.store(6, Ordering::Relaxed);
        output.played.store(100, Ordering::Relaxed);

        // Three queued stereo frames occupy ring slots 3, 0, and 1.
        output.ring[6..8].copy_from_slice(&[0.1, -0.1]);
        output.ring[0..2].copy_from_slice(&[0.2, -0.2]);
        output.ring[2..4].copy_from_slice(&[0.3, -0.3]);

        let mut audio = [9.0_f32; 8];
        let result = unsafe {
            data_callback(
                ptr::null_mut(),
                (&mut output as *mut Output).cast::<c_void>(),
                audio.as_mut_ptr().cast::<c_void>(),
                4,
            )
        };

        assert_eq!(result, 0);
        assert_eq!(&audio[..6], &[0.1, -0.1, 0.2, -0.2, 0.3, -0.3]);
        assert_eq!(&audio[6..], &[0.0, 0.0]);
        assert_eq!(output.read.load(Ordering::Acquire), 6);
        assert_eq!(output.played.load(Ordering::Acquire), 103);
        assert_eq!(output.underruns.load(Ordering::Acquire), 1);
        assert!(output.active.load(Ordering::Acquire));
    }

    #[test]
    fn data_callback_stops_only_after_eof_ring_is_drained() {
        let mut output = test_output(4, 1);
        output.ring[0] = 0.25;
        output.write.store(1, Ordering::Relaxed);
        output.eof.store(true, Ordering::Relaxed);

        let mut final_frame = [0.0_f32; 1];
        let first_result = unsafe {
            data_callback(
                ptr::null_mut(),
                (&mut output as *mut Output).cast::<c_void>(),
                final_frame.as_mut_ptr().cast::<c_void>(),
                1,
            )
        };
        assert_eq!(first_result, 0);
        assert_eq!(final_frame, [0.25]);
        assert!(output.active.load(Ordering::Acquire));

        let mut silence = [9.0_f32; 1];
        let second_result = unsafe {
            data_callback(
                ptr::null_mut(),
                (&mut output as *mut Output).cast::<c_void>(),
                silence.as_mut_ptr().cast::<c_void>(),
                1,
            )
        };
        assert_eq!(second_result, 1);
        assert_eq!(silence, [0.0]);
        assert!(!output.active.load(Ordering::Acquire));
        assert_eq!(output.underruns.load(Ordering::Acquire), 0);
    }
}
