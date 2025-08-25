package com.gaebang.backend.domain.question.feedback.exception;

import com.gaebang.backend.global.exception.ApplicationException;
import com.gaebang.backend.global.exception.ErrorCode;

public class InvalidFeedbackCategoryException extends ApplicationException {
    public InvalidFeedbackCategoryException() {
        super(ErrorCode.BAD_REQUEST, "유효하지 않은 피드백 카테고리입니다.");
    }
}