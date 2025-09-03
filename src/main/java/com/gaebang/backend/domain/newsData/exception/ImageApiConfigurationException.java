package com.gaebang.backend.domain.newsData.exception;

import com.gaebang.backend.global.exception.ApplicationException;
import com.gaebang.backend.global.exception.ErrorCode;

public class ImageApiConfigurationException extends ApplicationException {

    public ImageApiConfigurationException() {
        super(ErrorCode.IMAGE_API_CONFIGURATION_ERROR);
    }

    public ImageApiConfigurationException(String message) {
        super(ErrorCode.IMAGE_API_CONFIGURATION_ERROR, message);
    }
}