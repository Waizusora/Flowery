//! UAC2 playback clock topology parser.
//!
//! Resolves arbitrary Clock Selector / Clock Multiplier chains to Clock Sources.
//! Cycles, duplicate entities, dangling references and unsupported controls fail closed.

use serde::Serialize;
use std::collections::{HashMap, HashSet};

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct Uac2ClockPath {
    pub source_entity_id: u8,
    pub source_controls: u8,
    /// Ordered clock entity chain from terminal clock link to source.
    pub clock_entities: Vec<u8>,
    /// Selector entities from terminal towards the source, with one-based selected pin.
    pub selectors: Vec<Uac2SelectorStep>,
    /// Multiplier entities from terminal towards the source.
    pub multipliers: Vec<Uac2MultiplierStep>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct Uac2SelectorStep {
    pub entity_id: u8,
    pub controls: u8,
    pub input_sources: Vec<u8>,
    pub selected_pin: u8,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct Uac2MultiplierStep {
    pub entity_id: u8,
    pub controls: u8,
    pub input_clock_id: u8,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct Uac2ClockInfo {
    pub control_interface_number: u8,
    pub streaming_interface_number: u8,
    pub terminal_link: u8,
    // Legacy summary fields retained for existing Kotlin callers.
    pub clock_source_entity_id: u8,
    pub clock_controls: u8,
    pub clock_selector_entity_id: u8,
    pub clock_selector_controls: u8,
    pub selector_clock_sources: Vec<u8>,
    pub selector_source_controls: Vec<u8>,
    pub clock_multiplier_entity_id: u8,
    pub clock_multiplier_controls: u8,
    // All valid source paths, including choices at every selector in a chain.
    pub clock_paths: Vec<Uac2ClockPath>,
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
    clock_multipliers: HashMap<u8, (u8, u8)>,
}

impl AudioControlInterface {
    fn resolve_paths(
        &self,
        entity_id: u8,
        selectors: &mut Vec<Uac2SelectorStep>,
        multipliers: &mut Vec<Uac2MultiplierStep>,
        clock_entities: &mut Vec<u8>,
        visited: &mut HashSet<u8>,
        output: &mut Vec<Uac2ClockPath>,
    ) -> Result<(), String> {
        if !visited.insert(entity_id) {
            return Err(format!("UAC2 clock topology contains a cycle at entity {entity_id}"));
        }
        clock_entities.push(entity_id);

        if let Some(&controls) = self.clock_sources.get(&entity_id) {
            if !matches!(controls & 0x03, 0x01 | 0x02) {
                return Err(format!("UAC2 Clock Source {entity_id} has no usable frequency control"));
            }
            output.push(Uac2ClockPath {
                source_entity_id: entity_id,
                source_controls: controls,
                clock_entities: clock_entities.clone(),
                selectors: selectors.clone(),
                multipliers: multipliers.clone(),
            });
        } else if let Some((inputs, controls)) = self.clock_selectors.get(&entity_id) {
            if inputs.is_empty() || (controls & 0x03) == 0 {
                return Err(format!("UAC2 Clock Selector {entity_id} has no usable input control"));
            }
            for (index, &input_id) in inputs.iter().enumerate() {
                selectors.push(Uac2SelectorStep {
                    entity_id,
                    controls: *controls,
                    input_sources: inputs.clone(),
                    selected_pin: (index + 1) as u8,
                });
                self.resolve_paths(input_id, selectors, multipliers, clock_entities, visited, output)?;
                selectors.pop();
            }
        } else if let Some(&(input_id, controls)) = self.clock_multipliers.get(&entity_id) {
            // bmControls bits 1:0 and 3:2 are numerator and denominator controls.
            if (controls & 0x03) != 0x01 || (controls & 0x0C) != 0x04 {
                return Err(format!("UAC2 Clock Multiplier {entity_id} does not expose numerator and denominator controls"));
            }
            multipliers.push(Uac2MultiplierStep { entity_id, controls, input_clock_id: input_id });
            self.resolve_paths(input_id, selectors, multipliers, clock_entities, visited, output)?;
            multipliers.pop();
        } else {
            return Err(format!("UAC2 clock entity {entity_id} references an unresolved entity"));
        }

        clock_entities.pop();
        visited.remove(&entity_id);
        Ok(())
    }

    fn validate_all_references(&self) -> Result<(), String> {
        let mut ids = HashSet::new();
        for id in self.clock_sources.keys()
            .chain(self.clock_selectors.keys())
            .chain(self.clock_multipliers.keys())
        {
            if *id == 0 || !ids.insert(*id) {
                return Err(format!("UAC2 AudioControl interface {} has duplicate or zero clock entity ID {id}", self.interface_number));
            }
        }
        for (&id, (inputs, _)) in &self.clock_selectors {
            if inputs.is_empty() || inputs.iter().any(|input| !ids.contains(input)) {
                return Err(format!("UAC2 Clock Selector {id} has an empty or unresolved input reference"));
            }
        }
        for (&id, (input, _)) in &self.clock_multipliers {
            if !ids.contains(input) {
                return Err(format!("UAC2 Clock Multiplier {id} references unresolved clock entity {input}"));
            }
        }
        Ok(())
    }
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
                            0x02 if length >= 8 => {
                                ac.input_terminal_type.insert(d[3], u16::from_le_bytes([d[4], d[5]]));
                                ac.input_terminal_clock.insert(d[3], d[7]);
                            }
                            0x0A if length >= 8 => {
                                if ac.clock_sources.insert(d[3], d[5]).is_some() {
                                    return Err(format!("Duplicate UAC2 Clock Source entity ID {}", d[3]));
                                }
                            }
                            0x0B if length >= 7 => {
                                let count = d[4] as usize;
                                let controls_index = 5 + count;
                                if count > 0 && length >= controls_index + 2 {
                                    if ac.clock_selectors.insert(d[3], (d[5..controls_index].to_vec(), d[controls_index])).is_some() {
                                        return Err(format!("Duplicate UAC2 Clock Selector entity ID {}", d[3]));
                                    }
                                }
                            }
                            0x0C if length >= 7 => {
                                if ac.clock_multipliers.insert(d[3], (d[4], d[5])).is_some() {
                                    return Err(format!("Duplicate UAC2 Clock Multiplier entity ID {}", d[3]));
                                }
                            }
                            _ => {}
                        }
                    }
                }
            }
            _ => {}
        }
        offset += length;
    }

    for ac in &ac_interfaces { ac.validate_all_references()?; }

    let mut matches = Vec::new();
    for alt in streaming {
        if streaming_interface_number.is_some_and(|number| number != alt.interface_number) { continue; }
        let Some(terminal_link) = alt.terminal_link else { continue };
        for ac in &ac_interfaces {
            if ac.input_terminal_type.get(&terminal_link) != Some(&0x0101) { continue; }
            let Some(&clock_id) = ac.input_terminal_clock.get(&terminal_link) else {
                return Err(format!("UAC2 playback terminal {terminal_link} has no clock link"));
            };
            let mut paths = Vec::new();
            ac.resolve_paths(
                clock_id,
                &mut Vec::new(),
                &mut Vec::new(),
                &mut Vec::new(),
                &mut HashSet::new(),
                &mut paths,
            )?;
            if paths.is_empty() { return Err(format!("UAC2 playback terminal {terminal_link} has no clock source path")); }
            // Legacy fields describe the first candidate; the complete path set is authoritative.
            let first = &paths[0];
            let first_selector = first.selectors.first();
            let first_multiplier = first.multipliers.first();
            matches.push(Uac2ClockInfo {
                control_interface_number: ac.interface_number,
                streaming_interface_number: alt.interface_number,
                terminal_link,
                clock_source_entity_id: first.source_entity_id,
                clock_controls: first.source_controls,
                clock_selector_entity_id: first_selector.map_or(0, |s| s.entity_id),
                clock_selector_controls: first_selector.map_or(0, |s| s.controls),
                selector_clock_sources: first_selector.map_or_else(Vec::new, |s| s.input_sources.clone()),
                selector_source_controls: first_selector.map_or_else(Vec::new, |_| Vec::new()),
                clock_multiplier_entity_id: first_multiplier.map_or(0, |m| m.entity_id),
                clock_multiplier_controls: first_multiplier.map_or(0, |m| m.controls),
                clock_paths: paths,
            });
        }
    }
    matches.sort_by_key(|v| (v.streaming_interface_number, v.control_interface_number));
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
        assert_eq!(info.clock_paths.len(), 1);
        assert_eq!(info.clock_paths[0].source_entity_id, 7);
        assert_eq!(info.clock_paths[0].clock_entities, vec![9, 10, 11, 7]);
    }

    #[test]
    fn resolves_selector_multiplier_source_chain() {
        let mut d = descriptor_prefix();
        d.extend_from_slice(&[9,0x24,0x0B,9,2,10,11,2,0]);
        d.extend_from_slice(&[7,0x24,0x0C,10,7,0x05,0]);
        d.extend_from_slice(&[7,0x24,0x0C,11,8,0x05,0]);
        append_playback_terminal(&mut d, 9);
        let info = parse_playback_clock_info(&d).unwrap();
        assert_eq!(info.clock_paths.len(), 2);
        assert_eq!(info.clock_paths[0].selectors[0].entity_id, 9);
        assert_eq!(info.clock_paths[0].multipliers[0].entity_id, 10);
        assert_eq!(info.clock_paths[0].source_entity_id, 7);
        assert_eq!(info.clock_paths[1].multipliers[0].entity_id, 11);
        assert_eq!(info.clock_paths[1].source_entity_id, 8);
    }

    #[test]
    fn resolves_multiple_selector_and_multiplier_hops() {
        let mut d = descriptor_prefix();
        d.extend_from_slice(&[8,0x24,0x0B,9,1,10,2,0]);
        d.extend_from_slice(&[7,0x24,0x0C,10,11,0x05,0]);
        d.extend_from_slice(&[7,0x24,0x0C,11,7,0x05,0]);
        append_playback_terminal(&mut d, 9);
        let info = parse_playback_clock_info(&d).unwrap();
        assert_eq!(info.clock_paths[0].multipliers.len(), 2);
        assert_eq!(info.clock_paths[0].source_entity_id, 7);
    }

    #[test]
    fn resolves_nested_selector_and_multiplier_entity_order() {
        let mut d = descriptor_prefix();
        d.extend_from_slice(&[8,0x24,0x0B,9,1,10,2,0]);
        d.extend_from_slice(&[7,0x24,0x0C,10,7,0x05,0]);
        d.extend_from_slice(&[8,0x24,0x0B,12,2,9,8,2,0]);
        append_playback_terminal(&mut d, 12);

        let info = parse_playback_clock_info(&d).unwrap();
        let paths = &info.clock_paths;
        assert_eq!(paths.len(), 3);
        assert_eq!(paths[0].clock_entities, vec![12, 9, 10, 7]);
        assert_eq!(paths[0].selectors.iter().map(|step| step.entity_id).collect::<Vec<_>>(), vec![12, 9]);
        assert_eq!(paths[0].multipliers.iter().map(|step| step.entity_id).collect::<Vec<_>>(), vec![10]);
        assert_eq!(paths[1].clock_entities, vec![12, 9, 7]);
        assert_eq!(paths[2].clock_entities, vec![12, 8]);
    }

    #[test]
    fn rejects_clock_topology_cycle() {
        let mut d = descriptor_prefix();
        d.extend_from_slice(&[7,0x24,0x0C,10,11,0x05,0]);
        d.extend_from_slice(&[7,0x24,0x0C,11,10,0x05,0]);
        append_playback_terminal(&mut d, 10);
        assert!(parse_playback_clock_info(&d).unwrap_err().contains("cycle"));
    }

    #[test]
    fn rejects_dangling_selector_reference() {
        let mut d = descriptor_prefix();
        d.extend_from_slice(&[8,0x24,0x0B,9,1,99,2,0]);
        append_playback_terminal(&mut d, 9);
        assert!(parse_playback_clock_info(&d).unwrap_err().contains("unresolved input"));
    }

    #[test]
    fn rejects_truncated_descriptor() {
        let mut d = descriptor_prefix();
        d.extend_from_slice(&[9,0x24,0x0C,10,7]);
        assert!(parse_playback_clock_info(&d).is_err());
    }

    #[test]
    fn rejects_duplicate_entity_ids() {
        let mut d = descriptor_prefix();
        d.extend_from_slice(&[7,0x24,0x0C,7,8,0x05,0]);
        append_playback_terminal(&mut d, 7);
        assert!(parse_playback_clock_info(&d).unwrap_err().contains("duplicate"));
    }
}
