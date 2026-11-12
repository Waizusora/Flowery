//! UAC2 playback clock topology parser.
//!
//! Supports a direct Clock Source or one Clock Selector whose inputs are
//! direct Clock Sources. Clock Multiplier and cascaded selectors are rejected.

use serde::Serialize;
use std::collections::HashMap;

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct Uac2ClockInfo {
    pub control_interface_number: u8,
    pub streaming_interface_number: u8,
    pub terminal_link: u8,
    pub clock_source_entity_id: u8,
    pub clock_controls: u8,
    pub clock_selector_entity_id: u8,
    pub clock_selector_controls: u8,
    pub selector_clock_sources: Vec<u8>,
    pub selector_source_controls: Vec<u8>,
}

#[derive(Default)]
struct StreamingInterface {
    interface_number: u8,
    terminal_link: Option<u8>,
}

#[derive(Default)]
struct AudioControlInterface {
    interface_number: u8,
    input_terminal_clock: HashMap<u8, u8>,
    input_terminal_type: HashMap<u8, u16>,
    clock_sources: HashMap<u8, u8>,
    clock_selectors: HashMap<u8, (Vec<u8>, u8)>,
    clock_multipliers: HashMap<u8, u8>,
}

pub fn parse_playback_clock_info(descriptors: &[u8]) -> Result<Uac2ClockInfo, String> {
    parse_playback_clock_info_for_interface(descriptors, None)
}

