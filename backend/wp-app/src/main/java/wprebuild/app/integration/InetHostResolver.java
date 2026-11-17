package wprebuild.app.integration;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Default {@link HostResolver} backed by {@link InetAddress#getAllByName(String)}. */
@Component
class InetHostResolver implements HostResolver {

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        Objects.requireNonNull(host, "host");
        return InetAddress.getAllByName(host);
    }
}
