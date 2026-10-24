/**
 * Decides whether an incoming microphone datagram belongs to the session that
 * is currently running.
 *
 * Unicast sessions have exactly one owner ([sessionPeer] holds its address), so
 * audio from any other host on the LAN is not this session's audio. An absent
 * owner cannot authorize microphone input. Multicast capture uses a separate
 * policy and does not grant inbound microphone access here.
 *
 * A null [sender] means the platform did not report a source address. It is
 * always rejected.
 */
internal object MicSourceFilter {
    fun accepts(sessionPeer: String?, sender: String?): Boolean =
        sessionPeer != null && sender != null && sender == sessionPeer
}
