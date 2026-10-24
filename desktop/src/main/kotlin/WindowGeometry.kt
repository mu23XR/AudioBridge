/**
 * Window geometry persisted as "WxH" or "WxH+X+Y".
 *
 * Only the size and an explicit position are kept. A maximized window must not
 * overwrite the size the user picked before maximizing, so callers skip the
 * snapshot while the placement is not floating.
 */
internal data class WindowGeometry(
    val width: Int,
    val height: Int,
    val x: Int? = null,
    val y: Int? = null
) {
    fun clamped(): WindowGeometry = copy(
        width = width.coerceIn(MIN_WIDTH, MAX_WIDTH),
        height = height.coerceIn(MIN_HEIGHT, MAX_HEIGHT),
        x = x?.coerceIn(-MAX_OFFSET, MAX_OFFSET),
        y = y?.coerceIn(-MAX_OFFSET, MAX_OFFSET)
    )

    companion object {
        // Same floor as window.minimumSize, so a stored value can never fight it.
        const val MIN_WIDTH = 400
        const val MIN_HEIGHT = 500
        const val MAX_WIDTH = 8_000
        const val MAX_HEIGHT = 8_000
        // Negative offsets are valid: a window can sit on a screen left of the primary one.
        const val MAX_OFFSET = 32_000
    }
}

internal object WindowGeometryCodec {
    private val PATTERN = Regex("""^(\d{1,5})x(\d{1,5})(?:([+-]\d{1,5})([+-]\d{1,5}))?$""")

    fun parse(raw: String?): WindowGeometry? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val match = PATTERN.matchEntire(text) ?: return null
        val groups = match.groupValues
        val width = groups[1].toIntOrNull() ?: return null
        val height = groups[2].toIntOrNull() ?: return null
        val x = if (groups[3].isEmpty()) null else groups[3].toIntOrNull() ?: return null
        val y = if (groups[4].isEmpty()) null else groups[4].toIntOrNull() ?: return null
        // Half a position would place the window somewhere the user never chose.
        if ((x == null) != (y == null)) return null
        return WindowGeometry(width, height, x, y).clamped()
    }

    fun format(geometry: WindowGeometry): String {
        val g = geometry.clamped()
        return if (g.x == null || g.y == null) "${g.width}x${g.height}"
        else "${g.width}x${g.height}${signed(g.x)}${signed(g.y)}"
    }

    /**
     * A position is only restored when it still lands on an attached screen: unplugging
     * the second monitor would otherwise reopen the window where nothing can be seen.
     */
    fun isOnAnyScreen(x: Int, y: Int): Boolean = runCatching {
        java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
            .any { it.defaultConfiguration.bounds.contains(x, y) }
    }.getOrDefault(true)

    private fun signed(value: Int): String = if (value < 0) "$value" else "+$value"
}
