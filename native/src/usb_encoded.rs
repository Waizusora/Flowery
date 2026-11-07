use std::collections::VecDeque;
use std::sync::{Condvar, Mutex};
use std::time::{Duration, Instant};

/// Identifies the framing contract of a compressed USB payload.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(super) enum EncodedFraming {
    /// UAC1 Type-II payload: codec-specific compressed frames, not PCM.
    Uac1TypeIi { format_tag: u16 },
    /// IEC 61937 burst payload carried by a UAC1 Type-III interface.
    Iec61937 { data_type: u16 },
}

/// An indivisible compressed frame or IEC 61937 burst.
///
/// The byte vector is preserved exactly by the queue. It is never interpreted
/// as PCM and is never split across queue entries.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(super) struct EncodedPacket {
    pub sequence: u64,
    pub presentation_time_us: Option<u64>,
    pub framing: EncodedFraming,
    pub bytes: Vec<u8>,
}

struct QueueState {
    packets: VecDeque<EncodedPacket>,
    queued_bytes: usize,
    closed: bool,
}

/// A bounded, packet-preserving queue independent from the PCM queue.
///
/// Capacity is constrained by both packet count and total payload bytes. This
/// prevents a malformed or unusually large packet from bypassing the memory
/// bound merely because it occupies one queue entry.
pub(super) struct EncodedPacketQueue {
    max_packets: usize,
    max_bytes: usize,
    state: Mutex<QueueState>,
    changed: Condvar,
}

impl EncodedPacketQueue {
    pub(super) fn new(max_packets: usize, max_bytes: usize) -> Result<Self, &'static str> {
        if max_packets == 0 || max_bytes == 0 {
            return Err("Encoded queue limits must be positive");
        }
        Ok(Self {
            max_packets,
            max_bytes,
            state: Mutex::new(QueueState {
                packets: VecDeque::with_capacity(max_packets),
                queued_bytes: 0,
                closed: false,
            }),
            changed: Condvar::new(),
        })
    }

    /// Adds one complete compressed packet without changing its contents.
    /// Returns the packet to the caller when the queue is full or closed.
    pub(super) fn offer(&self, packet: EncodedPacket) -> Result<(), EncodedPacket> {
        let mut state = self.state.lock().unwrap_or_else(|error| error.into_inner());
        let next_bytes = state.queued_bytes.checked_add(packet.bytes.len());
        if state.closed
            || state.packets.len() >= self.max_packets
            || next_bytes.is_none()
            || next_bytes.unwrap_or(usize::MAX) > self.max_bytes
        {
            return Err(packet);
        }
        state.queued_bytes = next_bytes.unwrap();
        state.packets.push_back(packet);
        self.changed.notify_all();
        Ok(())
    }

    pub(super) fn poll(&self, timeout: Duration) -> Option<EncodedPacket> {
        let deadline = Instant::now() + timeout;
        let mut state = self.state.lock().unwrap_or_else(|error| error.into_inner());
        loop {
            if let Some(packet) = state.packets.pop_front() {
                state.queued_bytes = state.queued_bytes.saturating_sub(packet.bytes.len());
                self.changed.notify_all();
                return Some(packet);
            }
            if state.closed {
                return None;
            }
            let remaining = deadline.saturating_duration_since(Instant::now());
            if remaining.is_zero() {
                return None;
            }
            let result = self.changed.wait_timeout(state, remaining);
            let (next, timed_out) = match result {
                Ok((guard, status)) => (guard, status.timed_out()),
                Err(poisoned) => {
                    let (guard, status) = poisoned.into_inner();
                    (guard, status.timed_out())
                }
            };
            state = next;
            if timed_out && state.packets.is_empty() {
                return None;
            }
        }
    }

    /// Drops queued packets at a compressed-frame boundary, for seek/flush.
    pub(super) fn clear(&self) {
        let mut state = self.state.lock().unwrap_or_else(|error| error.into_inner());
        state.packets.clear();
        state.queued_bytes = 0;
        self.changed.notify_all();
    }

    pub(super) fn close(&self) {
        let mut state = self.state.lock().unwrap_or_else(|error| error.into_inner());
        state.closed = true;
        self.changed.notify_all();
    }

    #[cfg(test)]
    fn usage(&self) -> (usize, usize) {
        let state = self.state.lock().unwrap_or_else(|error| error.into_inner());
        (state.packets.len(), state.queued_bytes)
    }
}

#[cfg(test)]
mod tests {
    use super::{EncodedFraming, EncodedPacket, EncodedPacketQueue};
    use std::time::Duration;

    fn packet(sequence: u64, bytes: &[u8]) -> EncodedPacket {
        EncodedPacket {
            sequence,
            presentation_time_us: Some(sequence * 20_000),
            framing: EncodedFraming::Uac1TypeIi { format_tag: 0x1002 },
            bytes: bytes.to_vec(),
        }
    }

    #[test]
    fn queue_preserves_complete_packet_bytes_and_order() {
        let queue = EncodedPacketQueue::new(2, 8).unwrap();
        queue.offer(packet(0, &[0x0b, 0x77, 1, 2])).unwrap();
        queue.offer(packet(1, &[3, 4, 5, 6])).unwrap();
        assert_eq!(queue.poll(Duration::ZERO).unwrap().bytes, [0x0b, 0x77, 1, 2]);
        assert_eq!(queue.poll(Duration::ZERO).unwrap().sequence, 1);
        assert_eq!(queue.usage(), (0, 0));
    }

    #[test]
    fn queue_rejects_packet_and_byte_capacity_overflow_without_truncation() {
        let queue = EncodedPacketQueue::new(1, 4).unwrap();
        queue.offer(packet(0, &[1, 2, 3, 4])).unwrap();
        let rejected = queue.offer(packet(1, &[5])).unwrap_err();
        assert_eq!(rejected.bytes, [5]);
        assert_eq!(queue.usage(), (1, 4));
        let oversized = EncodedPacketQueue::new(2, 3).unwrap();
        assert_eq!(oversized.offer(packet(0, &[1, 2, 3, 4])).unwrap_err().bytes.len(), 4);
    }

    #[test]
    fn flush_discards_only_queued_encoded_packets() {
        let queue = EncodedPacketQueue::new(4, 32).unwrap();
        queue.offer(packet(0, &[1, 2])).unwrap();
        queue.clear();
        assert_eq!(queue.poll(Duration::ZERO), None);
        assert_eq!(queue.usage(), (0, 0));
    }

    #[test]
    fn close_rejects_new_packets_and_wakes_empty_polls() {
        let queue = EncodedPacketQueue::new(2, 32).unwrap();
        queue.close();
        assert!(queue.offer(packet(0, &[1])).is_err());
        assert_eq!(queue.poll(Duration::ZERO), None);
    }

    #[test]
    fn type_iii_packet_metadata_is_distinct_from_type_ii() {
        let mut burst = packet(0, &[0x72, 0xf8, 0x1f, 0x4e]);
        burst.framing = EncodedFraming::Iec61937 { data_type: 0x01 };
        assert_eq!(burst.framing, EncodedFraming::Iec61937 { data_type: 0x01 });
    }
}
