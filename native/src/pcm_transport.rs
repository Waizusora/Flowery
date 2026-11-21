//! Shared bounded PCM byte transport construction.
//!
//! Each backend receives one producer and one consumer endpoint. A ring must
//! never be shared by multiple producers or multiple consumers.

use rtrb::{Consumer, Producer, RingBuffer};

/// Creates a bounded SPSC ring for interleaved PCM bytes.
pub(crate) fn new_pcm_byte_ring(
    capacity_bytes: usize,
) -> Result<(Producer<u8>, Consumer<u8>), String> {
    if capacity_bytes == 0 {
        return Err("PCM ring capacity must be non-zero".into());
    }
    Ok(RingBuffer::<u8>::new(capacity_bytes))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn creates_independent_single_producer_single_consumer_endpoints() {
        let (mut producer, mut consumer) = new_pcm_byte_ring(8).unwrap();
        producer.push_entire_slice(&[1, 2, 3, 4]).unwrap();
        let mut output = [0; 4];
        let (read, remainder) = consumer.pop_partial_slice(&mut output);
        assert_eq!(read, &[1, 2, 3, 4]);
        assert!(remainder.is_empty());
    }

    #[test]
    fn rejects_zero_capacity() {
        assert!(new_pcm_byte_ring(0).is_err());
    }
}
