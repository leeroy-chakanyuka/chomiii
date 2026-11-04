package com.chomiii.provider;

public final class ProviderFailure extends RuntimeException {
    public enum Code {
        INVALID_KEY, INVALID_REQUEST, UNSUPPORTED_MODEL, RATE_LIMITED, TIMEOUT, UNAVAILABLE, EMPTY_RESPONSE, CANCELLED
    }

    private final Code code;

    public ProviderFailure(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
