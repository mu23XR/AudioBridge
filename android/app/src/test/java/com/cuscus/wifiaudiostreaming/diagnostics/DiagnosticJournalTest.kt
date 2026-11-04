package com.cuscus.wifiaudiostreaming.diagnostics

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class DiagnosticJournalTest {
    @Test fun rotationIsBoundedAndPreviousProcessEntriesSurvive() {
        val directory = Files.createTempDirectory("diagnostic-journal").toFile()
        try {
            val journal = DiagnosticJournal(directory, 256, 2)
            repeat(30) { journal.append("entry=$it " + "x".repeat(60)) }
            val reopened = DiagnosticJournal(directory, 256, 2)
            reopened.append("after-restart")
            val logs = reopened.snapshot()
            assertTrue(logs.size <= 3)
            assertTrue(logs.sumOf { it.second.size } <= 768)
            assertTrue(logs.flatMap { it.second.toString(Charsets.UTF_8).lines() }.any { it.startsWith("entry=29") })
            assertTrue(logs.last().second.toString(Charsets.UTF_8).contains("after-restart"))
        } finally { directory.deleteRecursively() }
    }

    @Test fun secretsAreRemovedBeforeTheyReachDisk() {
        val directory = Files.createTempDirectory("diagnostic-secrets").toFile()
        try {
            val journal = DiagnosticJournal(directory)
            journal.append("authKey=SECRET_A token=SECRET_B password=SECRET_C audiobridge://pair?key=SECRET_D Authorization: Bearer SECRET_E")
            val stored = journal.snapshot().single().second.toString(Charsets.UTF_8)
            listOf("SECRET_A", "SECRET_B", "SECRET_C", "SECRET_D", "SECRET_E").forEach { assertFalse(stored.contains(it)) }
            assertTrue(stored.contains("[redacted]"))
            assertFalse(DiagnosticRedactor.clean("{\"token\":\"JSON_SECRET\",\"password\":\"with spaces\"}").contains("JSON_SECRET"))
            assertFalse(DiagnosticRedactor.clean("{\"password\":\"with spaces\"}").contains("with spaces"))
        } finally { directory.deleteRecursively() }
    }
}
