import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.swing.JFileChooser
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter

@Composable
fun DesktopDiagnosticsExportControl(settings: AllSettings) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource("diagnostics_hint"), style = MaterialTheme.typography.bodySmall)
        OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
            busy = true
            result = null
            scope.launch {
                try {
                    val destination = withContext(Dispatchers.IO) { chooseDiagnosticDestination() }
                    if (destination != null) {
                        withContext(Dispatchers.IO) { DesktopDiagnostics.exportZip(destination.first, settings, destination.second) }
                        result = Strings.get("diagnostics_exported", destination.first.path)
                    }
                } catch (failure: Exception) {
                    DesktopDiagnostics.event("EXPORT_FAILED", failure.javaClass.simpleName)
                    result = Strings.get("diagnostics_failed", failure.message ?: failure.javaClass.simpleName)
                } finally { busy = false }
            }
        }) {
            Icon(Icons.Outlined.Download, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(if (busy) "diagnostics_exporting" else "diagnostics_export"))
        }
        result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

private fun chooseDiagnosticDestination(): Pair<File, Boolean>? {
    check(!SwingUtilities.isEventDispatchThread()) { "Diagnostic picker must run outside the UI dispatcher" }
    var result: Pair<File, Boolean>? = null
    SwingUtilities.invokeAndWait {
        val chooser = JFileChooser().apply {
            dialogTitle = Strings.get("diagnostics_export")
            selectedFile = File("AudioBridge-diagnostics-${System.currentTimeMillis()}.zip")
            fileFilter = FileNameExtensionFilter("ZIP", "zip")
        }
        if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
            val chosen = chooser.selectedFile
            val target = if (chosen.name.endsWith(".zip", true)) chosen else File(chosen.parentFile, chosen.name + ".zip")
            val replace = target.exists()
            if (!replace || JOptionPane.showConfirmDialog(null, Strings.get("diagnostics_replace"),
                    Strings.get("diagnostics_export"), JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                result = target to replace
            }
        }
    }
    return result
}
