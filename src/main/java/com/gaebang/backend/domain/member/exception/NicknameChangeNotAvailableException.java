package com.gaebang.backend.domain.member.exception;


import com.gaebang.backend.global.exception.ApplicationException;
import com.gaebang.backend.global.exception.ErrorCode;

public class NicknameChangeNotAvailableException extends ApplicationException {

  private static ErrorCode ERROR_CODE = ErrorCode.NICKNAME_CHANGE_NOT_AVAILABLE;

  public NicknameChangeNotAvailableException(){
    super(ERROR_CODE);
  }

}
