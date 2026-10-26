import java.nio.file.Files

object DesktopConfigurationChecks {
    @JvmStatic fun runChecks() {
        val defaults = ConfigManager.deserialize("{}")
        check(defaults.app.senderGain == 1f && defaults.app.receiverGain == 1f)
        check(!defaults.app.remoteVolumeSyncEnabled && defaults.app.receiverSources.isEmpty())
        check(DesktopVolumeScale.percent(defaults.app.senderGain, 2f) == 50)
        check(DesktopVolumeScale.percent(defaults.app.receiverGain, 1f) == 100)
        for (percent in 0..100) {
            check(DesktopVolumeScale.percent(DesktopVolumeScale.gain(percent, 2f), 2f) == percent)
            check(DesktopVolumeScale.percent(DesktopVolumeScale.gain(percent, 1f), 1f) == percent)
        }
        for (oldAutoVolume in -1..200) {
            val migrated = ConfigManager.deserialize("""{"server":{"autoStartVolume":$oldAutoVolume,"muteRender":false}}""")
            check(migrated.app.autoStartVolume == oldAutoVolume && !migrated.app.muteRender)
            check(migrated.app.senderGain == 1f) { "Unsaved old runtime volume must not become amplification" }
        }
        val sourceMetadata = """{"ip":"192.0.2.1","port":9090,"gain":0.6}"""
        val settings = defaults.copy(app = defaults.app.copy(senderGain = 1.42f, receiverGain = 0.31f,
            remoteVolumeSyncEnabled = true, muteRender = false, receiverSources = listOf(sourceMetadata)))
        val roundTrip = ConfigManager.deserialize(ConfigManager.serialize(settings))
        check(roundTrip.app.senderGain == settings.app.senderGain && roundTrip.app.receiverGain == settings.app.receiverGain)
        check(roundTrip.app.remoteVolumeSyncEnabled && !roundTrip.app.muteRender && roundTrip.app.receiverSources == listOf(sourceMetadata))
        for ((field, maximum) in listOf("server.gain" to 2f, "receiver.gain" to 1f, "audio.sampleRate" to 384000f)) {
            for (value in listOf("NaN", "Infinity", "-Infinity")) {
                val failure = runCatching { ConfigManager.withSet(defaults, field, value) }.exceptionOrNull()
                check(failure is ConfigException && failure.message?.contains(field) == true && failure.message?.contains("finite") == true)
            }
            for (value in listOf(-1f, maximum + 1f)) {
                check(runCatching { ConfigManager.withSet(defaults, field, value) }.exceptionOrNull() is ConfigException)
            }
        }
        val directory = Files.createTempDirectory("audiobridge-config-check-").toFile()
        val previous = ConfigPaths.overrideConfigFile
        try {
            ConfigPaths.overrideConfigFile = directory.resolve("config.json")
            ConfigManager.save(settings)
            val loaded = ConfigManager.load()
            check(loaded.app.senderGain == 1.42f && loaded.app.receiverGain == 0.31f)
            check(loaded.app.remoteVolumeSyncEnabled && loaded.app.receiverSources == listOf(sourceMetadata))
            check(!loaded.app.muteRender)
        } finally {
            ConfigPaths.overrideConfigFile = previous
            check(directory.deleteRecursively())
        }
        println("Desktop remembered independent gain, 0–100 scale, migration and finite configuration checks passed")
    }
}
