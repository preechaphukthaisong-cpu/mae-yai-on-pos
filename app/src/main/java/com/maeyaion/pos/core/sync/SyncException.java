package com.maeyaion.pos.core.sync;

/** Safe-to-display sync transport failure; never contains credentials. */
public final class SyncException extends Exception {
    private final int httpStatus;

    public SyncException(String message) { this(message, 0, null); }
    public SyncException(String message, Throwable cause) { this(message, 0, cause); }
    public SyncException(String message, int httpStatus) { this(message, httpStatus, null); }

    private SyncException(String message, int httpStatus, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
    }

    public int getHttpStatus() { return httpStatus; }
}
