package com.gaebang.backend.domain.member.exception;


import com.gaebang.backend.global.exception.ApplicationException;
import com.gaebang.backend.global.exception.ErrorCode;

public class CurrentPasswordNotMatchException extends ApplicationException {
    private static final ErrorCode ERROR_CODE = ErrorCode.CURRENT_PASSWORD_NOT_MATCH;

    public CurrentPasswordNotMatchException(){super(ERROR_CODE);}
}
