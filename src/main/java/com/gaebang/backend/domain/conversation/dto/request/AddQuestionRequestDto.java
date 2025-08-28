package com.gaebang.backend.domain.conversation.dto.request;

import com.gaebang.backend.domain.conversation.entity.Conversation;
import com.gaebang.backend.domain.conversation.entity.ConversationMessage;
import com.gaebang.backend.domain.conversation.entity.MessageRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public record AddQuestionRequestDto(
        @NotBlank(message = "질문 내용을 입력해주세요")
        @Size(max = 10000, message = "질문은 10000자 이하로 입력해주세요")
        String content,

        List<FileAttachmentDto> attachments
) {
    public ConversationMessage toEntity(Conversation conversation, String contentWithFiles, Integer nextOrder, String attachmentsJson) {
        return ConversationMessage.builder()
                .conversation(conversation)
                .role(MessageRole.USER)
                .content(contentWithFiles)
                .messageOrder(nextOrder)
                .attachments(attachmentsJson)
                .build();
    }
}