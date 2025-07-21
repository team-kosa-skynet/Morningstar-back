package com.gaebang.backend.domain.member.exception;


import com.gaebang.backend.global.exception.ApplicationException;
import com.gaebang.backend.global.exception.ErrorCode;

public class IntroductionLengthExceededException extends ApplicationException {
  private static final ErrorCode ERROR_CODE = ErrorCode.INTRODUCTION_TOO_LONG;

  public IntroductionLengthExceededException(){super(ERROR_CODE);}

}
