package com.gaebang.backend.domain.member.exception;


import com.gaebang.backend.global.exception.ApplicationException;
import com.gaebang.backend.global.exception.ErrorCode;

public class PointTierIsNotExistException extends ApplicationException {

    private static final ErrorCode ERROR_CODE = ErrorCode.POINT_TIER_IS_NOT_EXIST;

    public PointTierIsNotExistException() {
        super(ERROR_CODE);
    }
}
