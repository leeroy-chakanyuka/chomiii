package com.chomiii.chat;

import org.springframework.http.HttpStatus;

public final class ChatApiException extends RuntimeException {
    private final HttpStatus status;

    public ChatApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
