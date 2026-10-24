/** Portable regressions from the fixed Android test21 baseline (9ecbbc4). */
fun receiverProtocolChecks() {
    println("== Receiver peer controls, connection cues and packet history ==")
    remoteVolumeProtocolChecks()
    connectionCueChecks()
    packetHistoryChecks()
    datagramClassificationChecks()
    println("Receiver protocol compatibility, replay, retry and silence regressions passed")
}

private class CheckRemoteVolume(var value: Int = 50) : RemoteVolumeProtocol.Volume {
    var writes = 0
    override fun getPercent() = value
    override fun setPercent(value: Int) { this.value = value; writes++ }
}

private val volumeClientNonce = "1".repeat(32)
private val volumeAuthClient = "2".repeat(32)
private val volumeAuthServer = "3".repeat(32)

private fun volumePair(
    volume: CheckRemoteVolume = CheckRemoteVolume(), key: String = ""
): Pair<RemoteVolumeProtocol.Server, RemoteVolumeProtocol.Client> {
    val hello = "HELLO_FROM_CLIENT;v=2" + RemoteVolumeProtocol.helloSuffix(volumeClientNonce)
    val server = requireNotNull(RemoteVolumeProtocol.offer(hello, key, volumeAuthClient, volumeAuthServer, volume))
    val client = requireNotNull(RemoteVolumeProtocol.accept("HELLO_ACK;v=2" + server.ackSuffix(),
        volumeClientNonce, key, volumeAuthClient, volumeAuthServer))
    return server to client
}

