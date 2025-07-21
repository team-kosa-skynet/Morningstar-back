package com.gaebang.backend.domain.member.exception;


import com.gaebang.backend.global.exception.ApplicationException;
import com.gaebang.backend.global.exception.ErrorCode;

public class NewPasswordSameAsOldException extends ApplicationException {

    private static final ErrorCode ERROR_CODE = ErrorCode.NEW_PASSWORD_SAME_AS_OLD;

    public NewPasswordSameAsOldException() {
        super(ERROR_CODE);
    }
}
