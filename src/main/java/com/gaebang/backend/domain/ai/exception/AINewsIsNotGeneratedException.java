package com.gaebang.backend.domain.ai.exception;


import com.gaebang.backend.global.exception.ApplicationException;
import com.gaebang.backend.global.exception.ErrorCode;

public class AINewsIsNotGeneratedException extends ApplicationException {
    private static final ErrorCode ERROR_CODE = ErrorCode.AI_NEWS_IS_NOT_GENERATED;

    public AINewsIsNotGeneratedException(){super(ERROR_CODE);}
}
