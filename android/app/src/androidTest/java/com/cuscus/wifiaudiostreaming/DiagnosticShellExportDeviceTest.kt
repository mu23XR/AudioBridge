package com.cuscus.wifiaudiostreaming

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cuscus.wifiaudiostreaming.diagnostics.RuntimeDiagnostics
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.zip.ZipInputStream

@RunWith(AndroidJUnit4::class)
class DiagnosticShellExportDeviceTest {
    @Test fun authorizedShellReadsSameRedactedBundleWithoutActivity() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        RuntimeDiagnostics.event("SHELL_EXPORT_FIXTURE", "authKey=shell-test-secret", true)
        val uri = Uri.parse("content://${context.packageName}.diagnostics/bundle")
        inst.uiAutomation.executeShellCommand("content read --uri $uri").use { fd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { input ->
                ZipInputStream(input).use { zip ->
                    val entries = linkedMapOf<String, String>()
                    var entry = zip.nextEntry
                    while (entry != null) {
                        entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                        entry = zip.nextEntry
                    }
                    assertTrue("shell read is not a ZIP bundle", "report.txt" in entries)
                    assertTrue(entries.keys.any { it.startsWith("runtime") })
                    val text = entries.values.joinToString("\n")
                    assertTrue(text.contains("SHELL_EXPORT_FIXTURE"))
                    assertFalse(text.contains("shell-test-secret"))
                }
            }
        }
        try {
            context.contentResolver.openFileDescriptor(uri, "rw")?.close()
            fail("Writable diagnostic export accepted")
        } catch (_: java.io.FileNotFoundException) { }
        val probe = "content://${inst.context.packageName}.diagnostic-probe"
        inst.uiAutomation.executeShellCommand("content call --uri $probe --method probe --arg $uri").use { fd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { input ->
                val answer = input.readBytes().toString(Charsets.UTF_8)
                assertTrue("Ordinary app UID was not denied: $answer", answer.contains("denied=true"))
            }
        }
    }
}