private fun remoteVolumeProtocolChecks() {
    check(RemoteVolumeProtocol.offer("HELLO_FROM_CLIENT;v=2", "", "", "", CheckRemoteVolume()) == null)
    check(RemoteVolumeProtocol.accept("HELLO_ACK;v=2", volumeClientNonce, "", "", "") == null)
    check(RemoteVolumeProtocol.nonce().matches(Regex("[0-9a-f]{32}")))
    check(runCatching { RemoteVolumeProtocol.helloSuffix("invalid") }.isFailure)

    // A connection first queries the remembered sender value, never overwrites it.
    val remembered = CheckRemoteVolume(83)
    repeat(2) {
        val (server, client) = volumePair(remembered)
        check(server.acceptsHello("HELLO_FROM_CLIENT;v=2" + RemoteVolumeProtocol.helloSuffix(volumeClientNonce)))
        check(!server.acceptsHello("HELLO_FROM_CLIENT;v=2" + RemoteVolumeProtocol.helloSuffix("4".repeat(32))))
        client.query()
        check(client.state().percent == null)
        check(client.receive(requireNotNull(server.receive(requireNotNull(client.nextMessage(0))))))
        check(client.state().percent == 83 && !client.state().pending)
    }
    check(remembered.writes == 0)

    run {
        val volume = CheckRemoteVolume()
        val (server, client) = volumePair(volume)
        check(client.receive(server.snapshot()))
        client.setPercent(100)
        check(client.state().percent == 50 && client.state().pending)
        val request = requireNotNull(client.nextMessage(0))
        val ack = requireNotNull(server.receive(request))
        check(volume.value == 100 && volume.writes == 1)
        check(client.nextMessage(999) == null)
        check(client.nextMessage(1_000) == request)
        check(server.receive(request) != null && volume.writes == 1)
        check(client.receive(ack))
        check(client.state().percent == 100 && !client.state().pending)
        check(server.pushIfChanged() == null)
        volume.value = 31
        check(client.receive(requireNotNull(server.pushIfChanged())))
        check(client.state().percent == 31 && server.pushIfChanged() == null)
    }

    run {
        val (server, client) = volumePair()
        client.setPercent(20)
        val first = requireNotNull(client.nextMessage(0))
        val staleAck = requireNotNull(server.receive(first))
        client.setPercent(80)
        val secondAck = requireNotNull(server.receive(requireNotNull(client.nextMessage(1))))
        check(server.receive(first) == null)
        check(client.receive(staleAck) && client.state().pending)
        check(client.receive(secondAck))
        check(!client.receive(staleAck))
        check(client.state().percent == 80 && !client.state().pending)
    }

    run {
        val volume = CheckRemoteVolume()
        val (server, client) = volumePair(volume)
        client.setPercent(70)
        val delayedAck = requireNotNull(server.receive(requireNotNull(client.nextMessage(0))))
        volume.value = 90
        check(client.receive(server.snapshot()) && client.state().pending)
        check(client.receive(delayedAck))
        check(client.state().percent == 90 && !client.state().pending)
    }

    run {
        val (server, client) = volumePair()
        check(client.receive(server.snapshot()))
        client.setPercent(2)
        for (now in listOf(0L, 1_000L, 2_000L)) check(client.nextMessage(now) != null)
        check(client.nextMessage(3_000) == null && client.nextMessage(99_000) == null)
        check(client.state().percent == 50 && client.state().error == "TIMEOUT" && !client.state().pending)
        client.query()
        check(client.receive(requireNotNull(server.receive(requireNotNull(client.nextMessage(100_000))))))
        check(client.state().error == null)
    }

    run {
        val volume = CheckRemoteVolume()
        val (server, client) = volumePair(volume, "shared secret")
        client.setPercent(60)
        val request = requireNotNull(client.nextMessage(0))
        check(server.receive(request.replace("pct=60", "pct=61")) == null)
        check(server.receive(request.substringBefore(";mac=")) == null)
        val response = requireNotNull(server.receive(request))
        check(!client.receive(response.replace("pct=60", "pct=61")))
        check(server.receive(response) == null && !client.receive(request))
        check(client.receive(response))
        val ack = "HELLO_ACK;v=2" + server.ackSuffix()
        check(RemoteVolumeProtocol.accept(ack, volumeClientNonce, "wrong secret", volumeAuthClient, volumeAuthServer) == null)
        check(RemoteVolumeProtocol.accept(ack, "4".repeat(32), "shared secret", volumeAuthClient, volumeAuthServer) == null)
        check(RemoteVolumeProtocol.accept(ack, volumeClientNonce, "shared secret", "5".repeat(32), volumeAuthServer) == null)
        check(RemoteVolumeProtocol.accept(ack.substringBefore(";rvmac="), volumeClientNonce,
            "shared secret", volumeAuthClient, volumeAuthServer) == null)
        check(volume.writes == 1)
    }

    // A new session rejects captured traffic even with optional authentication off.
    run {
        val volume = CheckRemoteVolume()
        val (oldServer, oldClient) = volumePair(volume)
        oldClient.setPercent(0)
        val captured = requireNotNull(oldClient.nextMessage(0))
        val (nextServer, nextClient) = volumePair(volume)
        check(nextServer.receive(captured) == null && !nextClient.receive(oldServer.snapshot()))
        check(volume.value == 50)
    }

    run {
        val volume = CheckRemoteVolume()
        val (server, client) = volumePair(volume)
        check(runCatching { client.setPercent(-1) }.isFailure)
        check(runCatching { client.setPercent(101) }.isFailure)
        client.setPercent(100)
        val valid = requireNotNull(client.nextMessage(0))
        val malformed = listOf(
            valid.replace("pct=100", "pct=101"), valid.replace("pct=100", "pct=+1"), valid + ";pct=0",
            valid.replace("n=1", "n=-1"), valid.replace("n=1", "n=9223372036854775808"), valid + ";extra=1"
        )
        malformed.forEach { check(server.receive(it) == null) }
        check(volume.writes == 0)
        check(server.receive(valid) != null && volume.writes == 1)
    }
}

private fun connectionCueChecks() {
    val policy = ConnectionCuePolicy()
    policy.beginAttempt(true) // ACK or rejected data does not consume the cue.
    val first = policy.beginAttempt(true)
    check(first.onValidFrame())
    check(!first.onValidFrame() && !policy.beginAttempt(true).onValidFrame())
    policy.reset()
    check(!first.onValidFrame())
    check(policy.beginAttempt(true).onValidFrame())
    policy.reset()
    val replaced = policy.beginAttempt(true)
    policy.reset()
    check(!replaced.onValidFrame())
    check(policy.beginAttempt(true).onValidFrame())
    policy.reset()
    check(!policy.beginAttempt(false).onValidFrame())
    check(!policy.beginAttempt(true).onValidFrame())
    policy.reset()
    check(policy.beginAttempt(true).onValidFrame())
}

