package com.gaebang.backend.domain.email.exception;


import com.gaebang.backend.global.exception.ApplicationException;
import com.gaebang.backend.global.exception.ErrorCode;

public class EmailTemplateLoadException extends ApplicationException {

    private static final ErrorCode ERROR_CODE = ErrorCode.EMAIL_TEMPLATE_LOAD_FAILURE;

    public EmailTemplateLoadException() {

        super(ERROR_CODE);
    }

}
