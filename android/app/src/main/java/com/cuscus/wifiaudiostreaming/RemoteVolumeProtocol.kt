package com.cuscus.wifiaudiostreaming

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

enum class RemoteVolumeSupport { UNKNOWN, UNSUPPORTED, SUPPORTED }

data class RemoteVolumeState(
    val support: RemoteVolumeSupport = RemoteVolumeSupport.UNKNOWN,
    val percent: Int? = null,
    val pending: Boolean = false,
    val error: String? = null
)

/** Optional WFAS v2 control. Transport must enforce the connected peer endpoint first. */
object RemoteVolumeProtocol {
    // Streaming controls cannot start with the binary audio magic "WF".
    const val GET = "RVOL_GET"
    const val SET = "RVOL_SET"
    const val STATE = "RVOL_STATE"
    private const val RETRY_MS = 1_000L
    private const val MAX_SENDS = 3

    interface Volume {
        fun getPercent(): Int
        fun setPercent(value: Int)
    }

    @JvmStatic fun nonce(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }.hex()
    @JvmStatic fun helloSuffix(clientNonce: String): String {
        require(validNonce(clientNonce))
        return ";rvol=1;rvnonce=$clientNonce"
    }

    @JvmStatic fun offer(hello: String, key: String, cnonce: String, snonce: String, volume: Volume): Server? {
        val tokens = tokens(hello) ?: return null
        val nonce = tokens["rvnonce"] ?: return null
        if (tokens["rvol"] != "1" || !validNonce(nonce)) return null
        if (key.isNotEmpty() && (!validNonce(cnonce) || !validNonce(snonce))) return null
        return Server(nonce, key, cnonce, snonce, volume)
    }

    @JvmStatic fun accept(ack: String, clientNonce: String, key: String, cnonce: String, snonce: String): Client? {
        val values = tokens(ack) ?: return null
        val sid = values["rvsid"] ?: return null
        if (values["rvol"] != "1" || !validNonce(sid) || !validNonce(clientNonce)) return null
        if (key.isNotEmpty() && (!validNonce(cnonce) || !validNonce(snonce))) return null
        val auth = Auth(key, cnonce, snonce, clientNonce, sid)
        if (key.isNotEmpty() && !auth.verifyOffer(values["rvmac"])) return null
        return Client(sid, auth)
    }

    class Server internal constructor(
        private val clientNonce: String,
        key: String, cnonce: String, snonce: String,
        private val volume: Volume
    ) {
        private val sid = nonce()
        private val auth = Auth(key, cnonce, snonce, clientNonce, sid)
        private var revision = 0L
        private var percent = checkedPercent(volume.getPercent())
        private var highestRequest = 0L
        private var lastRequest = ""

        fun ackSuffix(): String = ";rvol=1;rvsid=$sid" + auth.offerSuffix()
        fun acceptsHello(hello: String): Boolean = tokens(hello)?.let {
            it["rvol"] == "1" && it["rvnonce"] == clientNonce
        } == true

        @Synchronized fun receive(message: String): String? {
            val fields = tokens(message) ?: return null
            val kind = message.substringBefore(';')
            val expected = if (kind == SET) setOf("sid", "n", "pct") else setOf("sid", "n")
            if (kind != GET && kind != SET || !auth.validFields(fields, expected) || fields["sid"] != sid) return null
            val sequence = positive(fields["n"]) ?: return null
            if (!auth.verify('C', message, fields["mac"])) return null
            if (sequence < highestRequest) return null
            if (sequence == highestRequest) {
                // Retries acknowledge the same operation but can never apply it twice.
                if (message != lastRequest) return null
            } else {
                val requested = if (kind == SET) parsedPercent(fields["pct"]) ?: return null else null
                if (requested != null) volume.setPercent(requested)
                highestRequest = sequence
                lastRequest = message
            }
            refresh()
            return state(sequence)
        }

        @Synchronized fun pushIfChanged(): String? = if (refresh()) state(0) else null
        // Small periodic snapshots repair a lost UDP push without resetting
        // volume or requiring an audio reconnect. Same-revision repeats are inert.
        @Synchronized fun snapshot(): String { refresh(); return state(0) }

        private fun refresh(): Boolean {
            val current = checkedPercent(volume.getPercent())
            if (current == percent) return false
            percent = current
            check(revision < Long.MAX_VALUE) { "Remote volume revision exhausted" }
            revision++
            return true
        }
        private fun state(request: Long) = auth.sign('S', "$STATE;sid=$sid;q=$request;rev=$revision;pct=$percent")
    }

    class Client internal constructor(private val sid: String, private val auth: Auth) {
        private data class Request(val sequence: Long, val message: String, var sends: Int = 0, var at: Long = 0)
        private var sequence = 0L
        private var revision = -1L
        private var request: Request? = null
        private var latest = RemoteVolumeState(RemoteVolumeSupport.SUPPORTED)

