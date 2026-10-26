internal object WindowGeometryChecks {
    fun runChecks() {
        // Size-only and size+position round-trip.
        check(WindowGeometryCodec.parse("600x800") == WindowGeometry(600, 800))
        check(WindowGeometryCodec.parse("1200x900+100+50") == WindowGeometry(1200, 900, 100, 50))

        // A window can legitimately sit on a screen left of (or above) the primary one.
        check(WindowGeometryCodec.parse("1200x900-100-50") == WindowGeometry(1200, 900, -100, -50))
        check(WindowGeometryCodec.format(WindowGeometry(1200, 900, -100, -50)) == "1200x900-100-50")
        check(WindowGeometryCodec.format(WindowGeometry(900, 700, 0, 0)) == "900x700+0+0")

        // Half a position, unknown shapes and empty values fall back to the default size.
        check(WindowGeometryCodec.parse("1200x900+100") == null)
        check(WindowGeometryCodec.parse("1200x900+100-") == null)
        check(WindowGeometryCodec.parse("window") == null)
        check(WindowGeometryCodec.parse("") == null)
        check(WindowGeometryCodec.parse(null) == null)

        // Values below the enforced minimum (400x500) or above the ceiling are clamped,
        // so a corrupt string can never shrink the window below its own minimumSize.
        check(WindowGeometryCodec.parse("1x1") == WindowGeometry(400, 500))
        check(WindowGeometryCodec.parse("99999x99999") == WindowGeometry(8_000, 8_000))

        // Round-trip through format keeps a clamped geometry stable.
        val clamped = WindowGeometryCodec.parse("1x1")!!
        check(WindowGeometryCodec.parse(WindowGeometryCodec.format(clamped)) == clamped)

        println("window geometry: parse, half-position rejection, clamping and round-trip passed")
    }
}
