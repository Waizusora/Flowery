# UI Theme

Flowery uses a Soft Rose theme with simple list-based navigation and flat surfaces. The palette is applied at the application navigation root so the player, library, lyrics, settings, welcome screen, and dialogs share the same appearance. Light and dark color schemes provide corresponding rose tones.

The application navigation exposes the player, folders, lyrics, and settings. Audio settings expose three Android-managed output targets: AUX, built-in speaker, and Bluetooth.

## Now-playing lyrics panel

When the lyrics panel is visible, its card background uses `MaterialTheme.colorScheme.primaryContainer` rather than sampling the current album artwork. This means the panel follows the active Soft Rose light/dark palette. Lyric text uses `onPrimaryContainer` for contrast.

The player renders timestamped LRC lines, visually emphasizing the active line and dimming surrounding lines.
