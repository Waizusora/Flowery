use jni::objects::JClass;
use jni::sys::{jboolean, jint};
use jni::JNIEnv;
use std::sync::atomic::{AtomicU64, Ordering};

const ACTION_NEXT: jint = 0;
const ACTION_PREVIOUS: jint = 1;
const ACTION_COMPLETION: jint = 2;
const REPEAT_ALL: jint = 1;
const REPEAT_ONE: jint = 2;

static RANDOM_STATE: AtomicU64 = AtomicU64::new(0x9E37_79B9_7F4A_7C15);

fn next_random() -> u64 {
    let mut current = RANDOM_STATE.load(Ordering::Relaxed);
    loop {
        let mut next = current;
        next ^= next >> 12;
        next ^= next << 25;
        next ^= next >> 27;
        match RANDOM_STATE.compare_exchange_weak(
            current,
            next,
            Ordering::Relaxed,
            Ordering::Relaxed,
        ) {
            Ok(_) => return next.wrapping_mul(0x2545_F491_4F6C_DD1D),
            Err(observed) => current = observed,
        }
    }
}

fn shuffled_index(current: usize, size: usize) -> usize {
    let candidate = (next_random() % (size as u64 - 1)) as usize;
    if candidate >= current { candidate + 1 } else { candidate }
}

/// Returns a new index, -1 to stop, or -2 to replay the current item.
fn transition(current: i32, size: i32, shuffle: bool, repeat_mode: i32, action: i32) -> i32 {
    if size <= 0 || current < 0 || current >= size {
        return -1;
    }

    let current = current as usize;
    let size = size as usize;
    match action {
        ACTION_NEXT => {
            if shuffle && size > 1 {
                shuffled_index(current, size) as i32
            } else if current + 1 < size {
                (current + 1) as i32
            } else if repeat_mode == REPEAT_ALL {
                0
            } else {
                -1
            }
        }
        ACTION_PREVIOUS => {
            if current > 0 {
                (current - 1) as i32
            } else if repeat_mode == REPEAT_ALL {
                (size - 1) as i32
            } else {
                0
            }
        }
        ACTION_COMPLETION => {
            if repeat_mode == REPEAT_ONE {
                -2
            } else if current + 1 < size || repeat_mode == REPEAT_ALL || (shuffle && size > 1) {
                transition(current as i32, size as i32, shuffle, repeat_mode, ACTION_NEXT)
            } else {
                -1
            }
        }
        _ => -1,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeQueueTransition(
    _env: JNIEnv,
    _class: JClass,
    current: jint,
    size: jint,
    shuffle: jboolean,
    repeat_mode: jint,
    action: jint,
) -> jint {
    transition(current, size, shuffle != 0, repeat_mode, action)
}

#[cfg(test)]
mod tests {
    use super::{transition, ACTION_COMPLETION, ACTION_NEXT, ACTION_PREVIOUS};

    #[test]
    fn advances_and_stops_at_the_end_without_repeat() {
        assert_eq!(transition(0, 3, false, 0, ACTION_NEXT), 1);
        assert_eq!(transition(2, 3, false, 0, ACTION_NEXT), -1);
    }

    #[test]
    fn repeat_all_wraps_in_both_directions() {
        assert_eq!(transition(2, 3, false, 1, ACTION_NEXT), 0);
        assert_eq!(transition(0, 3, false, 1, ACTION_PREVIOUS), 2);
    }

    #[test]
    fn previous_at_the_first_item_keeps_the_first_item_without_repeat() {
        assert_eq!(transition(0, 3, false, 0, ACTION_PREVIOUS), 0);
    }

    #[test]
    fn repeat_one_replays_only_on_natural_completion() {
        assert_eq!(transition(1, 3, false, 2, ACTION_COMPLETION), -2);
        assert_eq!(transition(1, 3, false, 2, ACTION_NEXT), 2);
    }

    #[test]
    fn shuffle_never_selects_the_current_item() {
        for _ in 0..128 {
            let next = transition(1, 4, true, 0, ACTION_NEXT);
            assert!((0..4).contains(&next));
            assert_ne!(next, 1);
        }
    }

    #[test]
    fn completion_advances_when_shuffle_is_enabled() {
        for _ in 0..128 {
            let next = transition(2, 4, true, 0, ACTION_COMPLETION);
            assert!((0..4).contains(&next));
            assert_ne!(next, 2);
        }
    }

    #[test]
    fn invalid_queue_state_stops_safely() {
        assert_eq!(transition(-1, 3, false, 0, ACTION_NEXT), -1);
        assert_eq!(transition(3, 3, false, 0, ACTION_NEXT), -1);
        assert_eq!(transition(0, 0, true, 1, ACTION_PREVIOUS), -1);
    }
}
