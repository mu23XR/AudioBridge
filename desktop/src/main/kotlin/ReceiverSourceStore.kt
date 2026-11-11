import java.util.UUID

/** Metadata only; credentialRef is an opaque vault entry name, never the key itself. */
internal data class RememberedReceiverSource(
    val id: String,
    val ip: String,
    val port: Int,
    val multicast: Boolean,
    val hostname: String = "",
    val gain: Float = 1f,
    val latencyMs: Int = 20,
    val muted: Boolean = false,
    val adaptive: Boolean = false,
    val paused: Boolean = true,
    val credentialRef: String = "",
    val serverAudio: AudioSettings_V1? = null,
    val capabilities: ServerCapabilities? = null,
    val viaUsb: Boolean = false,
    val fromInvite: Boolean = false,
    val expectedEpoch: Long? = null
) {
    fun serialize(): String {
        validate()
        return ConfigManager.writeJson(linkedMapOf(
            "version" to 2, "id" to id, "ip" to ip, "port" to port, "multicast" to multicast,
            "hostname" to hostname, "gain" to gain, "latencyMs" to latencyMs, "muted" to muted,
            "adaptive" to adaptive, "paused" to paused, "credentialRef" to credentialRef,
            "viaUsb" to viaUsb, "fromInvite" to fromInvite, "expectedEpoch" to expectedEpoch,
            "audio" to serverAudio?.let { a -> linkedMapOf(
                "sampleRate" to a.sampleRate, "bitDepth" to a.bitDepth, "channels" to a.channels,
                "bufferSize" to a.bufferSize, "latencyMs" to a.latencyMs,
                "maxPayloadBytes" to a.maxPayloadBytes, "adaptiveLatency" to a.adaptiveLatency) },
            "capabilities" to capabilities?.let { c -> linkedMapOf(
                "protocols" to c.protocols.map { it.name }, "httpPort" to c.httpPort,
                "safariMode" to c.safariMode, "securityMode" to c.securityMode,
                "encrypted" to c.encrypted, "serverSendsMic" to c.serverSendsMic,
                "serverWantsMic" to c.serverWantsMic) }
        ))
    }

    fun toServerInfo(defaultAudio: AudioSettings_V1): ServerInfo = ServerInfo(
        ip, multicast, port, capabilities = capabilities,
        serverAudioSettings = (serverAudio ?: defaultAudio).copy(latencyMs = latencyMs, adaptiveLatency = adaptive),
        viaUsb = viaUsb
    )

    internal fun sameEndpointAs(other: RememberedReceiverSource): Boolean =
        ip == other.ip && port == other.port && multicast == other.multicast

    internal fun validate() {
        require(UUID.fromString(id).toString() == id.lowercase()) { "receiver source has invalid id" }
        require(ip.isNotBlank() && ip.length <= 255 && ip.none { it.isWhitespace() || it.isISOControl() }) {
            "receiver source has invalid address"
        }
        require(port in 1..65535) { "receiver source has invalid port" }
        require(hostname.length <= 128) { "receiver source name is too long" }
        require(gain.isFinite() && gain in 0f..1f && latencyMs in 20..400) { "receiver source has invalid gain/latency" }
        require(credentialRef.isEmpty() || credentialRef.matches(Regex("wfas\\.receiver\\.[0-9a-fA-F-]{36}"))) {
            "receiver source has invalid credential reference"
        }
        require(expectedEpoch == null || expectedEpoch >= 0) { "receiver source has invalid epoch" }
        serverAudio?.let {
            require(it.sampleRate.isFinite() && it.sampleRate in 8000f..384000f && it.bitDepth == 16 &&
                it.channels in 1..8 && it.bufferSize > 0 && it.maxPayloadBytes in 64..65000 && it.latencyMs in 0..5000) {
                "receiver source has invalid peer audio format"
            }
        }
        require(capabilities?.httpPort == null || capabilities.httpPort in 1..65535) { "receiver source has invalid HTTP port" }
    }

    companion object {
        /** Nullable inspection API; repository loads report invalid rows explicitly. */
        fun parse(line: String): RememberedReceiverSource? = try {
            val map = ConfigManager.parseJson(line) as? Map<*, *> ?: throw IllegalArgumentException("object required")
            val version = map.number("version", 1).exactInt()
            require(version in 1..2) { "unsupported receiver source version" }
            val ip = (map["ip"] as? String)?.trim() ?: throw IllegalArgumentException("address required")
            val port = map.number("port").exactInt()
            val multicast = map.boolean("multicast", false)
            // Legacy rows get a deterministic id, rather than churning on every GUI save.
            val id = map.nullableString("id") ?: UUID.nameUUIDFromBytes("$ip:$port:$multicast".toByteArray(Charsets.UTF_8)).toString()
            val old = version == 1
            val gain = map.number("gain", 1f).toFloat().also { require(it.isFinite()) }
            val latency = map.number("latencyMs", 20).exactInt()
            val adaptive = map.boolean("adaptive", false)
            require(map["audio"] == null || map["audio"] is Map<*, *>) { "invalid peer audio object" }
            require(map["capabilities"] == null || map["capabilities"] is Map<*, *>) { "invalid peer capabilities object" }
            val audio = (map["audio"] as? Map<*, *>)?.let { a ->
                AudioSettings_V1(a.number("sampleRate").toFloat().also { require(it.isFinite() && it in 8000f..384000f) },
                    a.number("bitDepth", 16).exactInt().also { require(it == 16) },
                    a.number("channels").exactInt().also { require(it in 1..8) },
                    a.number("bufferSize", 512).exactInt().also { require(it > 0) },
                    a.number("latencyMs", latency.coerceIn(20, 400)).exactInt().also { require(it in 0..5000) },
                    a.number("maxPayloadBytes", 1390).exactInt().also { require(it in 64..65000) },
                    a.boolean("adaptiveLatency", adaptive))
            }
            val capabilities = (map["capabilities"] as? Map<*, *>)?.let { c ->
                require(c["protocols"] == null || c["protocols"] is List<*>) { "invalid peer protocols" }
                require(c["httpPort"] == null || c["httpPort"] is Number) { "invalid peer HTTP port" }
                val protocols = (c["protocols"] as? List<*>)?.map {
                    StreamingProtocol.valueOf(it as? String ?: throw IllegalArgumentException("invalid protocol"))
                }?.toSet() ?: emptySet()
                ServerCapabilities(protocols, (c["httpPort"] as? Number)?.exactInt()?.also { require(it in 1..65535) },
                    c.boolean("safariMode", false), c.nullableString("securityMode"),
                    c.boolean("encrypted", false), c.boolean("serverSendsMic", false), c.boolean("serverWantsMic", false))
            }
            require(map["expectedEpoch"] == null || map["expectedEpoch"] is Long || map["expectedEpoch"] is Int) { "invalid peer epoch" }
            RememberedReceiverSource(id, ip, port, multicast, map.nullableString("hostname").orEmpty(),
                if (old) gain.coerceIn(0f, 1f) else gain, if (old) latency.coerceIn(20, 400) else latency,
                map.boolean("muted", false), adaptive, map.boolean("paused", true),
                map.nullableString("credentialRef").orEmpty(), audio, capabilities,
                map.boolean("viaUsb", false), map.boolean("fromInvite", false),
                (map["expectedEpoch"] as? Number)?.toLong()).also { it.validate() }
        } catch (_: ConfigException) { null } catch (_: IllegalArgumentException) { null }

        private fun Map<*, *>.number(key: String, default: Number? = null): Number =
            (this[key] ?: default) as? Number ?: throw IllegalArgumentException("$key must be numeric")
        private fun Map<*, *>.boolean(key: String, default: Boolean): Boolean =
            if (!containsKey(key)) default else this[key] as? Boolean ?: throw IllegalArgumentException("$key must be boolean")
        private fun Map<*, *>.nullableString(key: String): String? =
            this[key]?.let { it as? String ?: throw IllegalArgumentException("$key must be a string") }
        private fun Number.exactInt(): Int = toInt().also { require(toDouble().isFinite() && toDouble() == it.toDouble()) }
    }
}

