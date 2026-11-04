package com.cuscus.wifiaudiostreaming

import com.cuscus.wifiaudiostreaming.diagnostics.DiagnosticExportAccess
import org.junit.Assert.*
import org.junit.Test

class DiagnosticExportAccessTest {
    @Test fun shellAndOwnProcessOnly() {
        assertTrue(DiagnosticExportAccess.allowed(2000, 10234))
        assertTrue(DiagnosticExportAccess.allowed(10234, 10234))
        assertFalse(DiagnosticExportAccess.allowed(10235, 10234))
        assertFalse(DiagnosticExportAccess.allowed(1000, 10234))
        assertFalse(DiagnosticExportAccess.allowed(0, 10234))
    }
    @Test fun onlyOneReadOnlyResource() {
        assertTrue(DiagnosticExportAccess.readable("/bundle", "r", false, false))
        for (path in listOf("/runtime.log", "/bundle/../runtime.log", "/", null))
            assertFalse(DiagnosticExportAccess.readable(path, "r", false, false))
        assertFalse(DiagnosticExportAccess.readable("/bundle", "rw", false, false))
        assertFalse(DiagnosticExportAccess.readable("/bundle", "r", true, false))
        assertFalse(DiagnosticExportAccess.readable("/bundle", "r", false, true))
    }
}
