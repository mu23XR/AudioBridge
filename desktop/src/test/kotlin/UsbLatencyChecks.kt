internal object UsbLatencyChecks {
    fun runChecks() {
        // Without a USB link the configured target passes through untouched.
        check(UsbLatency.effective(80, 20, ready = false) == 80)

        // A ready USB link may shorten the target...
        check(UsbLatency.effective(80, 20, ready = true) == 20)

        // ...but never lengthen it.
        check(UsbLatency.effective(20, 60, ready = true) == 20)
        check(UsbLatency.effective(5, 120, ready = true) == 5)

        // The stored USB target stays inside the range the settings UI offers.
        check(UsbLink.MAX_USB_LATENCY_MS == 120)
        check(UsbLink.MIN_USB_LATENCY_MS <= UsbLink.DEFAULT_USB_LATENCY_MS)
        check(UsbLink.DEFAULT_USB_LATENCY_MS <= UsbLink.MAX_USB_LATENCY_MS)

        println("usb latency policy: pass-through, shortening-only and 5..120 bounds passed")
    }
}
