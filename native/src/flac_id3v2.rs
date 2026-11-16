use std::io::{self, Read, Seek, SeekFrom};

const ID3V2_HEADER_LEN: u64 = 10;
const ID3V2_FOOTER_LEN: u64 = 10;

/// Physical information about a leading ID3v2 tag before a FLAC stream.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct LeadingId3v2 {
    pub(crate) major_version: u8,
    pub(crate) revision: u8,
    pub(crate) flags: u8,
    /// Synchsafe size from the ID3 header; excludes the 10-byte header and,
    /// for ID3v2.4, excludes the optional footer.
    pub(crate) payload_size: u32,
    /// Complete leading ID3v2 tag length, including header and optional footer.
    pub(crate) total_size: u64,
    /// Byte offset at which the FLAC `fLaC` signature begins.
    pub(crate) flac_offset: u64,
}

/// Inspects a leading ID3v2.2/2.3/2.4 tag and verifies the following FLAC
/// signature. Returns `Ok(None)` when the file begins directly with `fLaC`.
///
/// The reader's original stream position is restored on success and failure.
/// This function only inspects bytes; it never modifies the input.
pub(crate) fn inspect_leading_id3v2<R: Read + Seek>(
    reader: &mut R,
) -> io::Result<Option<LeadingId3v2>> {
    let original_position = reader.stream_position()?;
    let result = inspect_from_start(reader);
    let restore_result = reader.seek(SeekFrom::Start(original_position));

    match (result, restore_result) {
        (Ok(value), Ok(_)) => Ok(value),
        (Err(error), _) => Err(error),
        (Ok(_), Err(error)) => Err(error),
    }
}

fn inspect_from_start<R: Read + Seek>(
    reader: &mut R,
) -> io::Result<Option<LeadingId3v2>> {
    reader.seek(SeekFrom::Start(0))?;
    let file_len = reader.seek(SeekFrom::End(0))?;
    if file_len < 4 {
        return Err(invalid_data("File is too short to contain a FLAC signature"));
    }

    reader.seek(SeekFrom::Start(0))?;
    let mut prefix = [0u8; 4];
    reader.read_exact(&mut prefix)?;
    if &prefix == b"fLaC" {
        return Ok(None);
    }
    if &prefix[..3] != b"ID3" {
        return Err(invalid_data("File does not begin with ID3v2 or FLAC signature"));
    }
    if file_len < ID3V2_HEADER_LEN {
        return Err(invalid_data("Truncated ID3v2 header"));
    }

    reader.seek(SeekFrom::Start(0))?;
    let mut header = [0u8; 10];
    reader.read_exact(&mut header)?;

    let major_version = header[3];
    let revision = header[4];
    let flags = header[5];
    let allowed_flags = match major_version {
        2 => 0xC0,
        3 => 0xE0,
        4 => 0xF0,
        _ => return Err(invalid_data("Unsupported ID3v2 major version")),
    };
    if revision == 0xFF || flags & !allowed_flags != 0 {
        return Err(invalid_data("Invalid ID3v2 revision or reserved header flags"));
    }

    let size_bytes = &header[6..10];
    if size_bytes.iter().any(|byte| byte & 0x80 != 0) {
        return Err(invalid_data("ID3v2 tag size is not a valid synchsafe integer"));
    }
    let payload_size = ((size_bytes[0] as u32) << 21)
        | ((size_bytes[1] as u32) << 14)
        | ((size_bytes[2] as u32) << 7)
        | size_bytes[3] as u32;

    // The footer flag exists only in ID3v2.4. Its ten bytes are not included
    // in the header's tag-size field.
    let has_footer = major_version == 4 && flags & 0x10 != 0;
    let footer_size = if has_footer { ID3V2_FOOTER_LEN } else { 0 };
    let total_size = ID3V2_HEADER_LEN
        .checked_add(payload_size as u64)
        .and_then(|size| size.checked_add(footer_size))
        .ok_or_else(|| invalid_data("ID3v2 tag size overflow"))?;
    let flac_offset = total_size;

    let signature_end = flac_offset
        .checked_add(4)
        .ok_or_else(|| invalid_data("FLAC signature offset overflow"))?;
    if signature_end > file_len {
        return Err(invalid_data("ID3v2 tag extends beyond the end of the file"));
    }

    if has_footer {
        let footer_offset = ID3V2_HEADER_LEN + payload_size as u64;
        reader.seek(SeekFrom::Start(footer_offset))?;
        let mut footer = [0u8; 10];
        reader.read_exact(&mut footer)?;
        if &footer[..3] != b"3DI" || footer[3..] != header[3..] {
            return Err(invalid_data("ID3v2.4 footer does not match its header"));
        }
    }

    reader.seek(SeekFrom::Start(flac_offset))?;
    let mut signature = [0u8; 4];
    reader.read_exact(&mut signature)?;
    if &signature != b"fLaC" {
        return Err(invalid_data("ID3v2 tag is not followed by a FLAC signature"));
    }

    Ok(Some(LeadingId3v2 {
        major_version,
        revision,
        flags,
        payload_size,
        total_size,
        flac_offset,
    }))
}

