package com.gaebang.backend.domain.email.exception;


import com.gaebang.backend.global.exception.ApplicationException;
import com.gaebang.backend.global.exception.ErrorCode;

public class InvalidEmailCodeException extends ApplicationException {

    private static final ErrorCode ERROR_CODE = ErrorCode.EMAIL_VERIFY_FAILURE;

    public InvalidEmailCodeException() {

        super(ERROR_CODE);
    }
}
