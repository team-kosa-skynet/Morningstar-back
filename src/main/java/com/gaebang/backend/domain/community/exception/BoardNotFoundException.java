package com.gaebang.backend.domain.community.exception;


import com.gaebang.backend.global.exception.ApplicationException;
import com.gaebang.backend.global.exception.ErrorCode;

public class BoardNotFoundException extends ApplicationException {
    private static final ErrorCode ERROR_CODE = ErrorCode.BOARD_NOT_FOUND;

    public BoardNotFoundException() {
        super(ERROR_CODE);
    }
}