internal interface ReceiverSourcePersistence {
    fun loadRows(): List<String>
    fun updateRows(transform: (List<String>) -> List<String>): List<String>
}

internal interface ReceiverSourceCredentials {
    fun load(name: String): String?
    fun store(name: String, value: String)
    fun clear(name: String)
}

/** Injected storage ensures regression checks never touch real user config or credentials. */
internal class ReceiverSourceRepository(
    private val persistence: ReceiverSourcePersistence,
    private val credentials: ReceiverSourceCredentials
) {
    private val lock = Any()
    fun load(): List<RememberedReceiverSource> = synchronized(lock) { decode(persistence.loadRows()) }

    fun remember(source: RememberedReceiverSource, key: String? = null): Boolean = synchronized(lock) {
        source.validate()
        var writtenRef: String? = null
        var oldKey: String? = null
        var obsoleteRef: String? = null
        try {
            persistence.updateRows { rows ->
                val current = decode(rows)
                val previous = current.firstOrNull { it.id == source.id || it.sameEndpointAs(source) }
                require(previous == null || previous.sameEndpointAs(source)) { "source id belongs to another endpoint" }
                val retained = current.filterNot { it.id == source.id || it.sameEndpointAs(source) }
                require(retained.size < ReceiverSourceStore.MAX_REMEMBERED) { "At most four receiver sources can be remembered" }
                val ref = if (!key.isNullOrBlank()) source.credentialRef.ifEmpty { credentialName(source.id) }
                          else source.credentialRef.ifEmpty { previous?.credentialRef.orEmpty() }
                val next = source.copy(credentialRef = ref)
                val serialized = (retained + next).map { it.serialize() }
                previous?.let {
                    val previousRef = it.credentialRef.ifEmpty { legacyCredentialName(it.ip, it.port) }
                    if (ref.isNotEmpty() && previousRef != ref && retained.none { other ->
                        other.credentialRef.ifEmpty { legacyCredentialName(other.ip, other.port) } == previousRef
                    }) obsoleteRef = previousRef
                }
                if (!key.isNullOrBlank()) {
                    oldKey = credentials.load(ref)
                    writtenRef = ref
                    credentials.store(ref, key)
                }
                serialized
            }
        } catch (failure: Exception) {
            // Metadata and vault are separate stores. Restore the old credential on failed commit.
            writtenRef?.let { ref ->
                try { oldKey?.let { credentials.store(ref, it) } ?: credentials.clear(ref) }
                catch (rollback: Exception) { failure.addSuppressed(rollback) }
            }
            throw failure
        }
        // Once the metadata commits to the new entry, remove an unshared legacy/rebound key.
        obsoleteRef?.let { credentials.clear(it) }
        true
    }

    /** Missing rows stay missing, so stale StateFlow snapshots cannot resurrect a removal. */
    fun update(id: String, transform: (RememberedReceiverSource) -> RememberedReceiverSource): Boolean = synchronized(lock) {
        var found = false
        persistence.updateRows { rows ->
            val current = decode(rows)
            if (current.none { it.id == id }) return@updateRows rows
            current.map { old ->
                if (old.id != id) old.serialize() else {
                    val next = transform(old)
                    require(next.id == old.id && next.sameEndpointAs(old) && next.credentialRef == old.credentialRef) {
                        "source metadata update cannot replace identity, endpoint or credential ownership"
                    }
                    found = true
                    next.serialize()
                }
            }
        }
        found
    }

    /** One transaction for a debounced GUI snapshot; no missing source can be re-created. */
    fun updatePreferences(preferences: List<ReceiverSourcePreferences>): Boolean = synchronized(lock) {
        val byId = preferences.associateBy { it.id }
        require(byId.size == preferences.size) { "receiver preferences contain duplicate ids" }
        if (preferences.isEmpty()) return@synchronized false
        var changed = false
        persistence.updateRows { rows ->
            decode(rows).mapIndexed { index, old ->
                val preference = byId[old.id] ?: return@mapIndexed rows[index]
                val next = old.copy(gain = preference.gain, muted = preference.muted,
                    adaptive = preference.adaptive, latencyMs = preference.manualLatencyMs, paused = preference.paused)
                if (next == old) rows[index] else {
                    changed = true
                    next.serialize()
                }
            }
        }
        changed
    }

    /** Save only the key confirmed by a successful handshake; removed sources remain removed. */
    fun updateCredential(id: String, key: String): Boolean = synchronized(lock) {
        require(key.isNotBlank()) { "receiver credential must not be blank" }
        var found = false
        var writtenRef: String? = null
        var oldKey: String? = null
        var obsoleteRef: String? = null
        try {
            persistence.updateRows { rows ->
                val current = decode(rows)
                val source = current.firstOrNull { it.id == id } ?: return@updateRows rows
                found = true
                val ref = source.credentialRef.ifEmpty { credentialName(id) }
                val storedKey = credentials.load(ref)
                if (storedKey == key && source.credentialRef == ref) return@updateRows rows
                val serialized = current.map {
                    if (it.id == id) it.copy(credentialRef = ref).serialize() else it.serialize()
                }
                if (storedKey != key) {
                    oldKey = storedKey
                    writtenRef = ref
                    credentials.store(ref, key)
                }
                val previousRef = source.credentialRef.ifEmpty { legacyCredentialName(source.ip, source.port) }
                if (previousRef != ref && current.none { other ->
                    other.id != id && other.credentialRef.ifEmpty { legacyCredentialName(other.ip, other.port) } == previousRef
                }) obsoleteRef = previousRef
                serialized
            }
        } catch (failure: Exception) {
            writtenRef?.let { ref ->
                try { oldKey?.let { credentials.store(ref, it) } ?: credentials.clear(ref) }
                catch (rollback: Exception) { failure.addSuppressed(rollback) }
            }
            throw failure
        }
        obsoleteRef?.let { credentials.clear(it) }
        found
    }

    fun forget(id: String): Boolean = synchronized(lock) {
        var removed: RememberedReceiverSource? = null
        var remaining = emptyList<RememberedReceiverSource>()
        persistence.updateRows { rows ->
            val current = decode(rows)
            removed = current.firstOrNull { it.id == id }
            if (removed == null) return@updateRows rows
            remaining = current.filterNot { it.id == id }
            remaining.map { it.serialize() }
        }
        val source = removed ?: return@synchronized false
        // Delete after saving metadata: a failed file write must not destroy a referenced key.
        val ref = source.credentialRef.ifEmpty { legacyCredentialName(source.ip, source.port) }
        val shared = remaining.any { it.credentialRef.ifEmpty { legacyCredentialName(it.ip, it.port) } == ref }
        if (!shared) credentials.clear(ref)
        true
    }

    fun loadKey(source: RememberedReceiverSource): String? = synchronized(lock) {
        credentials.load(source.credentialRef.ifEmpty { legacyCredentialName(source.ip, source.port) })?.takeIf { it.isNotBlank() }
    }

    private fun decode(rows: List<String>): List<RememberedReceiverSource> = decodeRememberedReceiverSources(rows)

    companion object {
        fun credentialName(id: String): String = "wfas.receiver.$id"
        fun legacyCredentialName(ip: String, port: Int): String = "wfas.receiver.$ip:$port"
    }
}

