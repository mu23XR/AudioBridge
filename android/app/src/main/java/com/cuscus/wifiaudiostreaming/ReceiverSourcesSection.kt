package com.cuscus.wifiaudiostreaming

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
internal fun ReceiverSourcesSection() {
    val sources by ClientSessionController.sources.collectAsState()
    if (sources.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.source_list_title), style = MaterialTheme.typography.titleMedium)
        for (source in sources) key(source.id) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(source.peer.hostname.ifBlank { source.peer.ip }, style = MaterialTheme.typography.titleMedium)
                    Text(source.status, style = MaterialTheme.typography.bodySmall)
                    val remote = source.remoteVolume
                    if (remote.support == RemoteVolumeSupport.SUPPORTED) {
                        var requested by remember(source.id) { mutableFloatStateOf((remote.percent ?: 50).toFloat()) }
                        var dragging by remember(source.id) { mutableStateOf(false) }
                        LaunchedEffect(remote.percent, remote.pending, remote.error) {
                            if (!dragging) remote.percent?.let { requested = it.toFloat() }
                        }
                        Text(stringResource(R.string.source_sender_volume,
                            remote.percent?.toString() ?: "—"), style = MaterialTheme.typography.bodySmall)
                        Slider(requested, {
                            dragging = true; requested = it
                        }, valueRange = 0f..100f,
                            enabled = remote.percent != null && !remote.pending && !source.needsAttention,
                            onValueChangeFinished = {
                                dragging = false
                                ClientSessionController.setRemoteVolume(source.id, requested.roundToInt())
                            })
                        if (remote.pending) Text(stringResource(R.string.source_sender_volume_pending),
                            style = MaterialTheme.typography.bodySmall)
                        if (remote.error != null) Text(stringResource(R.string.source_sender_volume_failed),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    } else {
                        Text(stringResource(if (remote.support == RemoteVolumeSupport.UNSUPPORTED)
                            R.string.source_sender_volume_unsupported else R.string.source_sender_volume_loading),
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.source_mute), Modifier.weight(1f))
                        Switch(source.muted, { ClientSessionController.setMuted(source.id, it) })
                    }
                    var volume by remember(source.id, source.volume) { mutableFloatStateOf(source.volume) }
                    Text("${stringResource(R.string.source_mix_volume)} ${(volume * 100).roundToInt()}%",
                        style = MaterialTheme.typography.bodySmall)
                    Slider(volume, { volume = it; ClientSessionController.setVolume(source.id, it, save = false) }, onValueChangeFinished = {
                        ClientSessionController.setVolume(source.id, volume)
                    })
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.source_adaptive), Modifier.weight(1f))
                        Switch(source.adaptive, {
                            ClientSessionController.setLatency(source.id, it, source.manualLatencyMs)
                        })
                    }
                    if (!source.adaptive) {
                        var value by remember(source.id, source.manualLatencyMs) { mutableFloatStateOf(source.manualLatencyMs.toFloat()) }
                        var input by remember(source.id, source.manualLatencyMs) { mutableStateOf(source.manualLatencyMs.toString()) }
                        fun save() { input.toIntOrNull()?.takeIf { it in 20..400 }?.let {
                            if (it != source.manualLatencyMs) ClientSessionController.setLatency(source.id, false, it)
                        } }
                        OutlinedTextField(input, { if (it.length <= 3 && it.all(Char::isDigit)) input = it },
                            label = { Text(stringResource(R.string.source_manual_latency)) },
                            isError = input.toIntOrNull()?.let { it !in 20..400 } ?: true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { save() }),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused) save() })
                        Slider(value, { value = it; input = it.roundToInt().toString() },
                            valueRange = 20f..400f, onValueChangeFinished = {
                                ClientSessionController.setLatency(source.id, false, value.roundToInt())
                            })
                    }
                    Text(stringResource(R.string.source_buffer_status, source.targetMs, source.bufferedMs),
                        style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton({ ClientSessionController.retry(source.id) }) { Text(stringResource(R.string.source_retry)) }
                        TextButton({ ClientSessionController.remove(source.id) }) { Text(stringResource(R.string.source_remove)) }
                    }
                }
            }
        }
        Text(stringResource(R.string.source_add_hint), style = MaterialTheme.typography.bodySmall)
    }
}
