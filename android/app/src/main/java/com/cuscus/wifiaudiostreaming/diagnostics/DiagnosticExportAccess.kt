package com.cuscus.wifiaudiostreaming.diagnostics

internal object DiagnosticExportAccess {
    // DUMP alone is not sufficient: another privileged app must not read journals.
    fun allowed(callerUid: Int, appUid: Int) = callerUid == 2000 || callerUid == appUid
    fun readable(path: String?, mode: String, hasQuery: Boolean, hasFragment: Boolean) =
        path == "/bundle" && mode == "r" && !hasQuery && !hasFragment
}
