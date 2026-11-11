package com.cuscus.wifiaudiostreaming.diagnostics

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import java.io.File
import java.io.FileNotFoundException

/** A bounded private snapshot, read through `adb shell content read --uri ...`. */
class DiagnosticExportProvider : ContentProvider() {
    override fun onCreate() = true

    private fun authorize(uri: Uri, mode: String) {
        if (!DiagnosticExportAccess.allowed(Binder.getCallingUid(), Process.myUid()))
            throw SecurityException("Diagnostic export requires the authorized ADB shell")
        if (!DiagnosticExportAccess.readable(uri.path, mode, uri.query != null, uri.fragment != null))
            throw FileNotFoundException("Only the read-only /bundle export is available")
    }

    @Synchronized override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        authorize(uri, mode)
        val app = checkNotNull(context)
        val directory = File(app.cacheDir, "diagnostic-exports")
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create private export directory" }
        // Unlink previous snapshots even if a shell reader still holds its FD.
        // Its FD remains valid; disk names and abandoned snapshots stay bounded.
        directory.listFiles()?.filter { it.isFile && it.name.startsWith("bundle-") && it.name.endsWith(".zip") }
            ?.forEach { check(it.delete() || !it.exists()) { "Cannot clean previous diagnostic snapshot" } }
        val snapshot = File.createTempFile("bundle-", ".zip", directory)
        try {
            snapshot.outputStream().use { RuntimeDiagnostics.exportToStream(app, it) }
            return ParcelFileDescriptor.open(snapshot, ParcelFileDescriptor.MODE_READ_ONLY,
                Handler(Looper.getMainLooper())) { error ->
                if (!snapshot.delete() && snapshot.exists())
                    RuntimeDiagnostics.event("EXPORT_CLEANUP_FAILED", critical = true)
                if (error != null) RuntimeDiagnostics.event("EXPORT_READ_FAILED", error.javaClass.simpleName, true)
            }
        } catch (failure: Exception) {
            if (!snapshot.delete() && snapshot.exists())
                RuntimeDiagnostics.event("EXPORT_CLEANUP_FAILED", critical = true)
            RuntimeDiagnostics.event("EXPORT_FAILED", failure.javaClass.simpleName, true)
            throw failure
        }
    }

    override fun getType(uri: Uri): String { authorize(uri, "r"); return "application/zip" }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        authorize(uri, "r"); return null
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw SecurityException("Read-only diagnostics")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) =
        throw SecurityException("Read-only diagnostics")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) =
        throw SecurityException("Read-only diagnostics")
}
