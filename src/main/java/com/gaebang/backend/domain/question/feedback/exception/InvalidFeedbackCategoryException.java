package com.gaebang.backend.domain.question.feedback.exception;

import com.gaebang.backend.global.exception.ApplicationException;
import com.gaebang.backend.global.exception.ErrorCode;

public class InvalidFeedbackCategoryException extends ApplicationException {
    
    private static final ErrorCode ERROR_CODE = ErrorCode.INVALID_FEEDBACK_CATEGORY;

    public InvalidFeedbackCategoryException() {
        super(ERROR_CODE);
    }
}