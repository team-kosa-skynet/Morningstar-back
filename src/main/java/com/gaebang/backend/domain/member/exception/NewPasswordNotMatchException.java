package com.gaebang.backend.domain.member.exception;


import com.gaebang.backend.global.exception.ApplicationException;
import com.gaebang.backend.global.exception.ErrorCode;

public class NewPasswordNotMatchException extends ApplicationException {
  private static final ErrorCode ERROR_CODE = ErrorCode.NEW_PASSWORD_NOT_MATCH;

  public NewPasswordNotMatchException(){super(ERROR_CODE);}

}
