package com.gaebang.backend.domain.community.exception;


import com.gaebang.backend.global.exception.ApplicationException;
import com.gaebang.backend.global.exception.ErrorCode;

public class DuplicateReportException extends ApplicationException {
    private static final ErrorCode ERROR_CODE = ErrorCode.DUPLICATE_REPORT;

    public DuplicateReportException() {
        super(ERROR_CODE);
    }
}
