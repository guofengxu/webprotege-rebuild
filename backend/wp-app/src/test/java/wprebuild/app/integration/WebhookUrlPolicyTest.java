package wprebuild.app.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WebhookUrlPolicyTest {

    private final HostResolver refusingResolver = host -> {
        throw new UnknownHostException(host);
    };

    @Test
    void rejects_non_http_schemes() {
        assertThat(policy(false).check("ftp://203.0.113.10/hook")).isEqualTo(WebhookUrlPolicy.Result.INVALID);
    }

    @Test
    void rejects_userinfo() {
        assertThat(policy(false).check("https://user:pass@203.0.113.10/hook"))
                .isEqualTo(WebhookUrlPolicy.Result.INVALID);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "http://127.0.0.1/hook",
                "http://[::1]/hook",
                "http://10.0.0.1/hook",
                "http://172.16.5.1/hook",
                "http://192.168.1.1/hook",
                "http://[fc00::1]/hook",
                "http://100.64.0.1/hook",
                "http://0.0.0.1/hook",
                "http://198.18.0.1/hook",
                "http://169.254.1.1/hook",
                "http://[fe80::1]/hook",
                "http://[fec0::1]/hook",
                "http://[64:ff9b::1]/hook",
                "http://224.0.0.1/hook",
                "http://0.0.0.0/hook",
                "http://[::ffff:127.0.0.1]/hook"
            })
    void rejects_forbidden_literal_targets(String url) {
        assertThat(policy(false).check(url)).isEqualTo(WebhookUrlPolicy.Result.NOT_ALLOWED);
    }

    @Test
    void allows_public_literal() {
        assertThat(policy(false).check("https://203.0.113.10/hooks/project"))
                .isEqualTo(WebhookUrlPolicy.Result.ALLOWED);
    }

    @Test
    void unresolvable_hostname() {
        assertThat(policy(false, refusingResolver).check("https://no-such-host.example/hook"))
                .isEqualTo(WebhookUrlPolicy.Result.UNRESOLVABLE);
    }

    @Test
    void allows_private_when_switch_enabled() {
        assertThat(policy(true).check("http://10.0.0.1/hook")).isEqualTo(WebhookUrlPolicy.Result.ALLOWED);
        assertThat(policy(true).check("http://127.0.0.1/hook")).isEqualTo(WebhookUrlPolicy.Result.ALLOWED);
    }

    @Test
    void hostname_resolved_to_private_is_rejected() throws Exception {
        InetAddress privateAddress = InetAddress.getByName("10.1.2.3");
        HostResolver resolver = host -> new InetAddress[] {privateAddress};
        assertThat(policy(false, resolver).check("https://hooks.internal.example/x"))
                .isEqualTo(WebhookUrlPolicy.Result.NOT_ALLOWED);
    }

    private static WebhookUrlPolicy policy(boolean allowPrivate) {
        return policy(allowPrivate, host -> new InetAddress[0]);
    }

    private static WebhookUrlPolicy policy(boolean allowPrivate, HostResolver resolver) {
        return new WebhookUrlPolicy(resolver, WebhookProperties.allowingPrivateTargets(allowPrivate));
    }
}
