internal object MicSourceFilterChecks {
    fun runChecks() {
        // A unicast session has one owner: only that address is this session's audio.
        check(MicSourceFilter.accepts("192.168.1.10", "192.168.1.10"))
        check(!MicSourceFilter.accepts("192.168.1.10", "192.168.1.11"))
        check(!MicSourceFilter.accepts("192.168.1.10", "10.0.0.5"))

        // A datagram without a reported source must not pass as the owner.
        check(!MicSourceFilter.accepts("192.168.1.10", null))

        // An idle/disconnected receiver has no authenticated microphone owner.
        check(!MicSourceFilter.accepts(null, "192.168.1.11"))
        check(!MicSourceFilter.accepts(null, null))

        println("mic source filter: unicast owner match, foreign sender and unknown source rejection passed")
    }
}
