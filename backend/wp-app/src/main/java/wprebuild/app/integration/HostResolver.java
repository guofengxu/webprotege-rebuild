package wprebuild.app.integration;

import java.net.InetAddress;
import java.net.UnknownHostException;

/** Resolves a hostname to addresses. Injected so tests can stub DNS without touching the network. */
@FunctionalInterface
public interface HostResolver {

    InetAddress[] resolve(String host) throws UnknownHostException;
}
