package com.chomiii.browser;

/** Reads a single permitted page without providing click, form, or login tools. */
public interface BrowserbasePageReader {
    record PageText(String url, String title, String text) {}

    PageText read(String connectUrl, String url, BrowserbaseUrlPolicy policy);
}