internal fun decodeRememberedReceiverSources(rows: List<String>): List<RememberedReceiverSource> {
    if (rows.size > ReceiverSourceStore.MAX_REMEMBERED) throw ConfigException("receiver.sources exceeds four entries")
    val decoded = rows.mapIndexed { index, row ->
        RememberedReceiverSource.parse(row) ?: throw ConfigException("receiver.sources[$index] contains invalid metadata")
    }
    if (decoded.map { it.id }.distinct().size != decoded.size) throw ConfigException("receiver.sources contains duplicate ids")
    if (decoded.map { Triple(it.ip, it.port, it.multicast) }.distinct().size != decoded.size)
        throw ConfigException("receiver.sources contains duplicate endpoints")
    return decoded
}

internal object ReceiverSourceStore {
    const val MAX_REMEMBERED = 4
    private val credentials = object : ReceiverSourceCredentials {
        override fun load(name: String): String? = synchronized(settingsPersistenceLock) { SecretVault.backend?.load(name) }
        override fun store(name: String, value: String) = synchronized(settingsPersistenceLock) {
            val backend = SecretVault.backend ?: throw IllegalStateException("Receiver key cannot be saved: no OS credential store")
            check(backend.store(name, value)) { "Receiver key could not be saved to ${backend.label}" }
        }
        override fun clear(name: String) { synchronized(settingsPersistenceLock) { SecretVault.backend?.clear(name) } }
    }
    private val repository = ReceiverSourceRepository(object : ReceiverSourcePersistence {
        override fun loadRows(): List<String> = SettingsRepository.loadSettings().app.receiverSources
        override fun updateRows(transform: (List<String>) -> List<String>): List<String> =
            SettingsRepository.updateReceiverSources(transform)
    }, credentials)

