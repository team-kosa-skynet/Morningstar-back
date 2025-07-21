package com.gaebang.backend.global.exception;


public class S3Exception extends ApplicationException {

    private static final ErrorCode ERROR_CODE = ErrorCode.S3_ERROR;

    public S3Exception() {
        super(ERROR_CODE);
    }
}
