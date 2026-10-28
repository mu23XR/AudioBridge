import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.sp
import java.io.File

private val windowsChineseFontFamily by lazy {
    val fonts = System.getenv("WINDIR")?.let { File(it, "Fonts") }
    val regular = fonts?.let { File(it, "Deng.ttf") }
    val bold = fonts?.let { File(it, "Dengb.ttf") }
    if (regular?.isFile != true || bold?.isFile != true) {
        // The CJK font pack is optional on Windows; absence must not prevent launch.
        System.err.println("[UI] DengXian regular/bold fonts unavailable in $fonts; using system sans-serif")
        FontFamily.SansSerif
    } else {
        // Match the accepted control preview and avoid mixing regular/bold CJK faces.
        FontFamily(
            Font("windows-dengxian-regular", regular.readBytes(), FontWeight.Normal),
            Font("windows-dengxian-bold", bold.readBytes(), FontWeight.Bold)
        )
    }
}

/** Apply the same Chinese UI font to fields, menus, buttons and headings. */
@OptIn(ExperimentalTextApi::class)
fun desktopTypography(language: String): Typography {
    val defaults = Typography()
    if (language != "zh" || !System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        return defaults
    }
    val family = windowsChineseFontFamily
    return defaults.copy(
        displayLarge = defaults.displayLarge.copy(fontFamily = family, letterSpacing = 0.sp),
        displayMedium = defaults.displayMedium.copy(fontFamily = family, letterSpacing = 0.sp),
        displaySmall = defaults.displaySmall.copy(fontFamily = family, letterSpacing = 0.sp),
        headlineLarge = defaults.headlineLarge.copy(fontFamily = family, letterSpacing = 0.sp),
        headlineMedium = defaults.headlineMedium.copy(fontFamily = family, letterSpacing = 0.sp),
        headlineSmall = defaults.headlineSmall.copy(fontFamily = family, letterSpacing = 0.sp),
        titleLarge = defaults.titleLarge.copy(fontFamily = family, letterSpacing = 0.sp),
        titleMedium = defaults.titleMedium.copy(fontFamily = family, letterSpacing = 0.sp),
        titleSmall = defaults.titleSmall.copy(fontFamily = family, letterSpacing = 0.sp),
        bodyLarge = defaults.bodyLarge.copy(fontFamily = family, letterSpacing = 0.sp),
        bodyMedium = defaults.bodyMedium.copy(fontFamily = family, letterSpacing = 0.sp),
        bodySmall = defaults.bodySmall.copy(fontFamily = family, letterSpacing = 0.sp),
        labelLarge = defaults.labelLarge.copy(fontFamily = family, letterSpacing = 0.sp),
        labelMedium = defaults.labelMedium.copy(fontFamily = family, letterSpacing = 0.sp),
        labelSmall = defaults.labelSmall.copy(fontFamily = family, letterSpacing = 0.sp)
    )
}
