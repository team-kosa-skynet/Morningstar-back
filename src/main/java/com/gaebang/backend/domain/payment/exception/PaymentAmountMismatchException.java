package com.gaebang.backend.domain.payment.exception;

import com.gaebang.backend.global.exception.ApplicationException;
import com.gaebang.backend.global.exception.ErrorCode;

public class PaymentAmountMismatchException extends ApplicationException {
    public PaymentAmountMismatchException(String message) {
        super(ErrorCode.PAYMENT_AMOUNT_MISMATCH, message);
    }
}