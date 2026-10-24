/*
 * Copyright AudioBridge
 *
 * Licensed under the EUPL, Version 1.2 or – as soon they will be approved by
 * the European Commission - subsequent versions of the EUPL (the "Licence");
 * You may not use this work except in compliance with the Licence.
 * You may obtain a copy of the Licence at:
 *
 * https://joinup.ec.europa.eu/software/page/eupl
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the Licence is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Licence for the specific language governing permissions and
 * limitations under the Licence.
 */

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.SpeakerGroup
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Https
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
data class Bilingual(val en: String, val it: String, val zh: String? = null) {
    fun get(): String = if (Strings.language == "zh") zh ?: en else en
}

enum class ChangelogAccent { PRIMARY, SECONDARY, TERTIARY }

data class ChangelogItem(
    val icon: ImageVector,
    val title: Bilingual,
    val body: Bilingual,
    val linkLabel: Bilingual? = null,
    val linkUrl: String? = null
)

private const val ANDROID_RELEASES_URL = "https://github.com/mu23XR/AudioBridge/releases"

data class ChangelogEntry(
    val version: String,
    val date: Bilingual,
    val headline: Bilingual,
    val items: List<ChangelogItem>
)

object Changelog {

    val entries: List<ChangelogEntry> = listOf(
        ChangelogEntry(
            version = Strings.appVersion,
            date = Bilingual("October 21, 2026", "", "2026 年 10 月 21 日"),
            headline = Bilingual("Windows receiver and connection repair preview, based on the verified Android test.21 source.", "",
                "Windows 接收与连接修复预览，以已校验的安卓 test.21 源码为对照。"),
            items = listOf(
                ChangelogItem(Icons.Outlined.Sync,
                    Bilingual("VPN reply ports", "", "VPN 回包端口兼容"),
                    Bilingual("Learn one rewritten reply port during the handshake, then keep the source endpoint fixed for that connection.", "",
                        "握手阶段可识别一次被 VPN 改写的回包端口，连接建立后保持来源校验。")),
                ChangelogItem(Icons.Outlined.GraphicEq,
                    Bilingual("Continuous playback", "", "连续播放与静音恢复"),
                    Bilingual("Bounded PCM queues preserve partial writes. Resuming sound after silence does not repay a silence debt by removing real audio.", "",
                        "播放队列保留短写尾部并限制积压。静音后恢复声音时，不再以静音补偿债务删除真实音频。")),
                ChangelogItem(Icons.Outlined.Sync,
                    Bilingual("Connection ownership", "", "连接校验与恢复"),
                    Bilingual("Validate the selected peer, separate audio activity from heartbeat timing, and keep source recovery independent.", "",
                        "校验所选发送方，区分有效音频与心跳，各音源独立恢复。")),
                ChangelogItem(Icons.Outlined.SpeakerGroup,
                    Bilingual("Source controls", "", "独立音源控制"),
                    Bilingual("Manage receiver sources and their local gain, mute and buffer target independently. Remote volume is available only when the peer supports it.", "",
                        "分别控制接收音源的本机增益、静音和缓冲目标。对端支持时才启用远端音量控制。")),
                ChangelogItem(Icons.Outlined.Terminal,
                    Bilingual("Diagnostic export", "", "诊断导出"),
                    Bilingual("Export bounded redacted diagnostics when a fault occurs. This preview still needs listening, hardware and long-session acceptance.", "",
                        "发生故障时可导出有容量限制且脱敏的诊断。本预览仍需听音、硬件和长时间运行验收。"))
            )
        ),
        ChangelogEntry(
            version = "1.3.1-test.17",
            date = Bilingual("October 2026", "", "2026 年 10 月"),
            headline = Bilingual("The Windows edition now includes the test.17 receive controls and Chinese UI.", "",
                "Windows 版同步 test.17 接收控制，并加入中文界面。"),
            items = listOf(
                ChangelogItem(Icons.Outlined.GraphicEq,
                    Bilingual("Adaptive or manual latency", "", "自适应或手动延迟"),
                    Bilingual("Choose an adaptive 20–80 ms receive target or any manual integer from 20 to 400 ms. The target belongs to the receiving device.", "",
                        "可选择 20–80 ms 自适应目标，或手动设置 20–400 ms 的任意整数。数值属于接收设备，不代表端到端延迟。")),
                ChangelogItem(Icons.Outlined.Sync,
                    Bilingual("Playback recovery", "", "播放恢复"),
                    Bilingual("Output stalls and errors trigger bounded recovery. Persistent failures reconnect with the same target and without another connection beep.", "",
                        "输出停滞或失败会触发受控恢复。持续失败后保留目标重连，避免重复连接提示音。")),
                ChangelogItem(Icons.Outlined.Computer,
                    Bilingual("One tray menu", "", "统一托盘菜单"),
                    Bilingual("Send, receive, stop and volume controls share one tray icon. Repeating a command restarts that role.", "",
                        "发送、接收、停止和音量集中在一个托盘图标中。重复命令会重新开始对应模式。")),
                ChangelogItem(Icons.Outlined.Palette,
                    Bilingual("Chinese and English", "", "中文与英文"),
                    Bilingual("Choose the interface language in Appearance; the choice is saved for the next launch.", "",
                        "在外观设置中选择界面语言，重启后保留选择。")),
                ChangelogItem(Icons.Outlined.VpnKey,
                    Bilingual("Independent test channel", "", "独立测试通道"),
                    Bilingual("Test settings, credentials, IPC and updates are isolated. The bundle includes its own Java runtime.", "",
                        "测试版独立保存设置、凭据和更新通道，便携包自带 Java 运行时，可与正式版并存。"))
            )
        ),
        ChangelogEntry(
            version = "1.2",
            date = Bilingual("August 2026", "Agosto 2026"),
            headline = Bilingual(
                "Two new protocols — DLNA and Snapcast — pairing by QR code, streaming over USB, and a rebuilt interface.",
                "Due nuovi protocolli — DLNA e Snapcast — accoppiamento con QR code, streaming via USB e un'interfaccia ricostruita."
            ),
            items = listOf(
                ChangelogItem(
                    icon = Icons.Outlined.Cast,
                    title = Bilingual("Play on your DLNA devices", "Riproduci sui tuoi dispositivi DLNA"),
                    body = Bilingual(
                        "Send the audio straight to the Smart TVs, AV receivers and network speakers you already own. They need nothing installed: pick a renderer and it starts playing.",
                        "Manda l'audio direttamente alle Smart TV, ai sintoamplificatori e ai diffusori di rete che possiedi già. Su di loro non serve installare nulla: scegli un renderer e parte."
                    )
                ),
                ChangelogItem(
                    icon = Icons.Outlined.SpeakerGroup,
                    title = Bilingual("Snapcast multiroom", "Multiroom con Snapcast"),
                    body = Bilingual(
                        "Turn this computer into a Snapcast server: every snapclient on the network — Raspberry Pi, ESP32, Home Assistant, the mobile apps — joins in and plays in sync, room after room.",
                        "Trasforma questo computer in un server Snapcast: ogni snapclient sulla rete — Raspberry Pi, ESP32, Home Assistant, le app mobili — si unisce e riproduce in sincrono, stanza dopo stanza."
                    )
                ),
                ChangelogItem(
                    icon = Icons.Outlined.QrCode2,
                    title = Bilingual("Pair with a QR code", "Accoppiamento con QR code"),
                    body = Bilingual(
                        "Address, port and a freshly generated key handed over in one shot. The key is never typed and never travels on the wire — scan the code and you are connected, encrypted.",
                        "Indirizzo, porta e una chiave appena generata consegnati in un colpo solo. La chiave non si digita mai e non viaggia mai sulla rete — inquadri il codice e sei connesso, cifrato."
                    )
                ),
                ChangelogItem(
                    icon = Icons.Outlined.Usb,
                    title = Bilingual("Stream over USB", "Streaming via USB"),
                    body = Bilingual(
                        "Plug the phone in and the audio takes the cable instead of the air: lower latency, no Wi-Fi congestion, and it keeps working where the network does not.",
                        "Collega il telefono e l'audio prende il cavo invece dell'aria: meno latenza, nessuna congestione Wi-Fi e continua a funzionare dove la rete non arriva."
                    )
                ),
                ChangelogItem(
                    icon = Icons.Outlined.Palette,
                    title = Bilingual("A rebuilt interface", "Interfaccia ricostruita"),
                    body = Bilingual(
                        "The whole window has been redrawn around what you actually do: start a stream, see who is listening, change protocol. The discovery list is clearer and no longer shows stale servers.",
                        "L'intera finestra è stata ridisegnata attorno a ciò che fai davvero: avviare uno stream, vedere chi ascolta, cambiare protocollo. La lista di discovery è più chiara e non mostra più server ormai spariti."
                    )
                ),
                ChangelogItem(
                    icon = Icons.Outlined.Router,
                    title = Bilingual("IPv6 and mixed networks", "IPv6 e reti miste"),
                    body = Bilingual(
                        "Discovery and streaming now behave on IPv6 and on networks with several interfaces, picking the one that actually reaches the other device.",
                        "Discovery e streaming ora si comportano bene su IPv6 e su reti con più interfacce, scegliendo quella che raggiunge davvero l'altro dispositivo."
                    )
                ),
                ChangelogItem(
                    icon = Icons.Outlined.Sync,
                    title = Bilingual("Older clients spotted sooner", "Client vecchi riconosciuti prima"),
                    body = Bilingual(
                        "When a device is running an older version, you are told before the connection fails rather than after, and exactly which side to update.",
                        "Quando un dispositivo usa una versione più vecchia, te lo dice prima che la connessione fallisca invece che dopo, e ti indica esattamente quale lato aggiornare."
                    )
                ),
                ChangelogItem(
                    icon = Icons.Outlined.PhoneAndroid,
                    title = Bilingual("Update the Android app too", "Aggiorna anche l'app Android"),
                    body = Bilingual(
                        "AudioBridge for Android has been updated as well. Update it too so both ends stay compatible.",
                        "Anche AudioBridge per Android è stata aggiornata. Aggiornala anche tu, così i due lati restano compatibili."
                    ),
                    linkLabel = Bilingual("Open on GitHub", "Apri su GitHub"),
                    linkUrl = ANDROID_RELEASES_URL
                )
            )
        ),
        ChangelogEntry(
            version = "1.1",
            date = Bilingual("June 2026", "Giugno 2026"),
            headline = Bilingual(
                "Terminal control, a live visualizer, broader OS support — and connection security with end-to-end encryption.",
                "Controllo da terminale, visualizzatore live, supporto più ampio ai sistemi operativi — e sicurezza delle connessioni con cifratura end-to-end."
            ),
            items = listOf(
                ChangelogItem(
                    icon = Icons.Outlined.Lock,
                    title = Bilingual("Protect your server", "Proteggi il tuo server"),
                    body = Bilingual(
                        "Right in the server panel, choose who can connect: approve every device by hand, or require a shared key. Mutual verification keeps out unknown clients and rogue servers alike.",
                        "Direttamente nel pannello server, scegli chi può connettersi: approva ogni dispositivo a mano, oppure richiedi una chiave condivisa. La verifica reciproca tiene fuori sia i client sconosciuti sia i server malevoli."
                    )
                ),
                ChangelogItem(
                    icon = Icons.Outlined.VpnKey,
                    title = Bilingual("Connect with a key", "Connessione con chiave"),
                    body = Bilingual(
                        "When a server is locked, just type its key when prompted — nothing to set up in advance, and you're told right away if the key is wrong.",
                        "Quando un server è protetto, basta digitare la sua chiave quando richiesto — niente da configurare prima, e ti viene detto subito se la chiave è sbagliata."
                    )
                ),
                ChangelogItem(
                    icon = Icons.Outlined.Https,
                    title = Bilingual("End-to-end encryption", "Cifratura end-to-end"),
                    body = Bilingual(
                        "Your audio travels encrypted from end to end, so only your paired devices can decode the stream — no one else on the network can listen in.",
                        "Il tuo audio viaggia cifrato da un capo all'altro: solo i tuoi dispositivi accoppiati possono decodificare lo stream — nessun altro sulla rete può ascoltare."
                    )
                ),
                ChangelogItem(
                    icon = Icons.Outlined.Terminal,
                    title = Bilingual("Command-line control", "Controllo da terminale"),
                    body = Bilingual(
                        "Run the whole app from your terminal with the wfas command — start a server, connect, find devices and change the volume, all fully scriptable.",
                        "Usa tutta l'app dal terminale con il comando wfas — avvia un server, connettiti, trova dispositivi e cambia il volume, tutto completamente automatizzabile."
                    )
                ),
                ChangelogItem(
                    icon = Icons.Outlined.GraphicEq,
                    title = Bilingual("Live terminal visualizer", "Visualizzatore live nel terminale"),
                    body = Bilingual(
                        "A real-time audio spectrum and a debug overlay right inside your terminal, with color themes to choose from.",
                        "Uno spettro audio in tempo reale e un overlay di debug direttamente nel terminale, con temi di colore tra cui scegliere."
                    )
                ),
                ChangelogItem(
                    icon = Icons.Outlined.Computer,
                    title = Bilingual("Better Linux & Windows support", "Miglior supporto Linux e Windows"),
                    body = Bilingual(
                        "Works on more Linux distributions with no extra setup, and on Windows it now picks the right audio device even when several have similar names.",
                        "Funziona su più distribuzioni Linux senza configurazioni extra e, su Windows, ora sceglie il dispositivo audio giusto anche quando ce ne sono diversi con nomi simili."
                    )
                ),
                ChangelogItem(
                    icon = Icons.Outlined.Sync,
                    title = Bilingual("Smarter compatibility", "Compatibilità più intelligente"),
                    body = Bilingual(
                        "The app checks that your desktop and phone speak the same version, and tells you exactly which one to update if they don't match.",
                        "L'app verifica che desktop e telefono parlino la stessa versione e ti dice esattamente quale aggiornare se non combaciano."
                    )
                ),
                ChangelogItem(
                    icon = Icons.Outlined.PhoneAndroid,
                    title = Bilingual("Update the Android app too", "Aggiorna anche l'app Android"),
                    body = Bilingual(
                        "AudioBridge for Android has been updated as well. Update it too so both ends stay compatible.",
                        "Anche AudioBridge per Android è stata aggiornata. Aggiornala anche tu, così i due lati restano compatibili."
                    ),
                    linkLabel = Bilingual("Open on GitHub", "Apri su GitHub"),
                    linkUrl = ANDROID_RELEASES_URL
                )
            )
        )
    )

