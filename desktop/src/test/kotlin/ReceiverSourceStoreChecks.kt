import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/** All config and credentials in these checks are in memory, never in the user's profile. */
internal object ReceiverSourceStoreChecks {
    private val firstId = "9f0d6b1e-4a2c-4a58-9d3f-2b6c1f0a7e11"
    private val secondId = "9f0d6b1e-4a2c-4a58-9d3f-2b6c1f0a7e12"

    private class MemoryVault : ReceiverSourceCredentials {
        val values = mutableMapOf<String, String>()
        var rejectStore = false
        var failNextWriteAfterStore = false
        var storeCalls = 0
        override fun load(name: String): String? = values[name]
        override fun store(name: String, value: String) {
            storeCalls++
            check(!rejectStore) { "fake vault rejected write" }
            values[name] = value
            if (failNextWriteAfterStore) {
                failNextWriteAfterStore = false
                error("fake vault failed after writing")
            }
        }
        override fun clear(name: String) { values.remove(name) }
    }

    private class Fixture {
        var saved = ConfigManager.DEFAULTS
        var failSave = false
        var saveCalls = 0
        val transactions = SettingsTransactions({ saved }, {
            check(!failSave) { "fake disk refused save" }
            saveCalls++
            saved = it
        })
        val vault = MemoryVault()
        val sources = ReceiverSourceRepository(object : ReceiverSourcePersistence {
            override fun loadRows(): List<String> = transactions.load().app.receiverSources
            override fun updateRows(transform: (List<String>) -> List<String>): List<String> = transactions.updateSources(transform)
        }, vault)
    }

