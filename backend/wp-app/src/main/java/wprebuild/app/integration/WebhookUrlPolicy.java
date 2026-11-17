package wprebuild.app.integration;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * SSRF guard for webhook callback URLs (BS-15). Accepts absolute {@code http}/{@code https} URLs
 * without userinfo; rejects loopback, private, CGNAT, link-local, site-local, NAT64, multicast, and
 * unspecified addresses unless {@code wp.webhooks.allow-private-targets=true}.
 */
@Component
public class WebhookUrlPolicy {

    public enum Result {
        ALLOWED,
        INVALID,
        UNRESOLVABLE,
        NOT_ALLOWED
    }

    private final HostResolver hostResolver;
    private final WebhookProperties properties;

    public WebhookUrlPolicy(HostResolver hostResolver, WebhookProperties properties) {
        this.hostResolver = Objects.requireNonNull(hostResolver, "host resolver");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    public Result check(String url) {
        Objects.requireNonNull(url, "url");
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException ex) {
            return Result.INVALID;
        }
        if (!uri.isAbsolute()) {
            return Result.INVALID;
        }
        String scheme = uri.getScheme();
        if (scheme == null) {
            return Result.INVALID;
        }
        String normalized = scheme.toLowerCase(Locale.ROOT);
        if (!normalized.equals("http") && !normalized.equals("https")) {
            return Result.INVALID;
        }
        if (uri.getUserInfo() != null) {
            return Result.INVALID;
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return Result.INVALID;
        }

        InetAddress[] addresses;
        try {
            addresses = resolve(host);
        } catch (UnknownHostException ex) {
            return Result.UNRESOLVABLE;
        }
        if (addresses.length == 0) {
            return Result.UNRESOLVABLE;
        }
        if (!properties.allowPrivateTargets()) {
            for (InetAddress address : addresses) {
                if (isForbiddenTarget(effectiveAddress(address))) {
                    return Result.NOT_ALLOWED;
                }
            }
        }
        return Result.ALLOWED;
    }

    private InetAddress[] resolve(String host) throws UnknownHostException {
        try {
            return new InetAddress[] {InetAddress.ofLiteral(host)};
        } catch (IllegalArgumentException ex) {
            return hostResolver.resolve(host);
        }
    }

    static boolean isForbiddenTarget(InetAddress address) {
        Objects.requireNonNull(address, "address");
        byte[] bytes = address.getAddress();
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isMulticastAddress()
                || address.isSiteLocalAddress()) {
            return true;
        }
        if (bytes.length == 4) {
            int b0 = bytes[0] & 0xff;
            int b1 = bytes[1] & 0xff;
            // 0.0.0.0/8 (beyond the single 0.0.0.0 covered by isAnyLocalAddress)
            if (b0 == 0) {
                return true;
            }
            // Carrier-grade NAT 100.64.0.0/10
            if (b0 == 100 && (b1 & 0xc0) == 0x40) {
                return true;
            }
            // Benchmarking 198.18.0.0/15
            if (b0 == 198 && (b1 & 0xfe) == 18) {
                return true;
            }
            return false;
        }
        if (bytes.length == 16) {
            // Unique local fc00::/7
            if ((bytes[0] & 0xfe) == 0xfc) {
                return true;
            }
            // NAT64 well-known prefix 64:ff9b::/96
            if (bytes[0] == 0x00
                    && bytes[1] == 0x64
                    && (bytes[2] & 0xff) == 0xff
                    && (bytes[3] & 0xff) == 0x9b
                    && bytes[4] == 0
                    && bytes[5] == 0
                    && bytes[6] == 0
                    && bytes[7] == 0
                    && bytes[8] == 0
                    && bytes[9] == 0
                    && bytes[10] == 0
                    && bytes[11] == 0) {
                return true;
            }
        }
        return false;
    }

    /** IPv4-mapped IPv6 addresses are judged by their embedded IPv4 address. */
    static InetAddress effectiveAddress(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (bytes.length == 16 && isIpv4Mapped(bytes)) {
            byte[] v4 = new byte[] {bytes[12], bytes[13], bytes[14], bytes[15]};
            try {
                return InetAddress.getByAddress(v4);
            } catch (UnknownHostException ex) {
                throw new IllegalStateException("invalid embedded IPv4 address", ex);
            }
        }
        return address;
    }

    private static boolean isIpv4Mapped(byte[] bytes) {
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return (bytes[10] & 0xff) == 0xff && (bytes[11] & 0xff) == 0xff;
    }
}
