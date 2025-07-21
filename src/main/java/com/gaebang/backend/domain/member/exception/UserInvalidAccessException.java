package com.gaebang.backend.domain.member.exception;


import com.gaebang.backend.global.exception.ApplicationException;
import com.gaebang.backend.global.exception.ErrorCode;

public class UserInvalidAccessException extends ApplicationException {

    private static final ErrorCode ERROR_CODE = ErrorCode.USER_INVALID_ACCESS;

    public UserInvalidAccessException() {
        super(ERROR_CODE);
    }
}