    val latest: ChangelogEntry get() = entries.first()
}

private val whatsNewTitle = Bilingual("What's new", "Novità", "更新内容")
private val versionHistoryTitle = Bilingual("Version history", "Cronologia versioni", "历史版本")
private val continueLabel = Bilingual("Continue", "Continua", "继续")
private val closeLabel = Bilingual("Close", "Chiudi", "关闭")

@Composable
fun ChangelogScreen(
    visible: Boolean,
    standalone: Boolean,
    onContinue: () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(400)) + scaleIn(initialScale = 0.92f),
        exit = fadeOut(animationSpec = tween(300)) + scaleOut(targetScale = 0.92f)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 480.dp)
                    .fillMaxHeight()
                    .padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                ChangelogBody(
                    modifier = Modifier.weight(1f),
                    onContinue = if (standalone) onContinue else null
                )

                if (!standalone) {
                    Button(
                        onClick = onContinue,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp)
                    ) {
                        Text(continueLabel.get())
                    }
                }
            }
        }
    }
}

@Composable
private fun ChangelogBody(
    modifier: Modifier = Modifier,
    onContinue: (() -> Unit)?
) {
    var shown by remember { mutableStateOf(Changelog.latest) }
    var showHistory by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = whatsNewTitle.get(),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "v${shown.version} · ${shown.date.get()}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { showHistory = true }) {
                Icon(
                    imageVector = Icons.Outlined.History,
                    contentDescription = versionHistoryTitle.get()
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = shown.headline.get(),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            shown.items.forEachIndexed { i, item -> ChangelogItemCard(item, accentForIndex(i)) }
            Spacer(modifier = Modifier.height(4.dp))
        }

        if (onContinue != null) {
            Button(
                onClick = onContinue,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp)
            ) {
                Text(continueLabel.get())
            }
        }
    }

    if (showHistory) {
        VersionHistoryDialog(
            onDismiss = { showHistory = false },
            onSelect = {
                shown = it
                showHistory = false
            }
        )
    }
}

