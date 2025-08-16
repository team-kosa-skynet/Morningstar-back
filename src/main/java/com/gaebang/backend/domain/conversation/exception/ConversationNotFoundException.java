package com.gaebang.backend.domain.conversation.exception;

import com.gaebang.backend.global.exception.ApplicationException;
import com.gaebang.backend.global.exception.ErrorCode;

public class ConversationNotFoundException extends ApplicationException {
    public ConversationNotFoundException() {
        super(ErrorCode.CONVERSATION_NOT_FOUND);
    }

    public ConversationNotFoundException(String message) {
        super(ErrorCode.CONVERSATION_NOT_FOUND, message);
    }
}