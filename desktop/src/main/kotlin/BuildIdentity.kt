import java.io.InputStreamReader
import java.util.Properties

/** Package metadata is independent of mutable UI language and user settings. */
object BuildIdentity {
    private val metadata = Properties().apply {
        BuildIdentity::class.java.classLoader.getResourceAsStream("version.properties")
            ?.use { load(InputStreamReader(it, Charsets.UTF_8)) }
    }
    val version: String = metadata.getProperty("app.display.version")?.takeIf { it.isNotBlank() }
        ?: displayVersion(metadata.getProperty("app.version", "?"))
    val preview: Boolean = metadata.getProperty("app.channel")?.let { it == "preview" }
        ?: version.contains("-test.", ignoreCase = true)
    val configName: String = if (preview) "audiobridge-test" else "wfas"
    val preferencesNode: String = if (preview) "io/github/mu23xr/audiobridge/test" else "com/mavco/wifiaudiostreamer"
    val ipcPrefix: String = if (preview) "audiobridge-test" else "wfas"
    val title: String get() = if (preview) Strings.get("app_title_test") else Strings.get("app_title")
}