pub fn parse_playback_clock_info_for_interface(
    descriptors: &[u8],
    streaming_interface_number: Option<u8>,
) -> Result<Uac2ClockInfo, String> {
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
                    if class == 0x01 && protocol == 0x20 && subclass == 0x02 && d[2] == 0x01 {
                        if let Some(alt) = streaming.iter_mut().rev().find(|a| a.interface_number == number) {
                            alt.terminal_link = Some(d[3]);
                        }
                    } else if class == 0x01 && protocol == 0x20 && subclass == 0x01 {
                        let Some(ac) = ac_interfaces.iter_mut().rev().find(|a| a.interface_number == number) else {
                            offset += length;
                            continue;
                        };
                        match d[2] {
                            // UAC2 Input Terminal: bTerminalID, wTerminalType, ..., bCSourceID.
                            0x02 if length >= 8 => {
                                ac.input_terminal_type.insert(d[3], u16::from_le_bytes([d[4], d[5]]));
                                ac.input_terminal_clock.insert(d[3], d[7]);
                            }
                            // UAC2 Clock Source: bClockID at 3, bmControls at 5.
                            0x0A if length >= 8 => { ac.clock_sources.insert(d[3], d[5]); }
                            // Clock Selector: bClockID, bNrInPins, baCSourceID[], bmControls, iClockSelector.
                            0x0B if length >= 7 => {
                                let count = d[4] as usize;
                                let controls_index = 5 + count;
                                if count > 0 && length >= controls_index + 2 {
                                    let inputs = d[5..controls_index].to_vec();
                                    ac.clock_selectors.insert(d[3], (inputs, d[controls_index]));
                                }
                            }
                            // Clock Multiplier is recognized but not negotiated in this stage.
                            0x0C if length >= 6 => { ac.clock_multipliers.insert(d[3], d[5]); }
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
        if streaming_interface_number.is_some_and(|number| number != alt.interface_number) {
            continue;
        }
        let Some(terminal_link) = alt.terminal_link else { continue };
        for ac in &ac_interfaces {
            if ac.input_terminal_type.get(&terminal_link) != Some(&0x0101) {
                continue;
            }
            let Some(&clock_id) = ac.input_terminal_clock.get(&terminal_link) else {
                return Err(format!("UAC2 playback terminal {terminal_link} has no Clock Source link"));
            };

            let (selector_id, selector_controls, source_ids, source_controls, direct_source) =
                if let Some((inputs, controls)) = ac.clock_selectors.get(&clock_id) {
                    if inputs.iter().any(|id| ac.clock_selectors.contains_key(id) || ac.clock_multipliers.contains_key(id)) {
                        return Err(format!("UAC2 Clock Selector {clock_id} contains a cascaded selector or multiplier; this topology is not supported yet"));
                    }
                    let mut controls_by_source = Vec::with_capacity(inputs.len());
                    for source_id in inputs {
                        let Some(&source_control) = ac.clock_sources.get(source_id) else {
                            return Err(format!("UAC2 Clock Selector {clock_id} references unresolved Clock Source {source_id}"));
                        };
                        if !matches!(source_control & 0x03, 0x01 | 0x02) {
                            return Err(format!("UAC2 Clock Source {source_id} has no usable frequency control"));
                        }
                        controls_by_source.push(source_control);
                    }
                    (clock_id, *controls, inputs.clone(), controls_by_source, 0)
                } else if ac.clock_multipliers.contains_key(&clock_id) {
                    return Err(format!("UAC2 playback clock path uses Clock Multiplier entity {clock_id}; multiplier negotiation is not implemented"));
                } else {
                    let Some(&controls) = ac.clock_sources.get(&clock_id) else {
                        return Err(format!("UAC2 playback terminal {terminal_link} references unresolved clock entity {clock_id}"));
                    };
                    if !matches!(controls & 0x03, 0x01 | 0x02) {
                        return Err(format!("UAC2 Clock Source {clock_id} does not expose usable frequency control"));
                    }
                    (0, 0, Vec::new(), Vec::new(), clock_id)
                };

            matches.push(Uac2ClockInfo {
                control_interface_number: ac.interface_number,
                streaming_interface_number: alt.interface_number,
                terminal_link,
                clock_source_entity_id: direct_source,
                clock_controls: if direct_source != 0 { ac.clock_sources[&direct_source] } else { 0 },
                clock_selector_entity_id: selector_id,
                clock_selector_controls: selector_controls,
                selector_clock_sources: source_ids,
                selector_source_controls: source_controls,
            });
        }
    }
    matches.sort_by_key(|v| (v.streaming_interface_number, v.control_interface_number, v.clock_selector_entity_id, v.clock_source_entity_id));
    matches.dedup();
    match matches.len() {
        1 => Ok(matches.remove(0)),
        0 => Err("No UAC2 playback terminal with a resolvable clock path was found".into()),
        _ => Err("Multiple UAC2 playback clock paths were found; a unique playback alternate is required".into()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn descriptor_prefix() -> Vec<u8> {
        vec![
            9,4,0,0,0,1,1,0x20,0,
            8,0x24,0x0A,7,0x03,0x02,0,0,
            8,0x24,0x0A,8,0x03,0x02,0,0,
        ]
    }

    fn append_playback_terminal(d: &mut Vec<u8>, clock_id: u8) {
        d.extend_from_slice(&[17,0x24,0x02,1,0x01,0x01,0,clock_id,2,3,0,0,0,0,0,0,0]);
        d.extend_from_slice(&[9,4,1,1,1,1,2,0x20,0]);
        d.extend_from_slice(&[16,0x24,0x01,1,0,1,0,0,0,0,2,3,0,0,0,0]);
    }

    #[test]
    fn resolves_direct_clock_source() {
        let mut d = descriptor_prefix();
        append_playback_terminal(&mut d, 7);
        let info = parse_playback_clock_info(&d).unwrap();
        assert_eq!(info.clock_source_entity_id, 7);
        assert_eq!(info.clock_selector_entity_id, 0);
        assert_eq!(info.clock_controls & 0x03, 2);
    }

    #[test]
    fn parses_selector_inputs_and_controls() {
        let mut d = descriptor_prefix();
        // Selector 9 has two inputs: Clock Sources 7 and 8; host programmable.
        d.extend_from_slice(&[9,0x24,0x0B,9,2,7,8,2,0]);
        append_playback_terminal(&mut d, 9);
        let info = parse_playback_clock_info(&d).unwrap();
        assert_eq!(info.clock_selector_entity_id, 9);
        assert_eq!(info.clock_selector_controls & 0x03, 2);
        assert_eq!(info.selector_clock_sources, vec![7,8]);
        assert_eq!(info.selector_source_controls, vec![2,2]);
    }

    #[test]
    fn rejects_selector_with_unresolved_source() {
        let mut d = descriptor_prefix();
        d.extend_from_slice(&[8,0x24,0x0B,9,2,7,99,2,0]);
        append_playback_terminal(&mut d, 9);
        assert!(parse_playback_clock_info(&d).unwrap_err().contains("unresolved Clock Source"));
    }
}
