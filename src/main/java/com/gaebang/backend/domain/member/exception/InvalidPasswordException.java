package com.gaebang.backend.domain.member.exception;


import com.gaebang.backend.global.exception.ApplicationException;
import com.gaebang.backend.global.exception.ErrorCode;

public class InvalidPasswordException extends ApplicationException {
  private static final ErrorCode ERROR_CODE = ErrorCode.INVALID_PASSWORD;

  public InvalidPasswordException(){super(ERROR_CODE);}

}
