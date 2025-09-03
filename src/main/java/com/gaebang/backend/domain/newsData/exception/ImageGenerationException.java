package com.gaebang.backend.domain.newsData.exception;

import com.gaebang.backend.global.exception.ApplicationException;
import com.gaebang.backend.global.exception.ErrorCode;

public class ImageGenerationException extends ApplicationException {

    public ImageGenerationException() {
        super(ErrorCode.IMAGE_GENERATION_FAILED);
    }

    public ImageGenerationException(String message) {
        super(ErrorCode.IMAGE_GENERATION_FAILED, message);
    }
}