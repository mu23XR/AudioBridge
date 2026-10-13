package com.cuscus.wifiaudiostreaming

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface

/** Route only this source's socket; do not change the process default network. */
internal object DestinationNetwork {
    data class Path(val network: Network?, val localAddress: InetAddress?, val name: String) {
        val fingerprint: String get() = "${network?.networkHandle ?: 0}:${localAddress?.hostAddress ?: "default"}:$name"
        fun bind(socket: DatagramSocket) { network?.bindSocket(socket) }
    }

    fun select(context: Context, address: InetAddress, requested: String): Path? {
        if (address.isLoopbackAddress) return Path(null, null, "loopback")
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val explicit = requested.isNotBlank() && !requested.equals("Auto", true)
        val candidates = cm.allNetworks.mapNotNull { network ->
            val properties = cm.getLinkProperties(network) ?: return@mapNotNull null
            val caps = cm.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (explicit && properties.interfaceName != requested) return@mapNotNull null
            val direct = properties.linkAddresses.any { sameSubnet(address, it.address, it.prefixLength) }
            val routed = properties.routes.any { it.matches(address) && it.destination.prefixLength > 0 }
            val vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            Triple(Path(network, null, properties.interfaceName.orEmpty()),
                if (direct && !vpn) 4 else if (routed && !vpn) 3 else if (direct || routed) 2 else 0, vpn)
        }
        if (explicit) return candidates.firstOrNull()?.first ?: localInterface(address, requested)
        candidates.maxByOrNull { it.second }?.takeIf { it.second > 0 }?.let { return it.first }
        localInterface(address, null)?.let { return it }
        if (address.isSiteLocalAddress || address.isLinkLocalAddress) return null
        val active = cm.activeNetwork ?: return null
        return Path(active, null, "default")
    }

    private fun localInterface(peer: InetAddress, requested: String?): Path? {
        val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        for (iface in interfaces) {
            if (!iface.isUp || iface.isLoopback || (requested != null && iface.name != requested)) continue
            for (link in iface.interfaceAddresses) {
                if (sameSubnet(peer, link.address, link.networkPrefixLength.toInt()))
                    return Path(null, link.address, iface.name)
            }
        }
        return null
    }

    internal fun sameSubnet(a: InetAddress, b: InetAddress, prefix: Int): Boolean {
        val left = a.address; val right = b.address
        if (left.size != right.size || prefix !in 0..left.size * 8) return false
        for (i in left.indices) {
            val bits = (prefix - i * 8).coerceIn(0, 8)
            val mask = (255 shl (8 - bits)) and 255
            if ((left[i].toInt() and mask) != (right[i].toInt() and mask)) return false
        }
        return true
    }
}
