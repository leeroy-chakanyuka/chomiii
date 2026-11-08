package com.chomiii.browser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

class BrowserbaseReadServiceTest {
    private final BrowserbaseSessions sessions = mock(BrowserbaseSessions.class);
    private final BrowserbasePageReader pages = mock(BrowserbasePageReader.class);

    private BrowserbaseReadService service(boolean enabled) {
        return new BrowserbaseReadService(sessions, pages, enabled, "example.com,www.example.com");
    }

    @Test
    void disabledConnectorDoesNotCreateBillableSession() {
        assertThatThrownBy(() -> service(false).read("https://example.com"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("disabled");
        verifyNoInteractions(sessions, pages);
    }

    @Test
    void rejectsUnlistedHostBeforeSessionCreation() {
        assertThatThrownBy(() -> service(true).read("https://unlisted.example.com"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not allowed");
        verifyNoInteractions(sessions, pages);
    }

    @Test
    void rejectsInternalAndNonHttpsAddressesBeforeSessionCreation() {
        var service = service(true);
        for (String url : new String[] {
                "http://example.com", "https://127.0.0.1",
                "https://example.com:8443", "https://user:pass@example.com",
                "https://example.com.evil.net", "file:///etc/passwd"
        }) {
            assertThatThrownBy(() -> service.read(url))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(sessions, pages);
    }

    @Test
    void readsAllowlistedPageAndReleasesCloudSession() {
        when(sessions.create()).thenReturn(new BrowserbaseSessions.Session(
                "example-session-id", "wss://connect.browserbase.com/example"));
        when(pages.read(eq("wss://connect.browserbase.com/example"), eq("https://example.com/docs"), any()))
                .thenReturn(new BrowserbasePageReader.PageText(
                        "https://example.com/docs", "Documentation", "Readable content"));

        var result = service(true).read("https://example.com/docs");

        assertThat(result.title()).isEqualTo("Documentation");
        assertThat(result.text()).isEqualTo("Readable content");
        verify(sessions).release("example-session-id");
    }

    @Test
    void truncatesLargePageText() {
        when(sessions.create()).thenReturn(new BrowserbaseSessions.Session("example-session-id", "wss://connect.browserbase.com"));
        when(pages.read(any(), any(), any())).thenReturn(
                new BrowserbasePageReader.PageText("https://example.com", "Large", "x".repeat(30_000)));

        var result = service(true).read("https://example.com");

        assertThat(result.text()).hasSize(12_000);
        verify(sessions).release("example-session-id");
    }

    @Test
    void alwaysReleasesSessionWhenNavigationFails() {
        when(sessions.create()).thenReturn(new BrowserbaseSessions.Session("example-session-id", "wss://connect.browserbase.com"));
        when(pages.read(any(), any(), any())).thenThrow(new IllegalStateException("navigation failed"));

        assertThatThrownBy(() -> service(true).read("https://example.com"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("navigation failed");
        verify(sessions).release("example-session-id");
    }

    @Test
    void rejectsResultThatWasRedirectedToAnUnapprovedHost() {
        when(sessions.create()).thenReturn(new BrowserbaseSessions.Session("example-session-id", "wss://connect.browserbase.com"));
        when(pages.read(any(), any(), any())).thenReturn(
                new BrowserbasePageReader.PageText("https://other.net/private", "No", "text"));

        assertThatThrownBy(() -> service(true).read("https://example.com"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(sessions).release("example-session-id");
    }

    @Test
    void emptyHostAllowlistFailsClosed() {
        BrowserbaseReadService withNoHosts = new BrowserbaseReadService(sessions, pages, true, "");
        assertThatThrownBy(() -> withNoHosts.read("https://example.com"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(sessions, pages);
    }
}