private fun accentForIndex(i: Int): ChangelogAccent = when (i % 3) {
    0 -> ChangelogAccent.PRIMARY
    1 -> ChangelogAccent.SECONDARY
    else -> ChangelogAccent.TERTIARY
}

@Composable
private fun ChangelogItemCard(item: ChangelogItem, accent: ChangelogAccent) {
    val cs = MaterialTheme.colorScheme
    val (container, onContainer, badge, onBadge) = when (accent) {
        ChangelogAccent.PRIMARY -> listOf(cs.primaryContainer, cs.onPrimaryContainer, cs.primary, cs.onPrimary)
        ChangelogAccent.SECONDARY -> listOf(cs.secondaryContainer, cs.onSecondaryContainer, cs.secondary, cs.onSecondary)
        ChangelogAccent.TERTIARY -> listOf(cs.tertiaryContainer, cs.onTertiaryContainer, cs.tertiary, cs.onTertiary)
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = container
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(badge),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = item.icon,
                    contentDescription = null,
                    tint = onBadge,
                    modifier = Modifier.size(28.dp)
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = item.title.get(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = onContainer
                )
                Text(
                    text = item.body.get(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = onContainer.copy(alpha = 0.85f)
                )
                if (item.linkUrl != null && item.linkLabel != null) {
                    TextButton(
                        onClick = { runCatching { openUrl(item.linkUrl) } },
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text(
                            item.linkLabel.get(),
                            color = onContainer,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VersionHistoryDialog(
    onDismiss: () -> Unit,
    onSelect: (ChangelogEntry) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.NewReleases, contentDescription = null) },
        title = { Text(versionHistoryTitle.get()) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(Changelog.entries) { entry ->
                    Surface(
                        onClick = { onSelect(entry) },
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = "v${entry.version}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = entry.date.get(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(closeLabel.get()) }
        }
    )
}
