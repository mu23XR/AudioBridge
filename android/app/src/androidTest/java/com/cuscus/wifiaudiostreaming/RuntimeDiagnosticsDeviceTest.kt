package com.cuscus.wifiaudiostreaming

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cuscus.wifiaudiostreaming.diagnostics.RuntimeDiagnostics
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class RuntimeDiagnosticsDeviceTest {
    @Test fun exportsAppPrivateHistoryWithoutCredentials() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        RuntimeDiagnostics.initialize(context)
        val markerFile = File(context.filesDir, "diagnostic-test-marker.txt")
        val previousMarker = if (markerFile.exists()) markerFile.readText() else null
        val marker = "DEVICE_TEST_${System.currentTimeMillis()}"
        RuntimeDiagnostics.event("DEVICE_VALIDATION", "$marker token=DO_NOT_EXPORT_THIS", true)
        val export = File(context.getExternalFilesDir(null), "diagnostics-verified.zip")
        RuntimeDiagnostics.export(context, Uri.fromFile(export))
        ZipFile(export).use { zip ->
            val text = zip.entries().asSequence().joinToString("\n") { entry ->
                zip.getInputStream(entry).bufferedReader().use { it.readText() }
            }
            assertTrue(text.contains(marker))
            assertTrue(text.contains("PROCESS_START"))
            assertFalse(text.contains("DO_NOT_EXPORT_THIS"))
            assertNotNull(zip.getEntry("report.txt"))
            if (previousMarker != null) assertTrue("Previous process evidence must survive", text.contains(previousMarker))
        }
        markerFile.writeText(marker)
    }
}