        @Synchronized fun state(): RemoteVolumeState = latest
        @Synchronized fun query() { enqueue(GET, null) }
        @Synchronized fun setPercent(value: Int) {
            require(value in 0..100)
            enqueue(SET, value)
        }
        private fun enqueue(kind: String, percent: Int?) {
            check(sequence < Long.MAX_VALUE) { "Remote volume sequence exhausted" }
            sequence++
            val body = "$kind;sid=$sid;n=$sequence" + (percent?.let { ";pct=$it" } ?: "")
            request = Request(sequence, auth.sign('C', body))
            // Preserve the confirmed sender value until its acknowledgement arrives.
            latest = latest.copy(pending = true, error = null)
        }

        @Synchronized fun nextMessage(now: Long): String? {
            val current = request ?: return null
            if (current.sends > 0 && now - current.at < RETRY_MS) return null
            if (current.sends >= MAX_SENDS) {
                request = null
                latest = latest.copy(pending = false, error = "TIMEOUT")
                return null
            }
            current.sends++
            current.at = now
            return current.message
        }

        @Synchronized fun receive(message: String): Boolean {
            if (message.substringBefore(';') != STATE) return false
            val fields = tokens(message) ?: return false
            if (!auth.validFields(fields, setOf("sid", "q", "rev", "pct")) || fields["sid"] != sid) return false
            val requestNumber = nonnegative(fields["q"]) ?: return false
            val version = nonnegative(fields["rev"]) ?: return false
            val value = parsedPercent(fields["pct"]) ?: return false
            if (!auth.verify('S', message, fields["mac"])) return false
            if (version < revision) {
                // A newer authoritative push can overtake the ACK. Confirm its
                // operation without rolling the displayed sender value backward.
                if (request?.sequence != requestNumber) return false
                request = null
                latest = latest.copy(pending = false, error = null)
                return true
            }
            // A revision has one authoritative value, even if its push and ACK reorder.
            if (version == revision && latest.percent != value) return false
            revision = version
            val ack = request?.sequence == requestNumber
            if (ack) request = null
            latest = latest.copy(percent = value, pending = request != null,
                error = if (ack) null else latest.error)
            return true
        }
    }

    internal class Auth(
        private val key: String, cnonce: String, snonce: String,
        clientNonce: String, private val sid: String
    ) {
        private val binding = "$cnonce:$snonce:$clientNonce:$sid"
        fun offerSuffix(): String = if (key.isEmpty()) "" else ";rvmac=${mac('S', "OFFER") }"
        fun verifyOffer(value: String?): Boolean = value != null && equal(value, mac('S', "OFFER"))
        fun sign(side: Char, body: String): String = if (key.isEmpty()) body else "$body;mac=${mac(side, body)}"
        fun verify(side: Char, message: String, value: String?): Boolean {
            if (key.isEmpty()) return value == null
            if (value == null || !Regex("[0-9a-f]{64}").matches(value)) return false
            val body = message.substringBeforeLast(";mac=")
            return message == "$body;mac=$value" && equal(value, mac(side, body))
        }
        fun validFields(fields: Map<String, String>, required: Set<String>): Boolean =
            fields.keys == if (key.isEmpty()) required else required + "mac"
        private fun mac(side: Char, body: String): String {
            val hmac = Mac.getInstance("HmacSHA256")
            hmac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            return hmac.doFinal("WFAS-RVOL-$side:$binding:$body".toByteArray(Charsets.US_ASCII)).hex()
        }
    }

    private fun tokens(message: String): Map<String, String>? {
        if (message.length > 1024 || message.any { it.code !in 32..126 }) return null
        val values = LinkedHashMap<String, String>()
        for (part in message.split(';').drop(1)) {
            val at = part.indexOf('=')
            if (at <= 0 || at == part.lastIndex || values.put(part.substring(0, at), part.substring(at + 1)) != null) return null
        }
        return values
    }
    private fun checkedPercent(value: Int): Int { require(value in 0..100); return value }
    private fun parsedPercent(value: String?): Int? = nonnegative(value)?.takeIf { it <= 100 }?.toInt()
    private fun positive(value: String?): Long? = nonnegative(value)?.takeIf { it > 0 }
    private fun nonnegative(value: String?): Long? = value?.takeIf { it.matches(Regex("0|[1-9][0-9]{0,18}")) }
        ?.toLongOrNull()?.takeIf { it >= 0 }
    private fun validNonce(value: String): Boolean = value.matches(Regex("[0-9a-f]{32}"))
    private fun equal(a: String, b: String): Boolean = MessageDigest.isEqual(a.toByteArray(Charsets.US_ASCII), b.toByteArray(Charsets.US_ASCII))
    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 255) }
}
