package com.gaebang.backend.domain.point.exception;


import com.gaebang.backend.global.exception.ApplicationException;
import com.gaebang.backend.global.exception.ErrorCode;

public class PointCreationRetryExhaustedException extends ApplicationException {

    private static final ErrorCode ERROR_CODE = ErrorCode.POINT_CREATION_RETRY_EXHAUSTED_EXCEPTION;

    public PointCreationRetryExhaustedException() {
        super(ERROR_CODE);
    }
}
