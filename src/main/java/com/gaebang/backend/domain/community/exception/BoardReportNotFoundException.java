package com.gaebang.backend.domain.community.exception;


import com.gaebang.backend.global.exception.ApplicationException;
import com.gaebang.backend.global.exception.ErrorCode;

public class BoardReportNotFoundException extends ApplicationException {
    private static final ErrorCode ERROR_CODE = ErrorCode.BOARD_REPORT_NOT_FOUND;

    public BoardReportNotFoundException() {
        super(ERROR_CODE);
    }
}
