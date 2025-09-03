package com.gaebang.backend.domain.newsData.exception;

import com.gaebang.backend.global.exception.ApplicationException;
import com.gaebang.backend.global.exception.ErrorCode;

public class ImageApiException extends ApplicationException {

    public ImageApiException() {
        super(ErrorCode.IMAGE_API_ERROR);
    }

    public ImageApiException(String message) {
        super(ErrorCode.IMAGE_API_ERROR, message);
    }
}