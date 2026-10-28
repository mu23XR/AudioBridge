package com.cuscus.wifiaudiostreaming

/**
 * Decides whether an incoming datagram belongs to the expected peer.
 *
 * The address is the peer's identity. The source port normally equals the
 * advertised one, but anything that re-originates UDP on the sender's device can
 * rewrite it: with a userspace TUN VPN on the sending device, the sender's
 * replies left from a different source port than the one it advertised, so a
 * strict port match discarded every packet of a whole session and the client
 * kept reporting `NO_ACK` after six unanswered HELLOs.
 *
 * Only a handshake reply observed before the endpoint is bound may point the
 * session at such a rewritten port, and the port is then remembered for that
 * session. Audio frames never widen the endpoint: they are accepted only from
 * the advertised or an already-learned port. This is session isolation, not
 * cryptographic authentication - a same-host socket that wins the handshake race
 * is still treated as the peer, which is why `WFAS_PROTOCOL.md` points at Key
 * mode when protection from LAN spoofing is required.
 */
internal object PeerEndpointPolicy {
    enum class Verdict { ACCEPT, LEARN, REJECT }

    /**
     * Handshake reply shapes that may bind the session to a rewritten source
     * port. Deliberately excludes `PING`/`PONG`/`BYE`: three arbitrary bytes
     * would otherwise be enough for an unrelated socket on the same host to
     * tear down or starve a live session. The remote-volume `RVOL_*` traffic is
     * not part of the handshake either.
     */
    fun isHandshakeReply(control: String): Boolean =
        control.startsWith("HELLO_ACK") || control.startsWith("WFAS_INCOMPATIBLE") ||
            control.startsWith("WFAS_BUSY") || control.startsWith("WFAS_PENDING") ||
            control.startsWith("WFAS_UNAUTHORIZED") || control.startsWith("WFAS_AUTH_REQUIRED")

    fun verdict(
        sameAddress: Boolean,
        multicast: Boolean,
        expectedPort: Int,
        sourcePort: Int,
        learnedPort: Int,
        handshakeReply: Boolean
    ): Verdict = when {
        !sameAddress -> Verdict.REJECT
        // A multicast group can contain several senders; the port carries no
        // identity there, only the address does.
        multicast -> Verdict.ACCEPT
        sourcePort == expectedPort -> Verdict.ACCEPT
        sourcePort == learnedPort -> Verdict.ACCEPT
        handshakeReply && learnedPort < 0 -> Verdict.LEARN
        else -> Verdict.REJECT
    }
}
