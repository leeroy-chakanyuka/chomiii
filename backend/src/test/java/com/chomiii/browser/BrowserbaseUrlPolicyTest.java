package com.chomiii.browser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import org.junit.jupiter.api.Test;

class BrowserbaseUrlPolicyTest {
    private final BrowserbaseUrlPolicy policy = new BrowserbaseUrlPolicy("example.com,www.example.com");

    @Test
    void acceptsOnlyExplicitHttpsHosts() {
        assertThat(policy.isAllowed(URI.create("https://example.com/articles?q=1"))).isTrue();
        assertThat(policy.isAllowed(URI.create("https://www.example.com"))).isTrue();
        assertThat(policy.isAllowed(URI.create("https://example.com.evil.net"))).isFalse();
        assertThat(policy.isAllowed(URI.create("http://example.com"))).isFalse();
    }

    @Test
    void blocksCredentialsPortsFragmentsAndPrivateNetworkTargets() {
        for (String url : new String[] {
                "https://me:secret@example.com/", "https://example.com:443/",
                "https://example.com/#fragment", "http://127.0.0.1/",
                "https://localhost/", "file:///etc/passwd", "https://192.168.0.1/"
        }) {
            assertThat(policy.isAllowed(URI.create(url))).as(url).isFalse();
        }
    }

    @Test
    void rejectsUntrustedOrOverlongInputs() {
        assertThatThrownBy(() -> policy.requireAllowed("https://other.example.com"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.requireAllowed("https://example.com/" + "x".repeat(2100)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.requireAllowed("not a url"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesWildcardAndNonpublicAllowlistEntries() {
        assertThatThrownBy(() -> new BrowserbaseUrlPolicy("*.example.com"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BrowserbaseUrlPolicy("localhost"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BrowserbaseUrlPolicy("169.254.169.254"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BrowserbaseUrlPolicy("demo.internal"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
