import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketException

/**
 * Classifies UDP send failures the same way the Android sender does.
 *
 * A Wi-Fi blip, an asleep access point or a route that disappears for a moment must not end the
 * session: the stream keeps running and resumes when the link comes back. Only failures that will
 * not heal on their own are fatal. The marker list mirrors the Android implementation because the
 * JVM exposes the same errno strings through different message wording per platform.
 */
internal object TransientSendError {
    private val UNREACHABLE_MARKERS = arrayOf(
        "enetunreach", "network is unreachable",
        "enonet", "ehostunreach", "no route to host",
        "enetdown", "network is down",
        "eaddrnotavail", "cannot assign requested address"
    )

    fun isTransient(error: Throwable): Boolean {
        var cause: Throwable? = error
        var depth = 0
        // Same bounded walk: wrap layers (IOException -> SocketException -> cause) must not make
        // the classifier loop on a self-referential or unusually deep chain.
        while (cause != null && depth < 5) {
            when (cause) {
                is PortUnreachableException -> return true
                is NoRouteToHostException -> return true
                is SocketException -> {
                    val message = cause.message?.lowercase().orEmpty()
                    if (UNREACHABLE_MARKERS.any { message.contains(it) }) return true
                }
            }
            cause = cause.cause
            depth++
        }
        return false
    }
}