fn invalid_data(message: &'static str) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, message)
}

#[cfg(test)]
mod tests {
    use super::inspect_leading_id3v2;
    use std::io::{Cursor, Seek, SeekFrom};

    fn header(major: u8, flags: u8, payload_size: u32) -> [u8; 10] {
        assert!(payload_size <= 0x0FFF_FFFF);
        [
            b'I', b'D', b'3', major, 0, flags,
            ((payload_size >> 21) & 0x7F) as u8,
            ((payload_size >> 14) & 0x7F) as u8,
            ((payload_size >> 7) & 0x7F) as u8,
            (payload_size & 0x7F) as u8,
        ]
    }

    #[test]
    fn recognizes_flac_without_a_leading_id3_tag() {
        let mut input = Cursor::new(b"fLaC\0\0\0\0".to_vec());
        input.seek(SeekFrom::Start(2)).unwrap();
        assert_eq!(inspect_leading_id3v2(&mut input).unwrap(), None);
        assert_eq!(input.stream_position().unwrap(), 2);
    }

    #[test]
    fn finds_flac_after_id3v23_and_preserves_reader_position() {
        let mut bytes = header(3, 0, 5).to_vec();
        bytes.extend_from_slice(b"12345fLaC");
        let mut input = Cursor::new(bytes);
        input.seek(SeekFrom::Start(3)).unwrap();

        let info = inspect_leading_id3v2(&mut input).unwrap().unwrap();
        assert_eq!(info.major_version, 3);
        assert_eq!(info.payload_size, 5);
        assert_eq!(info.total_size, 15);
        assert_eq!(info.flac_offset, 15);
        assert_eq!(input.stream_position().unwrap(), 3);
    }

    #[test]
    fn accounts_for_id3v24_footer_outside_header_size() {
        let tag_header = header(4, 0x10, 3);
        let mut bytes = tag_header.to_vec();
        bytes.extend_from_slice(b"abc");
        let mut footer = tag_header;
        footer[..3].copy_from_slice(b"3DI");
        bytes.extend_from_slice(&footer);
        bytes.extend_from_slice(b"fLaC");

        let info = inspect_leading_id3v2(&mut Cursor::new(bytes)).unwrap().unwrap();
        assert_eq!(info.total_size, 23);
        assert_eq!(info.flac_offset, 23);
    }

    #[test]
    fn rejects_non_synchsafe_size_bytes() {
        let mut bytes = header(4, 0, 0).to_vec();
        bytes[8] = 0x80;
        bytes.extend_from_slice(b"fLaC");
        assert_eq!(
            inspect_leading_id3v2(&mut Cursor::new(bytes)).unwrap_err().kind(),
            std::io::ErrorKind::InvalidData
        );
    }

    #[test]
    fn rejects_tag_that_does_not_lead_to_flac() {
        let mut bytes = header(4, 0, 0).to_vec();
        bytes.extend_from_slice(b"NOPE");
        assert!(inspect_leading_id3v2(&mut Cursor::new(bytes)).is_err());
    }

    #[test]
    fn rejects_mismatched_id3v24_footer() {
        let tag_header = header(4, 0x10, 0);
        let mut bytes = tag_header.to_vec();
        bytes.extend_from_slice(b"BAD");
        bytes.extend_from_slice(b"3DI\x04\x00\x00\x00\x00\x00\x00");
        bytes.extend_from_slice(b"fLaC");
        assert!(inspect_leading_id3v2(&mut Cursor::new(bytes)).is_err());
    }

    #[test]
    fn rejects_truncated_tag() {
        let mut bytes = header(4, 0, 10).to_vec();
        bytes.extend_from_slice(b"short");
        assert!(inspect_leading_id3v2(&mut Cursor::new(bytes)).is_err());
    }
}
