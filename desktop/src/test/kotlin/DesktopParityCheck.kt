import java.io.InputStreamReader
import java.util.Locale
import java.util.Properties

fun desktopParityChecks() {
    println("== Desktop test.17 settings, language and release channel ==")
    fun properties(file: String): Properties = Properties().apply {
        val stream = requireNotNull(Strings::class.java.classLoader.getResourceAsStream(file)) { file }
        stream.use { load(InputStreamReader(it, Charsets.UTF_8)) }
    }
    val english = properties("strings.properties")
    val chinese = properties("strings_zh.properties")
    check(english.stringPropertyNames() == chinese.stringPropertyNames()) {
        "Chinese keys missing=${english.stringPropertyNames() - chinese.stringPropertyNames()}, extra=${chinese.stringPropertyNames() - english.stringPropertyNames()}"
    }
    val format = Regex("%([0-9]+\\$)?[-#+ 0,(]*[0-9]*(?:\\.[0-9]+)?([bBhHsScCdoxXeEfgGaA%n])")
    for (key in english.stringPropertyNames()) {
        fun signature(value: String): List<String> = format.findAll(value).map {
            it.groupValues[1] + it.groupValues[2]
        }.toList()
        check(signature(english.getProperty(key)) == signature(chinese.getProperty(key))) { "Formatter mismatch: $key" }
    }
    Strings.setLanguage("zh")
    check(Strings.get("settings") == "设置")
    check(Strings.get("transmission_volume", 37).contains("37%"))
    check(Strings.get("status_server_waiting", 9090).contains("9090"))
    check(Strings.get("status_multicast_streaming", 9090).contains("9090"))
    check(Strings.get("status_client_connected", "127.0.0.1").contains("127.0.0.1"))
    Strings.setLanguage("en")
    check(Strings.get("settings") == "Settings")
    Strings.setLanguage("system")
    if (Locale.getDefault().language == "zh") check(Strings.get("settings") == "设置")

    check(ConfigManager.deserialize("{}").audio.adaptiveLatency)
    val legacy = ConfigManager.deserialize("""{"audio":{"latencyMs":120}}""")
    check(!legacy.audio.adaptiveLatency && legacy.audio.latencyMs == 120)
    check(ConfigManager.deserialize("""{"audio":{"latencyMs":5000}}""").audio.latencyMs == 400)
    for (ms in 20..400) {
        val settings = ConfigManager.DEFAULTS.copy(
            app = AppSettings(language = "zh"),
            audio = legacy.audio.copy(latencyMs = ms, adaptiveLatency = false)
        )
        val roundTrip = ConfigManager.deserialize(ConfigManager.serialize(settings))
        check(roundTrip.audio.latencyMs == ms && !roundTrip.audio.adaptiveLatency && roundTrip.app.language == "zh")
        check(roundTrip.copy(audio = roundTrip.audio.copy(adaptiveLatency = true)).audio.latencyMs == ms)
    }
    for (value in listOf(19, 401)) {
        check(runCatching { ConfigManager.deserialize("""{"audio":{"latencyMs":$value,"adaptiveLatency":false}}""") }.exceptionOrNull() is ConfigException)
    }
    val fixture = """[
      {"tag_name":"archive-test.13","prerelease":true,"assets":[{"name":"test.13.apk"}]},
      {"tag_name":"v1.3.1","prerelease":false,"assets":[{"name":"AudioBridge-1.3.1-windows-x86_64.zip"}]},
      {"tag_name":"v1.3.1-test.18","prerelease":true,"assets":[{"name":"AudioBridge-1.3.1-test.18-android.apk"}]},
      {"tag_name":"v1.3.1-test.17","prerelease":true,"assets":[{"name":"AudioBridge-1.3.1-test.17-windows-x86_64.zip"}]},
      {"tag_name":"v1.3.1-test.19","prerelease":true,"draft":true,"assets":[{"name":"AudioBridge-1.3.1-test.19-windows-x86_64.zip"}]}
    ]"""
    check(UpdateChecker.selectRelease(fixture, false, "windows-x86_64")?.version == "1.3.1")
    check(UpdateChecker.selectRelease(fixture, true, "windows-x86_64")?.version == "1.3.1-test.17")
    check(UpdateChecker.selectRelease(fixture, true, "macos-arm64") == null)
    check(UpdateChecker.compareVersions("1.3.1", "1.3.1-test.17") > 0)
    check(UpdateChecker.compareVersions("1.3.1-test.18", "1.3.1-test.9") > 0)
    check(UpdateChecker.compareVersions("1.3.1+local", "1.3.1") == 0)
    if (BuildIdentity.preview) {
        check(ConfigPaths.defaultConfigDir().name == "audiobridge-test")
        check(!CliPathInstaller.isInstalled())
        check(!ProtocolRegistrar.ensureRegistered())
    }
    println("Desktop resource coverage, formatter signatures, all 381 manual values and channel filtering passed")
}
