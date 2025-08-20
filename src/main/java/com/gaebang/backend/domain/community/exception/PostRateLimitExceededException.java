package com.gaebang.backend.domain.community.exception;

import com.gaebang.backend.global.exception.ApplicationException;
import com.gaebang.backend.global.exception.ErrorCode;

public class PostRateLimitExceededException extends ApplicationException {
    public PostRateLimitExceededException() {
        super(ErrorCode.POST_RATE_LIMIT_EXCEEDED);
    }
}