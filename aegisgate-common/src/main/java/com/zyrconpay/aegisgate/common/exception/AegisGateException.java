package com.zyrconpay.aegisgate.common.exception;

public class AegisGateException extends RuntimeException {
    
    private final int status;
    private final String errorCode;

    public AegisGateException(int status, String message) {
        this(status, "INTERNAL_ERROR", message);
    }

    public AegisGateException(int status, String errorCode, String message) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }

    public AegisGateException(int status, String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.errorCode = errorCode;
    }

    public int getStatus() {
        return status;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
