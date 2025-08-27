package com.gaebang.backend.domain.question.common.exception;

import com.gaebang.backend.global.exception.ApplicationException;
import com.gaebang.backend.global.exception.ErrorCode;

public class ModelNotFoundException extends ApplicationException {
    
    private static final ErrorCode ERROR_CODE = ErrorCode.MODEL_NOT_FOUND;

    public ModelNotFoundException() {
        super(ERROR_CODE);
    }
}