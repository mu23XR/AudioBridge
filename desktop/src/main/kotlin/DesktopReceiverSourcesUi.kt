import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Each source exposes its own output/retry state; this panel owns no playback lifecycle. */
@Composable
fun DesktopReceiverSourcesSection(
    sources: List<DesktopReceiverSourceState>,
    remoteSyncEnabled: Boolean,
    onGain: (String, Float) -> Unit,
    onMute: (String, Boolean) -> Unit,
    onLatency: (String, Boolean, Int) -> Unit,
    onRetry: (String) -> Unit,
    onRemove: (String) -> Unit,
    onRemotePercent: (String, Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource("receiver_sources_title"), style = MaterialTheme.typography.titleMedium)
        for (source in sources) key(source.id) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${source.peer.ip}:${source.peer.port}", style = MaterialTheme.typography.titleMedium)
                    Text(when {
                        source.paused -> stringResource("receiver_source_paused")
                        else -> source.status
                    }, style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource("receiver_source_mute"), Modifier.weight(1f))
                        Switch(source.muted, { onMute(source.id, it) })
                    }
                    Text(stringResource("receiver_source_gain", DesktopVolumeScale.percent(source.gain, 1f)))
                    Slider(source.gain, { onGain(source.id, it) }, valueRange = 0f..1f)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource("latency_adaptive"), Modifier.weight(1f))
                        Switch(source.adaptive, { onLatency(source.id, it, source.manualLatencyMs) })
                    }
                    if (!source.adaptive) {
                        var input by remember(source.id, source.manualLatencyMs) { mutableStateOf(source.manualLatencyMs.toString()) }
                        val parsed = input.toIntOrNull()?.takeIf { it in 20..400 }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(input, { input = it }, label = { Text(stringResource("latency_value")) },
                                singleLine = true, isError = parsed == null, modifier = Modifier.weight(1f))
                            Button(onClick = { parsed?.let { onLatency(source.id, false, it) } }, enabled = parsed != null) {
                                Text(stringResource("apply"))
                            }
                        }
                        var target by remember(source.id, source.manualLatencyMs) { mutableFloatStateOf(source.manualLatencyMs.toFloat()) }
                        Slider(target, { target = it }, onValueChangeFinished = {
                            onLatency(source.id, false, target.roundToInt())
                        }, valueRange = 20f..400f)
                    }
                    Text(stringResource("receiver_source_buffer", source.targetMs, source.bufferedMs),
                        style = MaterialTheme.typography.bodySmall)
                    if (remoteSyncEnabled) {
                        val remote = source.remoteVolume
                        when (remote.support) {
                            RemoteVolumeSupport.SUPPORTED -> {
                                var percent by remember(source.id) { mutableFloatStateOf((remote.percent ?: 50).toFloat()) }
                                var dragging by remember(source.id) { mutableStateOf(false) }
                                LaunchedEffect(remote.percent, remote.pending, remote.error) {
                                    if (!dragging) remote.percent?.let { percent = it.toFloat() }
                                }
                                Text(stringResource("receiver_source_remote", remote.percent?.toString() ?: "—"))
                                Slider(percent, { dragging = true; percent = it }, valueRange = 0f..100f,
                                    enabled = remote.percent != null && !remote.pending && source.connected,
                                    onValueChangeFinished = {
                                        dragging = false
                                        onRemotePercent(source.id, percent.roundToInt())
                                    })
                                if (remote.pending) Text(stringResource("receiver_source_remote_pending"), style = MaterialTheme.typography.bodySmall)
                                remote.error?.let { Text(stringResource("receiver_source_remote_failed"),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                            }
                            RemoteVolumeSupport.UNSUPPORTED -> Text(stringResource("receiver_source_remote_unsupported"), style = MaterialTheme.typography.bodySmall)
                            RemoteVolumeSupport.UNKNOWN -> Text(stringResource("receiver_source_remote_loading"), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton({ onRetry(source.id) }) { Text(stringResource("receiver_source_retry")) }
                        TextButton({ onRemove(source.id) }) { Text(stringResource("receiver_source_remove")) }
                    }
                }
            }
        }
        Text(stringResource("receiver_source_add_hint"), style = MaterialTheme.typography.bodySmall)
    }
}
