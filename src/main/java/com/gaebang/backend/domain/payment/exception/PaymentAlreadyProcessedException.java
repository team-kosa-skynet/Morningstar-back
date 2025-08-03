package com.gaebang.backend.domain.payment.exception;

import com.gaebang.backend.global.exception.ApplicationException;
import com.gaebang.backend.global.exception.ErrorCode;

public class PaymentAlreadyProcessedException extends ApplicationException {
    public PaymentAlreadyProcessedException() {
        super(ErrorCode.PAYMENT_ALREADY_PROCESSED);
    }
}