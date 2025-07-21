package com.gaebang.backend.domain.member.exception;


import com.gaebang.backend.global.exception.ApplicationException;
import com.gaebang.backend.global.exception.ErrorCode;

public class EmailNotMatchException extends ApplicationException {
  private static final ErrorCode ERROR_CODE = ErrorCode.EMAIL_NOT_MATCH;

  public EmailNotMatchException(){super(ERROR_CODE);}
}
