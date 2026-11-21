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
    fn ring_wraparound_preserves_fifo_order_and_restores_capacity() {
        let (mut producer, mut consumer) = new_pcm_byte_ring(8).unwrap();
        producer.push_entire_slice(&[0, 1, 2, 3, 4, 5]).unwrap();
        let mut prefix = [0; 5];
        let (read, remainder) = consumer.pop_partial_slice(&mut prefix);
        assert_eq!(read, &[0, 1, 2, 3, 4]);
        assert!(remainder.is_empty());
        producer.push_entire_slice(&[6, 7, 8, 9, 10, 11]).unwrap();
        assert_eq!(producer.slots(), 1);
        let mut rest = [0; 7];
        let (read, remainder) = consumer.pop_partial_slice(&mut rest);
        assert_eq!(read, &[5, 6, 7, 8, 9, 10, 11]);
        assert!(remainder.is_empty());
        assert!(consumer.is_empty());
        assert_eq!(producer.slots(), 8);
    }

    #[test]
    fn rejects_zero_capacity() {
        assert!(new_pcm_byte_ring(0).is_err());
    }
}
