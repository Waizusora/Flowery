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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
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
    albumArtBytes: ByteArray?,
    displayTitle: String,
    displayArtist: String,
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
                colors = CardDefaults.cardColors(
                    containerColor = if (lyricsVisible) MaterialTheme.colorScheme.primaryContainer else foreground.copy(alpha = .08f)
                )) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (lyricsVisible) {
                        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            if (lyrics.isEmpty()) {
                                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    Text("No synchronized LRC lyrics found.", color = Color(0xFF9CB2BF), textAlign = TextAlign.Center)
                                }
                            } else {
                                BoxWithConstraints(
                                    modifier = Modifier.weight(1f).fillMaxWidth()
                                ) {
                                    val edgeSpace = ((maxHeight - 88.dp) / 2).coerceAtLeast(0.dp)
                                    LazyColumn(
                                        state = lyricListState,
                                        modifier = Modifier.fillMaxSize(),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        contentPadding = PaddingValues(vertical = edgeSpace)
                                    ) {
                                    itemsIndexed(lyrics, key = { index, line -> "${line.timeMs}-$index" }) { index, line ->
                                        val active = index == activeLyricIndex
                                        Box(
                                            modifier = Modifier.fillMaxWidth().height(88.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                line.text.ifBlank { "♪" },
                                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = if (active) 1f else 0.58f),
                                                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                                fontSize = if (active) 23.sp else 18.sp,
                                                lineHeight = if (active) 30.sp else 25.sp,
                                                textAlign = TextAlign.Center,
                                                maxLines = 2,
                                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                                            )
                                        }
                                    }
                                }
                                }
                            }
                        }
                    } else {
                        val art = remember(albumArtBytes, currentTag?.pictureBase64) {
                            runCatching {
                                val bytes = albumArtBytes
                                    ?: currentTag?.pictureBase64?.takeIf { it.isNotBlank() }?.let { Base64.decode(it, Base64.DEFAULT) }
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
                Text(displayTitle.ifBlank { selectedTrack?.title ?: "Nothing playing" },
                    Modifier.fillMaxWidth().basicMarquee(iterations = Int.MAX_VALUE), color = foreground,
                    style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center, maxLines = 1)
                Text(displayArtist.ifBlank { "Unknown artist" },
                    Modifier.fillMaxWidth().basicMarquee(iterations = Int.MAX_VALUE), color = muted,
                    style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, maxLines = 1)
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

