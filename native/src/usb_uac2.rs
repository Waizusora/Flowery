//! UAC2 clock topology parsing for USB Audio playback.
//!
//! The first negotiation stage supports a direct Clock Source connection.
//! Clock Selector and Clock Multiplier paths are reported as unsupported rather
//! than guessing a source entity or selecting an input implicitly.

use serde::Serialize;

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct Uac2ClockInfo {
    pub control_interface_number: u8,
    pub streaming_interface_number: u8,
    pub terminal_link: u8,
    pub clock_source_entity_id: u8,
    pub clock_controls: u8,
}

#[derive(Default)]
struct StreamingInterface {
    interface_number: u8,
    terminal_link: Option<u8>,
}

#[derive(Default)]
struct AudioControlInterface {
    interface_number: u8,
    input_terminal_clock: std::collections::HashMap<u8, u8>,
    input_terminal_type: std::collections::HashMap<u8, u16>,
    clock_sources: std::collections::HashMap<u8, u8>,
    clock_selectors: std::collections::HashSet<u8>,
    clock_multipliers: std::collections::HashSet<u8>,
}

/// Resolves the playback terminal's clock source from UAC2 class-specific
/// descriptors. Only a direct Clock Source connection is currently supported.
pub fn parse_playback_clock_info(descriptors: &[u8]) -> Result<Uac2ClockInfo, String> {
    let mut offset = 0usize;
    let mut current_interface: Option<(u8, u8, u8, u8)> = None;
    let mut ac_interfaces = Vec::<AudioControlInterface>::new();
    let mut streaming = Vec::<StreamingInterface>::new();

    while offset < descriptors.len() {
        if descriptors.len() - offset < 2 {
            return Err(format!("Truncated descriptor header at byte {offset}"));
        }
        let length = descriptors[offset] as usize;
        let kind = descriptors[offset + 1];
        if length < 2 || offset + length > descriptors.len() {
            return Err(format!("Invalid descriptor length {length} at byte {offset}"));
        }
        let d = &descriptors[offset..offset + length];
        match kind {
            0x04 if length >= 9 => {
                let number = d[2];
                let class = d[5];
                let subclass = d[6];
                let protocol = d[7];
                current_interface = Some((number, class, subclass, protocol));
                if class == 0x01 && subclass == 0x01 && protocol == 0x20 {
                    ac_interfaces.push(AudioControlInterface {
                        interface_number: number,
                        ..AudioControlInterface::default()
                    });
                } else if class == 0x01 && subclass == 0x02 && protocol == 0x20 {
                    streaming.push(StreamingInterface { interface_number: number, ..StreamingInterface::default() });
                }
            }
            0x24 if length >= 4 => {
                if let Some((number, class, subclass, protocol)) = current_interface {
                    if class != 0x01 || protocol != 0x20 { offset += length; continue; }
                    if subclass == 0x02 && d[2] == 0x01 && length >= 4 {
                        if let Some(alt) = streaming.iter_mut().rev().find(|a| a.interface_number == number) {
                            alt.terminal_link = Some(d[3]);
                        }
                    } else if subclass == 0x01 {
                        let Some(ac) = ac_interfaces.iter_mut().rev().find(|a| a.interface_number == number) else {
                            offset += length;
                            continue;
                        };
                        match d[2] {
                            // UAC2 Input Terminal: bTerminalID, wTerminalType, ..., bCSourceID.
                            0x02 if length >= 8 => {
                                let terminal_id = d[3];
                                let terminal_type = u16::from_le_bytes([d[4], d[5]]);
                                ac.input_terminal_type.insert(terminal_id, terminal_type);
                                ac.input_terminal_clock.insert(terminal_id, d[7]);
                            }
                            // UAC2 Clock Source: bClockID at 3, bmControls at 5.
                            0x0A if length >= 8 => {
                                ac.clock_sources.insert(d[3], d[5]);
                            }
                            // Clock Selector and Multiplier are recognized but not negotiated yet.
                            0x0B if length >= 7 => { ac.clock_selectors.insert(d[3]); }
                            0x0C if length >= 6 => { ac.clock_multipliers.insert(d[3]); }
                            _ => {}
                        }
                    }
                }
            }
            _ => {}
        }
        offset += length;
    }

    let mut matches = Vec::new();
    for alt in streaming {
        let Some(terminal_link) = alt.terminal_link else { continue };
        for ac in &ac_interfaces {
            if ac.input_terminal_type.get(&terminal_link) != Some(&0x0101) {
                continue;
            }
            let Some(&clock_id) = ac.input_terminal_clock.get(&terminal_link) else {
                return Err(format!("UAC2 playback terminal {terminal_link} has no Clock Source link"));
            };
            if ac.clock_selectors.contains(&clock_id) {
                return Err(format!("UAC2 playback clock path uses Clock Selector entity {clock_id}; selector negotiation is not implemented"));
            }
            if ac.clock_multipliers.contains(&clock_id) {
                return Err(format!("UAC2 playback clock path uses Clock Multiplier entity {clock_id}; multiplier negotiation is not implemented"));
            }
            let Some(&controls) = ac.clock_sources.get(&clock_id) else {
                return Err(format!("UAC2 playback terminal {terminal_link} references unresolved clock entity {clock_id}"));
            };
            matches.push(Uac2ClockInfo {
                control_interface_number: ac.interface_number,
                streaming_interface_number: alt.interface_number,
                terminal_link,
                clock_source_entity_id: clock_id,
                clock_controls: controls,
            });
        }
    }
    matches.sort_by_key(|v| (v.streaming_interface_number, v.control_interface_number, v.clock_source_entity_id));
    matches.dedup();
    match matches.len() {
        1 => {
            let info = matches.remove(0);
            match info.clock_controls & 0x03 {
                0x01 | 0x02 => Ok(info),
                0x00 => Err(format!("UAC2 Clock Source {} does not expose frequency control", info.clock_source_entity_id)),
                _ => Err(format!("UAC2 Clock Source {} has reserved frequency-control attributes", info.clock_source_entity_id)),
            }
        }
        0 => Err("No UAC2 playback terminal with a resolvable direct Clock Source was found".into()),
        _ => Err("Multiple UAC2 playback clock paths were found; a unique playback alternate is required".into()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn resolves_direct_clock_source_from_playback_terminal() {
        let d = [
            9,4,0,0,0,1,1,0x20,0, // AC interface
            8,0x24,0x0A,7,0x03,0x02,0,0, // Clock Source 7, host programmable frequency
            17,0x24,0x02,1,0x01,0x01,0,7,2,3,0,0,0,0,0,0,0, // USB Streaming Input Terminal 1 -> clock 7
            9,4,1,1,1,1,2,0x20,0, // AS interface alt 1
            16,0x24,0x01,1,0,1,0,0,0,0,2,3,0,0,0,0, // AS_GENERAL terminal link 1
        ];
        let info = parse_playback_clock_info(&d).unwrap();
        assert_eq!(info.control_interface_number, 0);
        assert_eq!(info.streaming_interface_number, 1);
        assert_eq!(info.terminal_link, 1);
        assert_eq!(info.clock_source_entity_id, 7);
        assert_eq!(info.clock_controls & 0x03, 2);
    }

    #[test]
    fn rejects_clock_selector_without_guessing_input() {
        let d = [
            9,4,0,0,0,1,1,0x20,0,
            8,0x24,0x0B,9,2,1,2,0,
            17,0x24,0x02,1,0x01,0x01,0,9,2,3,0,0,0,0,0,0,0,
            9,4,1,1,1,1,2,0x20,0,
            16,0x24,0x01,1,0,1,0,0,0,0,2,3,0,0,0,0,
        ];
        assert!(parse_playback_clock_info(&d).unwrap_err().contains("Clock Selector"));
    }
}
