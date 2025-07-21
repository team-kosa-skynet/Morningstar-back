package com.gaebang.backend.domain.member.exception;


import com.gaebang.backend.global.exception.ApplicationException;
import com.gaebang.backend.global.exception.ErrorCode;

public class NicknameAlreadyExistsException extends ApplicationException {

  private static final ErrorCode ERROR_CODE = ErrorCode.NICKNAME_ALREADY_EXISTS;

  public NicknameAlreadyExistsException(){super(ERROR_CODE);}
}
