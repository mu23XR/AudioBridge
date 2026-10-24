import java.io.IOException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketException

internal object DesktopTransientErrorChecks {
    fun runChecks() {
        // Failures that heal when the link returns must keep the session alive.
        check(TransientSendError.isTransient(PortUnreachableException("ICMP port unreachable")))
        check(TransientSendError.isTransient(NoRouteToHostException("no route")))
        check(TransientSendError.isTransient(SocketException("Network is unreachable")))
        check(TransientSendError.isTransient(SocketException("ENETUNREACH")))
        check(TransientSendError.isTransient(SocketException("eHostUnreach")))
        check(TransientSendError.isTransient(SocketException("network is down")))
        check(TransientSendError.isTransient(SocketException("Cannot assign requested address")))

        // The cause chain matters: the JVM wraps the interesting exception in IOException.
        check(TransientSendError.isTransient(IOException("send failed", PortUnreachableException("x"))))
        check(TransientSendError.isTransient(IOException("outer", IOException("inner", SocketException("ENETDOWN")))))

        // Real protocol errors must stay fatal instead of looping forever.
        check(!TransientSendError.isTransient(SocketException("Connection reset")))
        check(!TransientSendError.isTransient(IllegalStateException("engine stopped")))
        check(!TransientSendError.isTransient(IOException("broken pipe")))

        // The walk is bounded: a chain deeper than five links is not inspected.
        var deep: Throwable = SocketException("ENETUNREACH")
        repeat(6) { deep = IOException("layer $it", deep) }
        check(!TransientSendError.isTransient(deep)) { "Cause walk must stay bounded at five levels" }

        println("test21 transient send errors: linux/windows errno classification, nested causes and bounded walk passed")
    }
}
