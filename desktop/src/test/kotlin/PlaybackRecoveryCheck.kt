fun playbackRecoveryChecks() {
    println("== WFAS adaptive target and output recovery ==")
    val health = PlaybackHealth(true, 20)
    var now = 0L
    var shortages = 0L
    repeat(14) { now += 1000; health.sample(now, ++shortages, true, 0, 0) }
    check(health.targetMs == 80)
    repeat(31) { now += 1000; health.sample(now, shortages, false, 0, 0) }
    check(health.targetMs == 80) { "Idle/silence must not count as healthy flow" }
    repeat(30) { now += 1000; health.sample(now, shortages, true, 0, 0) }
    check(health.targetMs == 80)
    now += 1000; health.sample(now, shortages, true, 0, 0)
    check(health.targetMs == 75)
    now += 1000; health.sample(now, ++shortages, true, 0, 0)
    repeat(29) { now += 1000; health.sample(now, shortages, true, 0, 0) }
    check(health.targetMs == 75) { "An underrun must reset the clean interval" }
    now += 4000; health.sample(now, shortages, true, 0, 0)
    check(health.targetMs == 75) { "Unobserved sample gaps must reset the clean interval" }
    val manual = PlaybackHealth(false, 41)
    repeat(100) { manual.sample(it * 1000L, it.toLong(), true, 0, 0) }
    check(manual.targetMs == 41)
    check(PlaybackHealth(false, 5, minimumMs = 5).targetMs == 5) { "USB's independent low target must be retained" }

    class Device(val failWrite: Boolean = false, val frozen: Boolean = false, val partial: Boolean = false) : PlaybackOutput {
        override val capacityBytes = 4096
        var written = 0
        var closed = false
        override fun available() = capacityBytes
        override fun framePosition() = if (frozen) 0L else written / 4L
        override fun start() { check(!closed) }
        override fun write(bytes: ByteArray, offset: Int, length: Int): Int {
            check(!closed)
            if (failWrite) error("injected driver error")
            val n = if (partial) minOf(12, length) else length
            written += n
            return n
        }
        override fun close() { closed = true }
    }
    val bytes = ByteArray(128)
    val partial = Device(partial = true)
    RecoverableAudioOutput(partial, 4).useOutput { it.write(bytes, 0, bytes.size) { true } }
    check(partial.written == bytes.size && partial.closed)
    val broken = Device(failWrite = true)
    val replacement = Device()
    var creates = 0
    val recovered = RecoverableAudioOutput(broken, 4, factory = { creates++; replacement })
    recovered.write(bytes, 0, bytes.size) { true }
    recovered.write(bytes, 0, bytes.size) { true }
    check(creates == 1 && broken.closed && replacement.written == 256 && recovered.failedRecoveries == 0)
    recovered.close(); check(replacement.closed)
    var clock = 0L
    val frozen = Device(frozen = true)
    val afterFreeze = Device()
    val thaw = RecoverableAudioOutput(frozen, 4, factory = { afterFreeze }, clockMs = { clock })
    thaw.write(bytes, 0, bytes.size) { true }
    clock = 6000
    thaw.write(bytes, 0, bytes.size) { true }
    check(frozen.closed && afterFreeze.written == 128)
    // The same policy object survives a line rebuild; an increased target is retained.
    check(health.targetMs == 75)
    thaw.close()
    clock = 0
    val neverOpens = RecoverableAudioOutput(Device(failWrite = true), 4,
        factory = { error("injected open error") }, clockMs = { clock }, pause = { clock++ })
    val failure = runCatching { neverOpens.write(bytes, 0, bytes.size) { true } }.exceptionOrNull()
    check(failure is PlaybackOutputFailure && neverOpens.failedRecoveries == 3)
    neverOpens.close()
    val cancelled = Device()
    RecoverableAudioOutput(cancelled, 4).useOutput { it.write(bytes, 0, bytes.size) { false } }
    check(cancelled.written == 0 && cancelled.closed)
    println("Adaptive bounds/healthy intervals, partial writes, injected stall/open failures and cleanup passed")
}

private inline fun RecoverableAudioOutput.useOutput(block: (RecoverableAudioOutput) -> Unit) {
    try { start(); block(this) } finally { close() }
}
