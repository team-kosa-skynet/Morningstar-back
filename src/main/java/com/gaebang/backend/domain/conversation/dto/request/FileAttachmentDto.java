package com.gaebang.backend.domain.conversation.dto.request;

public record FileAttachmentDto(
        String fileName,
        String fileType,
        Long fileSize,
        String mimeType
) {
}
