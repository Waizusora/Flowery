use std::fs::File;
use std::io::{Read, Seek, SeekFrom};
use std::os::fd::FromRawFd;

use jni::objects::JClass;
use jni::sys::{jbyteArray, jint, jlong, jlongArray};
use jni::JNIEnv;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct WavHeader {
    sample_rate: u32,
    channels: u16,
    bit_depth: u16,
    block_align: u16,
    data_offset: u64,
    data_bytes: u64,
}

fn read_wav_header<R: Read + Seek>(reader: &mut R) -> Result<WavHeader, String> {
    reader.seek(SeekFrom::Start(0)).map_err(|e| format!("Unable to seek WAV input: {e}"))?;
    let mut riff = [0_u8; 12];
    reader.read_exact(&mut riff).map_err(|_| "WAV header is incomplete".to_string())?;
    if &riff[0..4] != b"RIFF" || &riff[8..12] != b"WAVE" {
        return Err("USB direct PCM requires a little-endian RIFF/WAVE file.".into());
    }

    let riff_size = u32::from_le_bytes(riff[4..8].try_into().unwrap()) as u64;
    let riff_end = 8_u64.checked_add(riff_size).ok_or("Invalid RIFF size")?;
    if riff_end < 12 {
        return Err("RIFF container size is invalid.".into());
    }

    let mut format: Option<(u16, u16, u32, u16, u16)> = None;
    let mut data: Option<(u64, u64)> = None;

    loop {
        let position = reader.stream_position().map_err(|e| format!("Unable to inspect WAV position: {e}"))?;
        if position >= riff_end || (riff_end - position) < 8 {
            break;
        }
        let mut chunk = [0_u8; 8];
        reader.read_exact(&mut chunk).map_err(|_| "WAV chunk header is incomplete".to_string())?;
        let chunk_size = u32::from_le_bytes(chunk[4..8].try_into().unwrap()) as u64;
        let chunk_data = reader.stream_position().map_err(|e| format!("Unable to inspect WAV chunk: {e}"))?;
        let chunk_end = chunk_data.checked_add(chunk_size).ok_or("WAV chunk size overflow")?;
        let padded_end = chunk_end.checked_add(chunk_size & 1).ok_or("WAV chunk padding overflow")?;
        if padded_end > riff_end {
            return Err("A WAV chunk extends beyond the RIFF container.".into());
        }

        match &chunk[0..4] {
            b"fmt " => {
                if chunk_size < 16 {
                    return Err("WAV format chunk is incomplete.".into());
                }
                let mut fmt = [0_u8; 16];
                reader.read_exact(&mut fmt).map_err(|_| "WAV format chunk is incomplete".to_string())?;
                let format_code = u16::from_le_bytes([fmt[0], fmt[1]]);
                let channels = u16::from_le_bytes([fmt[2], fmt[3]]);
                let sample_rate = u32::from_le_bytes(fmt[4..8].try_into().unwrap());
                let block_align = u16::from_le_bytes([fmt[12], fmt[13]]);
                let bit_depth = u16::from_le_bytes([fmt[14], fmt[15]]);
                format = Some((format_code, channels, sample_rate, block_align, bit_depth));
            }
            b"data" => data = Some((chunk_data, chunk_size)),
            _ => {}
        }

        reader.seek(SeekFrom::Start(padded_end))
            .map_err(|e| format!("Unable to skip WAV chunk: {e}"))?;
        if format.is_some() && data.is_some() {
            break;
        }
    }

    let (format_code, channels, sample_rate, block_align, bit_depth) =
        format.ok_or("WAV format chunk was not found.")?;
    let (data_offset, data_bytes) = data.ok_or("WAV data chunk was not found.")?;

    if format_code != 1 {
        return Err("USB direct PCM requires integer PCM WAV; compressed and floating-point WAV are not supported.".into());
    }
    if channels != 2 {
        return Err("USB direct PCM currently requires stereo WAV audio.".into());
    }
    if !matches!(bit_depth, 16 | 24 | 32) {
        return Err("USB direct PCM supports integer PCM at 16, 24, or 32 bits.".into());
    }
    if sample_rate == 0 || block_align != channels * (bit_depth / 8) {
        return Err("WAV sample rate or PCM block alignment is invalid.".into());
    }
    if data_bytes % block_align as u64 != 0 {
        return Err("WAV data chunk is not aligned to complete PCM frames.".into());
    }

    Ok(WavHeader { sample_rate, channels, bit_depth, block_align, data_offset, data_bytes })
}

struct WavPcmSource {
    file: File,
    header: WavHeader,
    total_frames: u64,
    frames_read: u64,
    last_gain: f32,
}

