package com.contractnotemanager.sharpfin;

public class SharpfinException extends RuntimeException {
    private final int httpStatus;

    public SharpfinException(String message, int httpStatus) {
        super(message);
        this.httpStatus = httpStatus;
    }

    public SharpfinException(String message, Throwable cause) {
        super(message, cause);
        this.httpStatus = 0;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
