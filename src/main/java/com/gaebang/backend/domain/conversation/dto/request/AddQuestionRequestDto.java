package com.gaebang.backend.domain.conversation.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public record AddQuestionRequestDto(
        @NotBlank(message = "질문 내용을 입력해주세요")
        @Size(max = 10000, message = "질문은 10000자 이하로 입력해주세요")
        String content,

        List<FileAttachmentDto> attachments
) {
}