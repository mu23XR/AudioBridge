import java.net.DatagramPacket
import java.net.InetSocketAddress

/** Per-attempt identity: a VPN may rewrite a sender's UDP reply port once. */
internal class DesktopPeerEndpoint(private val advertised: InetSocketAddress, private val multicast: Boolean) {
    private var learnedPort = -1
    private var bound = false

    fun bind() { bound = true }

    fun accepts(packet: DatagramPacket, handshake: Boolean = false): Boolean {
        if (DesktopPeerFilter.accepts(packet, advertised, multicast)) return true
        if (packet.address != advertised.address) return false
        if (packet.port == learnedPort) return true
        if (bound || !handshake || learnedPort >= 0 || !isHandshakeReply(packet)) return false
        learnedPort = packet.port
        // Only a recognized reply can extend the endpoint before binding. Audio,
        // liveness, teardown and RVOL must never redirect an existing session.
        DesktopDiagnostics.event("SOURCE_ENDPOINT_REBOUND", "expectedPort=${advertised.port} replyPort=$learnedPort")
        return true
    }

    private fun isHandshakeReply(packet: DatagramPacket): Boolean {
        if (packet.length <= 0) return false
        val kind = String(packet.data, packet.offset, packet.length, Charsets.US_ASCII).trim().substringBefore(';')
        return kind in HANDSHAKE_REPLIES
    }

    private companion object {
        val HANDSHAKE_REPLIES = setOf("HELLO_ACK", "WFAS_INCOMPATIBLE", "WFAS_BUSY", "WFAS_PENDING",
            "WFAS_UNAUTHORIZED", "WFAS_AUTH_REQUIRED")
    }
}
