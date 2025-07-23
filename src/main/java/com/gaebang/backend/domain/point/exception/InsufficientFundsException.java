package com.gaebang.backend.domain.point.exception;


import com.gaebang.backend.global.exception.ApplicationException;
import com.gaebang.backend.global.exception.ErrorCode;

public class InsufficientFundsException extends ApplicationException {

    private static final ErrorCode ERROR_CODE = ErrorCode.INSUFFICIENT_POINT;

    public InsufficientFundsException() {
        super(ERROR_CODE);
    }
}
