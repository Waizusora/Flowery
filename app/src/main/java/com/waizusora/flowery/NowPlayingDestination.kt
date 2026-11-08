package com.waizusora.flowery

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight\nimport androidx.compose.ui.text.SpanStyle\nimport androidx.compose.ui.text.buildAnnotatedString\nimport androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun NowPlayingDestination(
    foreground: Color,
    muted: Color,
    lyricsVisible: Boolean,
    lyricListState: LazyListState,
    lyrics: List<LyricLine>,
    activeLyricIndex: Int,
    currentTag: AudioTag?,
    selectedTrack: AudioTrackItem?,
    shuffleEnabled: Boolean,
    isPlaying: Boolean,
    repeatMode: Int,
    positionMs: Long,
    durationMs: Long,
    status: String,
    onShuffle: () -> Unit,
    onPrevious: () -> Unit,
    onTogglePlayback: () -> Unit,
    onNext: () -> Unit,
    onRepeat: () -> Unit,
    onSeek: (Long) -> Unit,
    onInspect: (AudioTrackItem) -> Unit,
    onEdit: () -> Unit
) {
            Card(Modifier.fillMaxWidth().aspectRatio(1f), shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = foreground.copy(alpha = .08f))) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (lyricsVisible) {
                        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "LYRICS LRC",
                                color = foreground,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                            if (lyrics.isEmpty()) {
                                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    Text("No synchronized LRC lyrics found.", color = muted, textAlign = TextAlign.Center)
                                }
                            } else {
                                LazyColumn(
                                    state = lyricListState,
                                    modifier = Modifier.weight(1f).fillMaxWidth(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    contentPadding = PaddingValues(vertical = 120.dp)
                                ) {
                                    item(key = "lyrics-spacer-top") { Spacer(Modifier.height(120.dp)) }
                                    itemsIndexed(lyrics, key = { index, line -> "${line.timeMs}-$index" }) { index, line ->
                                        val active = index == activeLyricIndex
                                        val activeWordIndex = if (active) line.words.indexOfLast { it.timeMs <= positionMs } else -1
                                        val displayedText = if (line.words.isEmpty()) {
                                            buildAnnotatedString { append(line.text.ifBlank { "♪" }) }
                                        } else {
                                            buildAnnotatedString {
                                                line.words.forEachIndexed { wordIndex, word ->
                                                    if (active && wordIndex == activeWordIndex) {
                                                        withStyle(SpanStyle(
                                                            color = MaterialTheme.colorScheme.primary,
                                                            fontWeight = FontWeight.Bold
                                                        )) { append(word.text) }
                                                    } else append(word.text)
                                                }
                                            }
                                        }
                                        Text(
                                            displayedText,
                                            color = if (active && line.words.isEmpty()) MaterialTheme.colorScheme.primary else muted,
                                            fontWeight = if (active && line.words.isEmpty()) FontWeight.Bold else FontWeight.Normal,
                                            fontSize = if (active) 21.sp else 16.sp,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.fillMaxWidth()
                                                .padding(horizontal = 8.dp, vertical = 9.dp)
                                                .then(if (active) Modifier.background(
                                                    MaterialTheme.colorScheme.primary.copy(alpha = .13f), RoundedCornerShape(12.dp)
                                                ) else Modifier)
                                                .padding(horizontal = 10.dp, vertical = 7.dp)
                                        )
                                    }
                                    item(key = "lyrics-spacer-bottom") { Spacer(Modifier.height(120.dp)) }
                                }
                            }
                            Text(
                                "Synced Lyric",
                                color = muted,
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    } else {
                        val art = remember(currentTag?.pictureBase64) {
                            runCatching {
                                val bytes = currentTag?.pictureBase64?.takeIf { it.isNotBlank() }?.let { Base64.decode(it, Base64.DEFAULT) }
                                bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
                            }.getOrNull()
                        }
                        if (art != null) Image(art, "Album artwork", Modifier.fillMaxSize(),
                            contentScale = androidx.compose.ui.layout.ContentScale.Fit)
                        else Icon(Icons.Default.MusicNote, "No artwork", tint = muted, modifier = Modifier.size(80.dp))

                    }
                }
            }
            Column(Modifier.fillMaxWidth().padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                val virtualTrackTitle = selectedTrack?.takeIf { it.cuePlaylist != null || it.playlistName != null }?.title
                Text(virtualTrackTitle ?: currentTag?.title?.ifBlank { selectedTrack?.title ?: "Nothing playing" } ?: selectedTrack?.title ?: "Nothing playing",
                    Modifier.fillMaxWidth().basicMarquee(iterations = Int.MAX_VALUE), color = foreground,
                    style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center, maxLines = 1)
                Text(currentTag?.artist?.ifBlank { "Unknown artist" } ?: "Unknown artist",
                    Modifier.fillMaxWidth().basicMarquee(iterations = Int.MAX_VALUE), color = muted,
                    style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, maxLines = 1)
                if (status.contains("Bit Perfect")) {
                    Text(
                        "BIT PERFECT",
                        color = Color(0xFF2EAD68),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onShuffle) { Icon(Icons.Default.Shuffle, "Shuffle", tint = if (shuffleEnabled) MaterialTheme.colorScheme.primary else foreground) }
                IconButton(onClick = onPrevious) { Icon(Icons.Default.SkipPrevious, "Previous", tint = foreground, modifier = Modifier.size(30.dp)) }
                IconButton(onClick = onTogglePlayback, Modifier.size(76.dp).background(foreground.copy(alpha = .13f), RoundedCornerShape(40.dp))) {
                    Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Play or pause", tint = foreground, modifier = Modifier.size(46.dp))
                }
                IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, "Next", tint = foreground, modifier = Modifier.size(30.dp)) }
                IconButton(onClick = onRepeat) { Icon(if (repeatMode == 2) Icons.Default.RepeatOne else Icons.Default.Repeat,
                    "Repeat", tint = if (repeatMode == 0) foreground else MaterialTheme.colorScheme.primary) }
            }
            if (selectedTrack != null) {
                val seekDurationMs = durationMs.coerceAtLeast(1L)
                Slider(
                    value = positionMs.coerceIn(0L, seekDurationMs).toFloat(),
                    onValueChange = { if (durationMs > 0L) onSeek(it.toLong()) },
                    valueRange = 0f..seekDurationMs.toFloat(),
                    enabled = durationMs > 0L
                )
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    IconButton(
                        onClick = { selectedTrack?.let(onInspect) },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(Icons.Default.Info, "File information", tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(30.dp))
                    }
                    Text(
                        "${formatPlaybackTime(positionMs)} / ${formatPlaybackTime(durationMs)}",
                        color = foreground,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center
                    )
                    IconButton(
                        onClick = onEdit,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(Icons.Default.Edit, "Edit audio tags", tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(30.dp))
                    }
                }
            }
}
