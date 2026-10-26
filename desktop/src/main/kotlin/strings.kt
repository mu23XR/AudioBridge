/*
 * Copyright (c) 2026 Marco Morosi
 *
 * Licensed under the EUPL, Version 1.2 or – as soon they will be approved by
 * the European Commission - subsequent versions of the EUPL (the "Licence");
 * You may not use this work except in compliance with the Licence.
 * You may obtain a copy of the Licence at:
 *
 * https://joinup.ec.europa.eu/software/page/eupl
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the Licence is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Licence for the specific language governing permissions and
 * limitations under the Licence.
 */

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import java.io.InputStreamReader
import java.util.Properties

object Strings {
    val languages = listOf("system", "zh", "en")
    private val selectedLanguage = mutableStateOf("system")
    val language: String get() = selectedLanguage.value.let { if (it == "system") java.util.Locale.getDefault().language else it }
    private val localized = mutableStateOf(load("system"))

    fun setLanguage(language: String) {
        require(language in languages) { "Unknown UI language: $language" }
        localized.value = load(language)
        selectedLanguage.value = language
    }

    private fun load(language: String): Properties {
        val effective = if (language == "system") java.util.Locale.getDefault().language else language
        val files = listOf("strings.properties") + if (effective == "zh") listOf("strings_zh.properties") else emptyList()
        return Properties().apply {
            for (file in files) {
                val stream = Strings::class.java.classLoader.getResourceAsStream(file)
                    ?: error("Missing language resource: $file")
                stream.use { load(InputStreamReader(it, Charsets.UTF_8)) }
            }
        }
    }

    fun get(key: String): String = localized.value.getProperty(key)
        ?: error("Missing UI string: $key")

    fun get(key: String, vararg args: Any): String {
        return String.format(get(key), *args)
    }

    val appVersion: String get() = BuildIdentity.version
}

/**
 * Transforms the internal build version (e.g. "5.0.0") into the
 * user-facing display version by subtracting 4 from the major component.
 * The build number in build.gradle.kts remains unchanged.
 */
fun displayVersion(raw: String): String {
    val parts = raw.split(".", limit = 3)
    val major = parts.getOrNull(0)?.toIntOrNull() ?: return raw
    val adjusted = (major - 4).coerceAtLeast(0)
    return buildString {
        append(adjusted)
        parts.drop(1).forEach { append('.').append(it) }
    }
}

@Composable
fun stringResource(key: String): String {
    return Strings.get(key)
}

@Composable
fun stringResource(key: String, vararg args: Any): String {
    return Strings.get(key, *args)
}