impl WavPcmSource {
    fn open(fd: i32) -> Result<Self, String> {
        if fd < 0 {
            return Err("Invalid WAV file descriptor.".into());
        }
        let duplicate = unsafe { libc::dup(fd) };
        if duplicate < 0 {
            return Err("Unable to duplicate the selected WAV document descriptor.".into());
        }
        let mut file = unsafe { File::from_raw_fd(duplicate) };
        let header = read_wav_header(&mut file)?;
        file.seek(SeekFrom::Start(header.data_offset))
            .map_err(|e| format!("Unable to position WAV data stream: {e}"))?;
        let total_frames = header.data_bytes / header.block_align as u64;
        Ok(Self { file, header, total_frames, frames_read: 0, last_gain: 1.0 })
    }

    fn read_frames(&mut self, max_frames: usize, target_gain: f32) -> Result<Vec<u8>, String> {
        if max_frames == 0 {
            return Err("WAV read size must be positive.".into());
        }
        let remaining = self.total_frames.saturating_sub(self.frames_read);
        if remaining == 0 {
            return Ok(Vec::new());
        }
        let frames = (max_frames as u64).min(remaining) as usize;
        let byte_count = frames.checked_mul(self.header.block_align as usize)
            .ok_or("WAV read size overflow")?;
        let mut bytes = vec![0_u8; byte_count];
        self.file.seek(SeekFrom::Start(
            self.header.data_offset + self.frames_read * self.header.block_align as u64
        )).map_err(|e| format!("Unable to seek WAV PCM data: {e}"))?;
        self.file.read_exact(&mut bytes)
            .map_err(|e| format!("WAV PCM data ended unexpectedly: {e}"))?;
        self.frames_read += frames as u64;
        self.apply_gain(&mut bytes, target_gain.clamp(0.0, 1.0));
        Ok(bytes)
    }

    fn apply_gain(&mut self, bytes: &mut [u8], target_gain: f32) {
        if bytes.is_empty() {
            return;
        }
        if target_gain == 1.0 && self.last_gain == 1.0 {
            return;
        }

        let sample_bytes = (self.header.bit_depth / 8) as usize;
        let sample_count = bytes.len() / sample_bytes;
        let start_gain = self.last_gain;
        for index in 0..sample_count {
            let offset = index * sample_bytes;
            let fraction = if sample_count <= 1 { 1.0 } else { index as f32 / (sample_count - 1) as f32 };
            let gain = start_gain + (target_gain - start_gain) * fraction;
            match self.header.bit_depth {
                16 => {
                    let raw = i16::from_le_bytes([bytes[offset], bytes[offset + 1]]) as f64;
                    let scaled = (raw * gain as f64).round().clamp(-32768.0, 32767.0) as i16;
                    bytes[offset..offset + 2].copy_from_slice(&scaled.to_le_bytes());
                }
                24 => {
                    let raw = (bytes[offset] as i32)
                        | ((bytes[offset + 1] as i32) << 8)
                        | ((bytes[offset + 2] as i32) << 16);
                    let signed = (raw << 8) >> 8;
                    let scaled = ((signed as f64 * gain as f64).round() as i64)
                        .clamp(-8_388_608, 8_388_607) as i32;
                    bytes[offset] = scaled as u8;
                    bytes[offset + 1] = (scaled >> 8) as u8;
                    bytes[offset + 2] = (scaled >> 16) as u8;
                }
                32 => {
                    let raw = i32::from_le_bytes(bytes[offset..offset + 4].try_into().unwrap());
                    let scaled = (raw as f64 * gain as f64).round()
                        .clamp(i32::MIN as f64, i32::MAX as f64) as i32;
                    bytes[offset..offset + 4].copy_from_slice(&scaled.to_le_bytes());
                }
                _ => unreachable!("WAV bit depth is validated when opening the source"),
            }
        }
        self.last_gain = target_gain;
    }

