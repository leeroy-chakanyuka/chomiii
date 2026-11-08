package com.chomiii.browser;

/** Browserbase cloud-session lifecycle, kept separate from the page renderer. */
public interface BrowserbaseSessions {
    record Session(String id, String connectUrl) {}

    Session create();
    void release(String id);
}
