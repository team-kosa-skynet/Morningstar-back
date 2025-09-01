package com.gaebang.backend.domain.attendance.exception;

import com.gaebang.backend.global.exception.ApplicationException;
import com.gaebang.backend.global.exception.ErrorCode;

public class AttendanceNotFoundException extends ApplicationException {
    private static final ErrorCode ERROR_CODE = ErrorCode.ATTENDANCE_NOT_FOUND;

    public AttendanceNotFoundException() {
        super(ERROR_CODE);
    }
}