package com.cuscus.wifiaudiostreaming

internal data class ManualEndpoint(val host: String, val port: Int, val explicitPort: Boolean) {
    companion object {
        fun parse(raw: String, defaultPort: Int): ManualEndpoint {
            val value = raw.trim()
            val host: String
            val portText: String?
            if (value.startsWith("[")) {
                val end = value.indexOf(']')
                require(end > 1)
                host = value.substring(1, end)
                val tail = value.substring(end + 1)
                require(tail.isEmpty() || tail.startsWith(":"))
                portText = if (tail.isEmpty()) null else tail.substring(1)
            } else if (value.count { it == ':' } == 1) {
                host = value.substringBefore(':'); portText = value.substringAfter(':')
            } else { host = value; portText = null }
            require(NetAddr.isLiteralAddress(host))
            val numeric = host.substringBefore('%')
            require(numeric.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' || it == '.' || it == ':' })
            if (numeric.contains(':')) {
                try { java.net.InetAddress.getByName(numeric) }
                catch (failure: java.net.UnknownHostException) { throw IllegalArgumentException("Invalid IPv6 literal", failure) }
            }
            val port = if (portText == null) defaultPort else portText.toIntOrNull() ?: throw IllegalArgumentException("Invalid port")
            require(port in 1..65535)
            return ManualEndpoint(host, port, portText != null)
        }
    }
}
