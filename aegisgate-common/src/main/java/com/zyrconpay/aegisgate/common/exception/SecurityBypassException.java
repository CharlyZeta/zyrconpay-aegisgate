package com.zyrconpay.aegisgate.common.exception;

public class SecurityBypassException extends AegisGateException {

    public SecurityBypassException(String message) {
        super(403, "SECURITY_BYPASS_EXPLOIT", message);
    }
}
