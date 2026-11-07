# Flowery UI Theme

## Visual direction

Flowery uses a restrained Soft Rose theme with simple list-based navigation and flat surfaces. The palette is applied at the application navigation root so the player, library, lyrics, DSP/equalizer, settings, welcome screen, and dialogs share the same appearance.

### Light palette

| Token | Color |
|---|---|
| Background | `#F7F6F4` |
| Surface | `#FFFFFF` |
| Surface variant | `#F0EAEC` |
| Primary / Soft Rose | `#B66D83` |
| Primary container | `#F3E1E7` |
| Main text | `#292629` |
| Secondary text | `#746A6E` |

### Dark palette

| Token | Color |
|---|---|
| Background | `#171719` |
| Surface | `#242326` |
| Surface variant | `#302B2E` |
| Primary / Soft Rose | `#D58CA3` |
| Primary container | `#603B49` |
| Main text | `#F4F0F0` |
| Secondary text | `#C8B9BF` |

Theme mode supports Light, Dark, and System. Existing stored values are migrated at read time so previously selected Light, Black, and Auto preferences remain compatible.

## Typography and script coverage

The Compose typography uses the Android sans-serif family. Android's font fallback mechanism supplies Noto script families for glyphs not covered by the primary face, allowing system-script fallback without requiring a network font download. Noto font projects are available under the SIL Open Font License 1.1; see the [Noto Fonts repositories](https://github.com/notofonts).

A fully bundled Noto Sans font asset is not included in this change. Bundling a local font requires adding the exact upstream font binary and its accompanying OFL license text; the current source-only change intentionally does not claim that a Noto TTF has been packaged.

## Equalizer

The 31-band equalizer retains its existing horizontal band layout, vertical sliders, interaction range, and dimensions. Track, tick, thumb, labels, and panel surfaces now use the active theme palette. Equalizer values and DSP behavior are unchanged.

## Components

- Settings root and About navigation use compact list rows with subtle dividers rather than large cards.
- Primary actions and selected navigation states use Soft Rose.
- Playback status colors remain semantic where appropriate.
- Dialog surfaces inherit the active Flowery color scheme.