private fun packetHistoryChecks() {
    run {
        var now = 0L
        val metrics = DesktopPacketMetrics(48_000) { now }
        check(metrics.observe(10, 0))
        now += 10_000_000; check(metrics.observe(11, 480))
        now += 1_000_000_000; check(metrics.observe(12, 960, true))
        now += 1_000_000_000; check(metrics.observe(13, 960, true))
        now += 10_000_000; check(metrics.observe(14, 960))
        val snapshot = metrics.snapshot()
        check(snapshot.packets == 5L && snapshot.lost == 0L)
        check(snapshot.jitterMs == 0.0 && snapshot.meanIntervalMs == 10.0)
    }
    run {
        val metrics = DesktopPacketMetrics(48_000) { 0L }
        check(metrics.observe(65534, 0))
        check(metrics.observe(0, 1) && metrics.snapshot().lost == 1L)
        check(!metrics.observe(65535, 0) && metrics.snapshot().lost == 0L)
        check(!metrics.observe(0, 1) && !metrics.observe(65535, 0))
        check(metrics.snapshot().duplicates == 2L && metrics.snapshot().reordered == 1L)
        check(metrics.observe(1, 2))
        check(metrics.snapshot().packets == 4L)
    }
    run {
        val metrics = DesktopPacketMetrics(48_000) { 0L }
        check(metrics.observe(1, 0) && metrics.observe(3, 0, true))
        check(metrics.snapshot().lost == 1L)
        check(!metrics.observe(2, 0, true) && metrics.snapshot().lost == 0L)
    }
    run {
        var now = 0L
        val metrics = DesktopPacketMetrics(48_000) { now }
        check(metrics.observe(1, 0xfffffff0L))
        now += 10_000_000
        check(metrics.observe(2, (0xfffffff0L + 480) and 0xffffffffL))
        check(metrics.snapshot().jitterMs == 0.0)
        check(metrics.observe(5000, 1))
        check(!metrics.observe(1, 0)) // Older than the retained replay history.
    }
    check(runCatching { DesktopPacketMetrics(0) }.isFailure)
    check(runCatching { DesktopPacketMetrics(48_000).observe(65536, 0) }.isFailure)
    LinkMetrics.start("CHECK", 48_000)
    try {
        check(LinkMetrics.onPacket(10, 0))
        check(LinkMetrics.onPacket(12, 480, true))
        check(!LinkMetrics.onPacket(11, 240))
        check(!LinkMetrics.onPacket(11, 240))
    } finally { LinkMetrics.stop() }
    check(LinkMetrics.snapshot.transport == "CHECK" && LinkMetrics.snapshot.packets == 3L)
    check(LinkMetrics.snapshot.lost == 0L && LinkMetrics.snapshot.duplicates == 1L)
}

private fun datagramClassificationChecks() {
    for (kind in listOf(RemoteVolumeProtocol.GET, RemoteVolumeProtocol.SET, RemoteVolumeProtocol.STATE)) {
        check(!kind.startsWith("WF"))
        val packet = "$kind;sid=session;n=1".toByteArray()
        check(!WfasDatagram.isAudio(packet, packet.size, false))
    }
    val beacon = WfasCrypto.buildMcastBeacon("fixture-key", 5, 1000, ByteArray(16) { it.toByte() })
    val beaconPacket = beacon.toByteArray()
    check(!WfasDatagram.isAudio(beaconPacket, beaconPacket.size, true))
    check(WfasCrypto.parseMcastBeacon("fixture-key", beacon, 4) != null)
    check(WfasCrypto.parseMcastBeacon("wrong-key", beacon, 4) == null)
    check(WfasDatagram.isAudio(beaconPacket, beaconPacket.size, false))
    val unrelated = "WFAS_MCAST_ENCevil;epoch=5".toByteArray()
    check(WfasDatagram.isAudio(unrelated, unrelated.size, true))
    for (version in listOf(2, 65, 255)) {
        val packet = byteArrayOf(0x57, 0x46, version.toByte(), 1, 0, 0, 0, 0, 0, 0)
        check(WfasDatagram.isAudio(packet, packet.size, false))
        check(WfasDatagram.isAudio(packet, packet.size, true))
    }
    check(!WfasDatagram.isAudio(ByteArray(0), 0, false))
    check(runCatching { WfasDatagram.isAudio(ByteArray(0), 1, false) }.isFailure)
}