    fn seek_to_frame(&mut self, frame: u64) -> Result<u64, String> {
        let target = frame.min(self.total_frames);
        let offset = self.header.data_offset
            .checked_add(target.checked_mul(self.header.block_align as u64).ok_or("WAV seek overflow")?)
            .ok_or("WAV seek offset overflow")?;
        self.file.seek(SeekFrom::Start(offset))
            .map_err(|e| format!("Unable to seek WAV PCM data: {e}"))?;
        self.frames_read = target;
        Ok(target)
    }
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeOpenWavPcm(
    mut env: JNIEnv, _class: JClass, fd: jint
) -> jlong {
    match WavPcmSource::open(fd) {
        Ok(source) => Box::into_raw(Box::new(source)) as jlong,
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalArgumentException", error);
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeGetWavPcmInfo(
    mut env: JNIEnv, _class: JClass, handle: jlong
) -> jlongArray {
    if handle == 0 { return std::ptr::null_mut(); }
    let source = unsafe { &*(handle as *mut WavPcmSource) };
    let values = [
        source.header.sample_rate as i64,
        source.header.bit_depth as i64,
        source.header.channels as i64,
        source.total_frames as i64,
    ];
    let array = match env.new_long_array(values.len() as i32) {
        Ok(value) => value,
        Err(_) => return std::ptr::null_mut(),
    };
    if env.set_long_array_region(&array, 0, &values).is_err() {
        return std::ptr::null_mut();
    }
    array.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeReadWavPcmFrames(
    mut env: JNIEnv, _class: JClass, handle: jlong, max_frames: jint, gain: jni::sys::jfloat
) -> jbyteArray {
    if handle == 0 || max_frames <= 0 {
        let _ = env.throw_new("java/lang/IllegalArgumentException", "Invalid WAV PCM read arguments");
        return std::ptr::null_mut();
    }
    let source = unsafe { &mut *(handle as *mut WavPcmSource) };
    let bytes = match source.read_frames(max_frames as usize, gain) {
        Ok(value) => value,
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalStateException", error);
            return std::ptr::null_mut();
        }
    };
    let output = match env.new_byte_array(bytes.len() as i32) {
        Ok(value) => value,
        Err(_) => return std::ptr::null_mut(),
    };
    let signed: Vec<i8> = bytes.into_iter().map(|value| value as i8).collect();
    if env.set_byte_array_region(&output, 0, &signed).is_err() {
        return std::ptr::null_mut();
    }
    output.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeSeekWavPcmFrame(
    mut env: JNIEnv, _class: JClass, handle: jlong, frame: jlong
) -> jlong {
    if handle == 0 || frame < 0 { return -1; }
    let source = unsafe { &mut *(handle as *mut WavPcmSource) };
    match source.seek_to_frame(frame as u64) {
        Ok(value) => value as jlong,
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalStateException", error);
            -1
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeCloseWavPcm(
    _env: JNIEnv, _class: JClass, handle: jlong
) {
    if handle != 0 {
        unsafe { drop(Box::from_raw(handle as *mut WavPcmSource)); }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Cursor;

    fn wav_file(data: &[u8], bit_depth: u16, channels: u16) -> Vec<u8> {
        let block_align = channels * (bit_depth / 8);
        let mut bytes = Vec::new();
        bytes.extend_from_slice(b"RIFF");
        let riff_size = 4 + 8 + 16 + 8 + data.len() as u32;
        bytes.extend_from_slice(&riff_size.to_le_bytes());
        bytes.extend_from_slice(b"WAVEfmt ");
        bytes.extend_from_slice(&16_u32.to_le_bytes());
        bytes.extend_from_slice(&1_u16.to_le_bytes());
        bytes.extend_from_slice(&channels.to_le_bytes());
        bytes.extend_from_slice(&48_000_u32.to_le_bytes());
        bytes.extend_from_slice(&(48_000_u32 * block_align as u32).to_le_bytes());
        bytes.extend_from_slice(&block_align.to_le_bytes());
        bytes.extend_from_slice(&bit_depth.to_le_bytes());
        bytes.extend_from_slice(b"data");
        bytes.extend_from_slice(&(data.len() as u32).to_le_bytes());
        bytes.extend_from_slice(data);
        bytes
    }

    #[test]
    fn parses_stereo_pcm_wav_metadata() {
        let bytes = wav_file(&[0; 24], 24, 2);
        let header = read_wav_header(&mut Cursor::new(bytes)).unwrap();
        assert_eq!(header.sample_rate, 48_000);
        assert_eq!(header.channels, 2);
        assert_eq!(header.bit_depth, 24);
        assert_eq!(header.data_bytes, 24);
    }

    #[test]
    fn rejects_float_wav() {
        let mut bytes = wav_file(&[0; 8], 16, 2);
        bytes[20..22].copy_from_slice(&3_u16.to_le_bytes());
        assert!(read_wav_header(&mut Cursor::new(bytes)).unwrap_err().contains("integer PCM"));
    }

    #[test]
    fn rejects_misaligned_pcm_data() {
        let bytes = wav_file(&[0; 7], 16, 2);
        assert!(read_wav_header(&mut Cursor::new(bytes)).unwrap_err().contains("aligned"));
    }
}
