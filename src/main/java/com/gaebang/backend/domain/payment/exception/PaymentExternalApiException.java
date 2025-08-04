package com.gaebang.backend.domain.payment.exception;

import com.gaebang.backend.global.exception.ApplicationException;
import com.gaebang.backend.global.exception.ErrorCode;

public class PaymentExternalApiException extends ApplicationException {
    public PaymentExternalApiException(String message) {
        super(ErrorCode.PAYMENT_EXTERNAL_API_ERROR, message);
    }
}