/*
 * Controlli sul parser SDP della ricezione RTP.
 *
 * Gli SDP di riferimento sono quelli che si incontrano davvero: quello emesso
 * da questa stessa app, quelli di ffmpeg, uno con payload type statico senza
 * rtpmap, uno con video prima dell'audio, e i casi degeneri. Il parser deve
 * essere tollerante: preferire un risultato con avvisi al rifiuto secco.
 */

private var rtpSdpFailures = 0

private fun rtpSdpCheck(name: String, cond: Boolean, detail: String = "") {
    if (cond) println("  ok   $name")
    else {
        println("  FAIL $name ${if (detail.isNotBlank()) "-> $detail" else ""}")
        rtpSdpFailures++
    }
}

private fun rtpSdpEq(name: String, actual: Any?, expected: Any?) =
    rtpSdpCheck(name, actual == expected, "actual=$actual expected=$expected")

fun rtpSdpChecks() {
    println("== rtp sdp parser ==")

    // 1. Quello che emette WFAS stesso (multicast)
    val wfas = """
        v=0
        o=- 1788900000 1788900000 IN IP4 192.168.1.10
        s=AudioBridge
        i=WFAS RTP stream -wfas.app
        c=IN IP4 239.255.0.1/4
        t=0 0
        a=tool:wfas
        m=audio 9094 RTP/AVP 96
        a=rtpmap:96 L16/48000/2
        a=ptime:10
        a=recvonly
    """.trimIndent()
    RtpSdp.parse(wfas).let { r ->
        rtpSdpCheck("wfas: ok", r.ok, r.error ?: "")
        rtpSdpEq("wfas: indirizzo", r.source?.address, "239.255.0.1")
        rtpSdpEq("wfas: porta", r.source?.port, 9094)
        rtpSdpEq("wfas: codec", r.source?.encoding, "L16")
        rtpSdpEq("wfas: rate", r.source?.sampleRate, 48000)
        rtpSdpEq("wfas: canali", r.source?.channels, 2)
        rtpSdpEq("wfas: pt", r.source?.payloadType, 96)
        rtpSdpEq("wfas: nome", r.source?.name, "AudioBridge")
        rtpSdpCheck("wfas: multicast", r.source?.isMulticast == true)
        rtpSdpCheck("wfas: percorso nativo", r.source?.isNativePcm == true)
        rtpSdpCheck("wfas: nessun avviso", r.warnings.isEmpty(), r.warnings.toString())
    }

    // 2. ffmpeg -f rtp: unicast, TTL assente, riga i= assente
    val ff = """
        v=0
        o=- 0 0 IN IP4 127.0.0.1
        s=No Name
        c=IN IP4 192.168.1.44
        t=0 0
        a=tool:libavformat 60.16.100
        m=audio 5004 RTP/AVP 97
        b=AS:128
        a=rtpmap:97 opus/48000/2
        a=fmtp:97 sprop-stereo=1
    """.trimIndent()
    RtpSdp.parse(ff).let { r ->
        rtpSdpCheck("ffmpeg/opus: ok", r.ok, r.error ?: "")
        rtpSdpEq("ffmpeg/opus: codec", r.source?.encoding, "opus")
        rtpSdpEq("ffmpeg/opus: porta", r.source?.port, 5004)
        rtpSdpCheck("ffmpeg/opus: non multicast", r.source?.isMulticast == false)
        rtpSdpCheck("ffmpeg/opus: va su ffmpeg", r.source?.isNativePcm == false)
    }

    // 3. Payload type statico, senza rtpmap (RFC 3551 PT 10 = L16/44100/2)
    RtpSdp.parse("v=0\no=- 0 0 IN IP4 10.0.0.1\ns=-\nc=IN IP4 10.0.0.1\nt=0 0\nm=audio 5555 RTP/AVP 10").let { r ->
        rtpSdpCheck("pt statico: ok", r.ok, r.error ?: "")
        rtpSdpEq("pt statico: codec", r.source?.encoding, "L16")
        rtpSdpEq("pt statico: rate", r.source?.sampleRate, 44100)
        rtpSdpEq("pt statico: canali", r.source?.channels, 2)
        rtpSdpEq("pt statico: nome vuoto da s=-", r.source?.name, "")
    }

    // 4. c= solo a livello di media, e video prima dell'audio
    val mixed = """
        v=0
        o=- 0 0 IN IP4 127.0.0.1
        s=cam
        t=0 0
        m=video 5000 RTP/AVP 96
        a=rtpmap:96 H264/90000
        m=audio 5002 RTP/AVP 98
        c=IN IP4 239.1.2.3/16
        a=rtpmap:98 L16/44100/1
    """.trimIndent()
    RtpSdp.parse(mixed).let { r ->
        rtpSdpCheck("misto: ok", r.ok, r.error ?: "")
        rtpSdpEq("misto: prende l'audio", r.source?.port, 5002)
        rtpSdpEq("misto: c= del media", r.source?.address, "239.1.2.3")
        rtpSdpEq("misto: mono", r.source?.channels, 1)
        rtpSdpEq("misto: codec audio, non H264", r.source?.encoding, "L16")
        rtpSdpCheck("misto: avvisa delle altre tracce", "sdp_warn_multi_media" in r.warnings, r.warnings.toString())
    }

    // 5. Casi degeneri
    rtpSdpCheck("vuoto -> errore", RtpSdp.parse("").error == "sdp_err_empty")
    rtpSdpCheck("non-sdp -> errore", RtpSdp.parse("ciao\nmondo").error == "sdp_err_no_media")
    rtpSdpCheck("solo video -> nessun audio", RtpSdp.parse("v=0\ns=x\nt=0 0\nm=video 5000 RTP/AVP 96").error == "sdp_err_no_audio")
    rtpSdpCheck("porta 0 -> disattivata", RtpSdp.parse("v=0\ns=x\nc=IN IP4 1.2.3.4\nt=0 0\nm=audio 0 RTP/AVP 96").error == "sdp_err_port_zero")

    // 6. Senza c= e senza rtpmap: si usa comunque, con avvisi
    RtpSdp.parse("v=0\ns=x\nt=0 0\nm=audio 9000 RTP/AVP 96").let { r ->
        rtpSdpCheck("minimale: comunque usabile", r.ok, r.error ?: "")
        rtpSdpCheck("minimale: avvisa dell'indirizzo", "sdp_warn_no_connection" in r.warnings, r.warnings.toString())
        rtpSdpCheck("minimale: avvisa del codec", "sdp_warn_no_rtpmap" in r.warnings, r.warnings.toString())
        rtpSdpEq("minimale: default L16", r.source?.encoding, "L16")
    }

    // 7. CRLF e spazi: gli SDP incollati arrivano quasi sempre cosi'
    RtpSdp.parse("v=0\r\n o=- 0 0 IN IP4 1.1.1.1 \r\ns=x\r\nc=IN IP4 224.0.0.9\r\nt=0 0\r\nm=audio 7000 RTP/AVP 96\r\na=rtpmap:96 L16/48000/2\r\n").let { r ->
        rtpSdpCheck("crlf: ok", r.ok, r.error ?: "")
        rtpSdpEq("crlf: indirizzo", r.source?.address, "224.0.0.9")
        rtpSdpEq("crlf: porta", r.source?.port, 7000)
    }

    // 8. Andata e ritorno: sintetizza -> riparsa
    val manual = RtpSource(name = "Mixer", address = "239.9.9.9", port = 5004,
                           payloadType = 100, encoding = "L16", sampleRate = 44100, channels = 2)
    RtpSdp.parse(RtpSdp.synthesize(manual)).let { r ->
        rtpSdpCheck("roundtrip: ok", r.ok, r.error ?: "")
        rtpSdpEq("roundtrip: indirizzo", r.source?.address, manual.address)
        rtpSdpEq("roundtrip: porta", r.source?.port, manual.port)
        rtpSdpEq("roundtrip: pt", r.source?.payloadType, manual.payloadType)
        rtpSdpEq("roundtrip: rate", r.source?.sampleRate, manual.sampleRate)
        rtpSdpEq("roundtrip: canali", r.source?.channels, manual.channels)
    }

    // 9. Persistenza
    val saved = RtpSource(name = "Sala|prove", address = "239.0.0.5", port = 9094,
                          payloadType = 96, encoding = "L16", sampleRate = 48000, channels = 2)
    RtpSource.deserialize(saved.serialize()).let { d ->
        rtpSdpCheck("persistenza: rileggibile", d != null)
        rtpSdpEq("persistenza: la barra nel nome e' neutralizzata", d?.name, "Sala/prove")
        rtpSdpEq("persistenza: indirizzo", d?.address, saved.address)
        rtpSdpEq("persistenza: porta", d?.port, saved.port)
    }
    rtpSdpCheck("persistenza: riga corrotta -> null", RtpSource.deserialize("boh") == null)

    // 10. Validazione
    rtpSdpCheck("validazione: default ok", RtpSdp.validate(RtpSource()).isEmpty())
    rtpSdpCheck("validazione: porta fuori scala", "rtp_val_port" in RtpSdp.validate(RtpSource(port = 0)))
    rtpSdpCheck("validazione: 6 canali", "rtp_val_channels" in RtpSdp.validate(RtpSource(channels = 6)))
    rtpSdpCheck("validazione: indirizzo storto", "rtp_val_address" in RtpSdp.validate(RtpSource(address = "999.1.@@")))
    rtpSdpCheck("validazione: nome host ok", RtpSdp.validate(RtpSource(address = "studio.local")).isEmpty())
    rtpSdpCheck("multicast: 224-239", RtpSdp.isMulticastAddress("230.1.1.1") && !RtpSdp.isMulticastAddress("192.168.1.1"))
    rtpSdpCheck("multicast: ipv6 ff", RtpSdp.isMulticastAddress("ff02::1"))

    if (rtpSdpFailures > 0) {
        println("  $rtpSdpFailures controlli falliti")
        kotlin.system.exitProcess(1)
    }
}