    fun load(): List<RememberedReceiverSource> = repository.load()
    fun remember(source: RememberedReceiverSource, key: String? = null): Boolean = repository.remember(source, key)
    fun update(id: String, transform: (RememberedReceiverSource) -> RememberedReceiverSource): Boolean = repository.update(id, transform)
    fun updatePreferences(preferences: List<ReceiverSourcePreferences>): Boolean = repository.updatePreferences(preferences)
    fun updateCredential(id: String, key: String): Boolean = repository.updateCredential(id, key)
    fun forget(id: String): Boolean = repository.forget(id)
    fun forget(ip: String, port: Int, multicast: Boolean): Boolean =
        load().firstOrNull { it.ip == ip && it.port == port && it.multicast == multicast }?.let { forget(it.id) } ?: false
    fun loadKey(source: RememberedReceiverSource): String? = repository.loadKey(source)

    // Legacy endpoint APIs remain until CLI/UI callers switch to explicit source ownership.
    fun loadKey(ip: String, port: Int): String? = credentials.load(ReceiverSourceRepository.legacyCredentialName(ip, port))
    fun storeKey(ip: String, port: Int, key: String) {
        if (key.isNotBlank()) credentials.store(ReceiverSourceRepository.legacyCredentialName(ip, port), key)
    }
    fun clearKey(ip: String, port: Int) = credentials.clear(ReceiverSourceRepository.legacyCredentialName(ip, port))
}
