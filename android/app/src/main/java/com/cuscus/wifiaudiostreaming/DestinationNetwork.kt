package com.cuscus.wifiaudiostreaming

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.RouteInfo
import android.os.Build
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Inet6Address
import java.net.NetworkInterface

/** Route only this source's socket; do not change the process default network. */
internal object DestinationNetwork {
    internal data class Candidate<T>(val path: T, val prefix: Int, val direct: Boolean,
                                      val vpn: Boolean, val managed: Boolean)

    internal fun <T> preferred(candidates: List<Candidate<T>>): T? = candidates
        .filter { it.prefix > 0 }
        .maxWithOrNull(compareBy<Candidate<T>> { !it.vpn }
            .thenBy { it.prefix }.thenBy { it.direct }.thenBy { it.managed })?.path
    data class Path(val network: Network?, val localAddress: InetAddress?, val name: String) {
        val fingerprint: String get() = "${network?.networkHandle ?: 0}:${localAddress?.hostAddress ?: "default"}:$name"
        fun bind(socket: DatagramSocket) { network?.bindSocket(socket) }
    }

    fun select(context: Context, address: InetAddress, requested: String): Path? {
        if (address.isLoopbackAddress) return Path(null, null, "loopback")
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val explicit = requested.isNotBlank() && !requested.equals("Auto", true)
        val scoped = (address as? Inet6Address)?.let {
            it.scopedInterface?.name ?: if (it.scopeId > 0) NetworkInterface.getByIndex(it.scopeId)?.name else null
        }
        val requiredInterface = if (explicit) requested else scoped
        val vpnInterfaces = mutableSetOf<String>()
        val candidates = cm.allNetworks.mapNotNull { network ->
            val properties = cm.getLinkProperties(network) ?: return@mapNotNull null
            val caps = cm.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (requiredInterface != null && properties.interfaceName != requiredInterface) return@mapNotNull null
            val vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            if (vpn) properties.interfaceName?.let(vpnInterfaces::add)
            val directPrefix = properties.linkAddresses.filter {
                sameSubnet(address, it.address, it.prefixLength)
            }.maxOfOrNull { it.prefixLength } ?: -1
            val routePrefix = properties.routes.filter {
                (Build.VERSION.SDK_INT < 33 || it.type == RouteInfo.RTN_UNICAST) && it.matches(address)
            }.maxOfOrNull { it.destination.prefixLength } ?: -1
            Candidate(Path(network, null, properties.interfaceName.orEmpty()),
                maxOf(directPrefix, routePrefix), directPrefix > 0, vpn, true)
        }.toMutableList()
        // Sharing/USB/P2P interfaces need not appear in ConnectivityManager.
        // Compare their direct paths before selecting a broader managed route.
        candidates += localInterfaces(address, requiredInterface, vpnInterfaces)
        preferred(candidates)?.let { return it }
        if (requiredInterface != null) return candidates.firstOrNull { it.managed }?.path
        if (address.isSiteLocalAddress || address.isLinkLocalAddress) return null
        val active = cm.activeNetwork ?: return null
        return Path(active, null, "default")
    }

    private fun localInterfaces(peer: InetAddress, requested: String?, vpnInterfaces: Set<String>): List<Candidate<Path>> {
        val candidates = mutableListOf<Candidate<Path>>()
        val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        for (iface in interfaces) {
            if (!iface.isUp || iface.isLoopback || (requested != null && iface.name != requested)) continue
            for (link in iface.interfaceAddresses) {
                val prefix = link.networkPrefixLength.toInt()
                if (prefix > 0 && sameSubnet(peer, link.address, prefix))
                    candidates += Candidate(Path(null, link.address, iface.name), prefix, true,
                        iface.name in vpnInterfaces, false)
            }
        }
        return candidates
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
