package com.chomiii.browser;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.WaitUntilState;
import java.net.URI;
import org.springframework.stereotype.Component;

/** Opens one allowlisted page in an isolated Browserbase cloud session. */
@Component
public class PlaywrightBrowserbasePageReader implements BrowserbasePageReader {

    @Override
    public PageText read(String connectUrl, String url, BrowserbaseUrlPolicy policy) {
        policy.requireAllowed(url);
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().connectOverCDP(connectUrl,
                    new com.microsoft.playwright.BrowserType.ConnectOverCDPOptions().setTimeout(15_000));
            try {
                if (browser.contexts().isEmpty()) {
                    throw new IllegalStateException("Browserbase session has no browser context");
                }
                BrowserContext context = browser.contexts().get(0);
                Page page = context.newPage();
                try {
                    page.route("**/*", route -> {
                        URI requested;
                        try {
                            requested = URI.create(route.request().url());
                        } catch (IllegalArgumentException malformed) {
                            route.abort();
                            return;
                        }
                        if (policy.isAllowed(requested)) route.resume();
                        else route.abort();
                    });
                    page.navigate(url, new Page.NavigateOptions()
                            .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                            .setTimeout(15_000));
                    String finalUrl = page.url();
                    policy.requireAllowed(finalUrl);
                    String title = page.title();
                    String content = page.locator("body").innerText();
                    return new PageText(finalUrl, title, content);
                } finally {
                    page.close();
                }
            } finally {
                browser.close();
            }
        } catch (PlaywrightException failure) {
            // CDP connection exceptions may contain secret-bearing session URLs.
            throw new IllegalStateException("Browserbase browser navigation failed");
        }
    }
}
