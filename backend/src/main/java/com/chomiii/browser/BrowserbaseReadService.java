package com.chomiii.browser;

import java.net.URI;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Internal-only, explicit read operation. Not registered as an agent tool or
 * exposed as an HTTP endpoint until M3 permissions/approvals are in place.
 */
@Service
public class BrowserbaseReadService {
    private static final int MAX_TEXT_CHARS = 12_000;
    private final BrowserbaseSessions sessions;
    private final BrowserbasePageReader pages;
    private final BrowserbaseUrlPolicy policy;
    private final boolean enabled;
    private final Semaphore sessionSlot = new Semaphore(1);

    public BrowserbaseReadService(BrowserbaseSessions sessions, BrowserbasePageReader pages,
            @Value("${chomiii.browserbase.enabled:false}") boolean enabled,
            @Value("${chomiii.browserbase.allowed-hosts:}") String allowedHosts) {
        this.sessions = sessions;
        this.pages = pages;
        this.enabled = enabled;
        this.policy = new BrowserbaseUrlPolicy(allowedHosts);
    }

    public BrowserbasePageReader.PageText read(String url) {
        if (!enabled) throw new IllegalStateException("Browserbase is disabled");
        URI target = policy.requireAllowed(url);
        if (!sessionSlot.tryAcquire()) throw new IllegalStateException("Browserbase reader is busy");
        try {
            BrowserbaseSessions.Session session = sessions.create();
            try {
                BrowserbasePageReader.PageText result = pages.read(session.connectUrl(), target.toString(), policy);
                // Check the final URL as well as each routed request (redirect safety).
                policy.requireAllowed(result.url());
                String body = result.text() == null ? "" : result.text();
                if (body.length() > MAX_TEXT_CHARS) body = body.substring(0, MAX_TEXT_CHARS);
                return new BrowserbasePageReader.PageText(result.url(), result.title(), body);
            } finally {
                sessions.release(session.id());
            }
        } finally {
            sessionSlot.release();
        }
    }
}