    @JvmStatic fun runChecks() {
        val sample = RememberedReceiverSource(
            id = firstId, ip = "192.0.2.10", port = 9090, multicast = false,
            hostname = "studio \"A\"", gain = 0.42f, latencyMs = 61,
            muted = true, adaptive = true, paused = true,
            serverAudio = AudioSettings_V1(44100f, 16, 2, 1024, 45, 1388, false),
            capabilities = ServerCapabilities(setOf(StreamingProtocol.WFAS), null,
                securityMode = "KEY", encrypted = true), viaUsb = true
        )
        val roundTrip = RememberedReceiverSource.parse(sample.serialize())
        check(roundTrip == sample) { "All source-local controls and peer metadata must survive serialization" }
        check(roundTrip!!.toServerInfo(ConfigManager.DEFAULTS.audio).serverAudioSettings?.adaptiveLatency == true)
        val oldV2Peer = RememberedReceiverSource.parse("""{"version":2,"id":"$firstId","ip":"192.0.2.10","port":9090,"latencyMs":73,"adaptive":true,"audio":{"sampleRate":44100,"bitDepth":16,"channels":2,"bufferSize":1024,"maxPayloadBytes":1388}}""")
        check(oldV2Peer?.serverAudio?.latencyMs == 73 && oldV2Peer.serverAudio?.adaptiveLatency == true) {
            "Earlier v2 peer objects without separate buffer fields must remain readable"
        }
        check(RememberedReceiverSource.parse("not json") == null)
        check(RememberedReceiverSource.parse("{\"port\":9090}") == null)
        check(RememberedReceiverSource.parse("{\"ip\":\"x\",\"port\":0}") == null)
        check(RememberedReceiverSource.parse("{\"ip\":\"x\",\"port\":9090.5}") == null)
        check(RememberedReceiverSource.parse("{\"ip\":\"x\",\"port\":9090,\"version\":3}") == null)
        val clamped = RememberedReceiverSource.parse("{\"ip\":\"192.0.2.11\",\"port\":9090,\"gain\":9,\"latencyMs\":900}")
        check(clamped != null && clamped.gain == 1f && clamped.latencyMs == 400 && !clamped.adaptive)
        val legacyRow = "{\"ip\":\"192.0.2.12\",\"port\":9091}"
        check(RememberedReceiverSource.parse(legacyRow) == RememberedReceiverSource.parse(legacyRow)) {
            "Legacy IDs must be stable across reads"
        }

        val f = Fixture()
        val staleGui = f.transactions.load()
        f.sources.remember(sample, "fixture-key-one")
        val stored = f.sources.load().single()
        check(stored.credentialRef == ReceiverSourceRepository.credentialName(firstId))
        check(f.sources.loadKey(stored) == "fixture-key-one")
        check(f.saved.app.receiverSources.none { it.contains("fixture-key-one") })
        val merged = f.transactions.saveGeneral(staleGui.copy(app = staleGui.app.copy(language = "zh")))
        check(merged.app.language == "zh" && f.sources.load().single().id == firstId) {
            "A stale GUI settings save must preserve a newly added source"
        }
        f.sources.update(firstId) { it.copy(gain = 0.23f, muted = false, adaptive = false, latencyMs = 137, paused = true) }
        f.transactions.saveGeneral(staleGui)
        check(f.sources.load().single().gain == 0.23f && f.sources.load().single().latencyMs == 137) {
            "GUI saves must retain independent source updates"
        }
        val beforeRemoval = f.transactions.load()
        check(f.sources.forget(firstId))
        check(!f.sources.update(firstId) { it.copy(muted = true) })
        f.transactions.saveGeneral(beforeRemoval)
        check(f.sources.load().isEmpty() && f.vault.values.isEmpty()) {
            "Neither stale GUI saves nor queued source updates may resurrect removed sources/credentials"
        }

        // Same IP/port with a different transport is a different source, including credential ownership.
        f.sources.remember(sample, "fixture-unicast")
        f.sources.remember(sample.copy(id = secondId, multicast = true), "fixture-multicast")
        check(f.sources.forget(firstId))
        check(f.sources.loadKey(f.sources.load().single()) == "fixture-multicast")
        check(f.vault.values.size == 1) { "Removing one source must not delete another source's key" }

        val credential = Fixture()
        credential.sources.remember(sample)
        check(credential.sources.updateCredential(firstId, "confirmed-prompt-key"))
        check(credential.sources.loadKey(credential.sources.load().single()) == "confirmed-prompt-key")
        val writesAfterConfirmation = credential.vault.storeCalls
        repeat(4) { check(credential.sources.updateCredential(firstId, "confirmed-prompt-key")) }
        check(credential.vault.storeCalls == writesAfterConfirmation) { "Stable connected snapshots must not rewrite the same key" }
        credential.vault.failNextWriteAfterStore = true
        check(runCatching { credential.sources.updateCredential(firstId, "failed-replacement") }.isFailure)
        check(credential.sources.loadKey(credential.sources.load().single()) == "confirmed-prompt-key") {
            "A vault that fails after writing must restore the previous confirmed credential"
        }
        credential.sources.forget(firstId)
        val writesAfterRemoval = credential.vault.storeCalls
        check(!credential.sources.updateCredential(firstId, "late-prompt-key"))
        check(credential.vault.storeCalls == writesAfterRemoval && credential.vault.values.isEmpty()) {
            "A delayed successful handshake cannot resurrect a removed row or credential"
        }

        val migration = Fixture()
        migration.saved = migration.saved.copy(app = migration.saved.app.copy(receiverSources = listOf(sample.serialize())))
        val oldRef = ReceiverSourceRepository.legacyCredentialName(sample.ip, sample.port)
        migration.vault.values[oldRef] = "old-legacy-key"
        migration.failSave = true
        check(runCatching { migration.sources.updateCredential(firstId, "confirmed-new-key") }.isFailure)
        check(migration.vault.values == mapOf(oldRef to "old-legacy-key")) {
            "Failed credential-reference migration must leave legacy key and metadata intact"
        }
        migration.failSave = false
        check(migration.sources.updateCredential(firstId, "confirmed-new-key"))
        check(migration.sources.loadKey(migration.sources.load().single()) == "confirmed-new-key" && oldRef !in migration.vault.values)

        val sharedLegacy = Fixture()
        sharedLegacy.saved = sharedLegacy.saved.copy(app = sharedLegacy.saved.app.copy(receiverSources = listOf(
            sample.serialize(), sample.copy(id = secondId, multicast = true).serialize()
        )))
        sharedLegacy.vault.values[oldRef] = "shared-legacy-key"
        check(sharedLegacy.sources.updateCredential(firstId, "new-unicast-key"))
        check(sharedLegacy.vault.values[oldRef] == "shared-legacy-key") {
            "Migrating one endpoint must preserve a legacy key still owned by another transport"
        }
        sharedLegacy.sources.forget(firstId)
        check(sharedLegacy.sources.loadKey(sharedLegacy.sources.load().single()) == "shared-legacy-key")

        val baseState = DesktopReceiverSourceState(firstId, sample.toServerInfo(ConfigManager.DEFAULTS.audio),
            gain = sample.gain, muted = sample.muted, adaptive = sample.adaptive,
            manualLatencyMs = sample.latencyMs, paused = sample.paused)
        val preference = ReceiverSourcePreferences.from(baseState)
        check(preference == ReceiverSourcePreferences.from(baseState.copy(
            status = "streaming", connected = true, needsAttention = true, blocked = true,
            targetMs = 70, bufferedMs = 42, playedFrames = 900000
        ))) { "Runtime status, queue and playback progress are not persistent user preferences" }
        val batch = Fixture()
        batch.sources.remember(sample, "batch-first-key")
        batch.sources.remember(sample.copy(id = secondId, ip = "192.0.2.20"), "batch-second-key")
        val beforeBatch = batch.sources.load()
        val beforeBatchVault = batch.vault.values.toMap()
        val savedBeforeBatch = batch.saveCalls
        val batchPreferences = listOf(
            preference.copy(gain = 0.17f, muted = false, adaptive = false, manualLatencyMs = 231, paused = false),
            preference.copy(id = secondId, gain = 0.75f, muted = false, manualLatencyMs = 67)
        )
        check(batch.sources.updatePreferences(batchPreferences))
        check(batch.saveCalls == savedBeforeBatch + 1) { "All source preferences must commit in one file write" }
        val afterBatch = batch.sources.load()
        check(afterBatch.first() == beforeBatch.first().copy(gain = 0.17f, muted = false,
            adaptive = false, latencyMs = 231, paused = false))
        check(afterBatch.last() == beforeBatch.last().copy(gain = 0.75f, muted = false, latencyMs = 67)) {
            "Preference updates must leave endpoint, peer and credential metadata untouched"
        }
        check(batch.vault.values == beforeBatchVault) { "Preference updates must not modify credentials" }
        check(!batch.sources.updatePreferences(batchPreferences) && batch.saveCalls == savedBeforeBatch + 1) {
            "An unchanged preference snapshot must cause no file writes"
        }
        check(batch.sources.forget(firstId))
        val savedAfterRemoval = batch.saveCalls
        check(!batch.sources.updatePreferences(batchPreferences))
        check(batch.saveCalls == savedAfterRemoval && batch.sources.load().map { it.id } == listOf(secondId)) {
            "A delayed batch snapshot must not resurrect a removed source or its key"
        }
        check(batch.vault.values.size == 1 && batch.sources.loadKey(batch.sources.load().single()) == "batch-second-key")

        val rollback = Fixture()
        rollback.sources.remember(sample, "old-fixture-key")
        rollback.failSave = true
        check(runCatching { rollback.sources.remember(sample.copy(gain = 0.2f), "new-fixture-key") }.isFailure)
        check(rollback.sources.loadKey(rollback.sources.load().single()) == "old-fixture-key") {
            "Failed metadata saves must restore the previous credential"
        }
        check(runCatching { rollback.sources.forget(firstId) }.isFailure)
        check(rollback.vault.values.size == 1) { "Failed source removal must not clear its credential" }

        val bad = Fixture()
        bad.saved = bad.saved.copy(app = bad.saved.app.copy(receiverSources = listOf("not json")))
        val failure = runCatching { bad.sources.load() }.exceptionOrNull()
        check(failure is ConfigException && failure.message?.contains("receiver.sources[0]") == true) {
            "Malformed source config must produce a locatable error, rather than disappear silently"
        }
        check(runCatching { bad.sources.remember(sample) }.isFailure && bad.saved.app.receiverSources == listOf("not json"))

        val concurrent = Fixture()
        concurrent.sources.remember(sample)
        val start = CountDownLatch(1)
        val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val guiWriter = thread {
            try { start.await(); repeat(200) { concurrent.transactions.saveGeneral(staleGui) } }
            catch (t: Throwable) { errors.add(t) }
        }
        val sourceWriter = thread {
            try { start.await(); repeat(200) { index -> concurrent.sources.update(firstId) { it.copy(latencyMs = 20 + index) } } }
            catch (t: Throwable) { errors.add(t) }
        }
        start.countDown(); guiWriter.join(); sourceWriter.join()
        check(errors.isEmpty()) { "Concurrent config writes failed: $errors" }
        check(concurrent.sources.load().single().latencyMs == 219)
        concurrent.transactions.replaceAll(ConfigManager.DEFAULTS)
        check(concurrent.sources.load().isEmpty()) { "Explicit reset/import may replace the complete source list" }

        println("receiver source store: metadata/vault ownership, stale GUI and removal races, failure rollback and concurrent writes passed")
    }
}
