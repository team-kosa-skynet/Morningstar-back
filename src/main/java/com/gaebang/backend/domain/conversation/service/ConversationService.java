package com.gaebang.backend.domain.conversation.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaebang.backend.domain.conversation.dto.request.AddAnswerRequestDto;
import com.gaebang.backend.domain.conversation.dto.request.AddQuestionRequestDto;
import com.gaebang.backend.domain.conversation.dto.request.CreateConversationRequestDto;
import com.gaebang.backend.domain.conversation.dto.request.FileAttachmentDto;
import com.gaebang.backend.domain.conversation.dto.request.UpdateConversationTitleRequestDto;
import com.gaebang.backend.domain.conversation.dto.response.ConversationDetailResponseDto;
import com.gaebang.backend.domain.conversation.dto.response.ConversationHistoryDto;
import com.gaebang.backend.domain.conversation.dto.response.ConversationListResponseDto;
import com.gaebang.backend.domain.conversation.dto.response.ConversationSummaryDto;
import com.gaebang.backend.domain.conversation.dto.response.CreateConversationResponseDto;
import com.gaebang.backend.domain.conversation.dto.response.MessageResponseDto;
import com.gaebang.backend.domain.conversation.entity.Conversation;
import com.gaebang.backend.domain.conversation.entity.ConversationMessage;
import com.gaebang.backend.domain.conversation.entity.MessageRole;
import com.gaebang.backend.domain.conversation.exception.ConversationNotFoundException;
import com.gaebang.backend.domain.conversation.repository.ConversationMessageRepository;
import com.gaebang.backend.domain.conversation.repository.ConversationRepository;
import com.gaebang.backend.domain.member.entity.Member;
import com.gaebang.backend.domain.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ConversationService {

    private final ConversationRepository conversationRepository;
    private final ConversationMessageRepository messageRepository;
    private final MemberRepository memberRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public CreateConversationResponseDto createConversation(Long memberId, CreateConversationRequestDto requestDto) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 사용자입니다."));

        Conversation conversation = Conversation.builder()
                .member(member)
                .title(requestDto.title())
                .build();

        Conversation savedConversation = conversationRepository.save(conversation);
        return CreateConversationResponseDto.from(savedConversation);
    }

    @Transactional
    public void updateConversationTitle(Long conversationId, Long memberId, UpdateConversationTitleRequestDto requestDto) {
        Conversation conversation = conversationRepository.findActiveConversationByIdAndMemberId(conversationId, memberId)
                .orElseThrow(() -> new ConversationNotFoundException());

        conversation.updateTitle(requestDto.title());
    }

    @Transactional
    public void deleteConversation(Long conversationId, Long memberId) {

        Conversation conversation = conversationRepository.findActiveConversationByIdAndMemberId(conversationId, memberId)
                .orElseThrow(() -> new ConversationNotFoundException());

        conversation.deactivate();
    }

    public ConversationListResponseDto getConversationList(Long memberId) {
        List<Object[]> conversationSummaries = conversationRepository
                .findConversationSummariesByMemberId(memberId);

        List<ConversationSummaryDto> summaryDtos = conversationSummaries.stream()
                .map(result -> {
                    Conversation conversation = (Conversation) result[0];
                    Long messageCount = (Long) result[1];
                    String lastMessageContent = (String) result[2];
                    
                    String lastMessagePreview = getLastMessagePreview(lastMessageContent);
                    return ConversationSummaryDto.from(conversation, messageCount, lastMessagePreview);
                })
                .toList();

        return ConversationListResponseDto.of(summaryDtos, (long) summaryDtos.size());
    }

    public ConversationDetailResponseDto getConversationDetail(Long conversationId, Long memberId) {
        Conversation conversation = conversationRepository.findActiveConversationByIdAndMemberId(conversationId, memberId)
                .orElseThrow(() -> new ConversationNotFoundException());

        List<ConversationMessage> messages = messageRepository.findMessagesByConversationIdOrderByOrder(conversationId);

        List<MessageResponseDto> messageResponseDtos = messages.stream()
                .map(MessageResponseDto::from)
                .toList();

        return ConversationDetailResponseDto.from(conversation, messageResponseDtos);
    }

    public ConversationHistoryDto getConversationHistory(Long conversationId, Long memberId, Integer maxMessages) {
        conversationRepository.findActiveConversationByIdAndMemberId(conversationId, memberId)
                .orElseThrow(() -> new IllegalArgumentException("대화방을 찾을 수 없거나 접근 권한이 없습니다."));

        List<ConversationMessage> messages;

        if (maxMessages != null && maxMessages > 0) {
            Pageable pageable = PageRequest.of(0, maxMessages);
            messages = messageRepository.findRecentMessagesByConversationId(conversationId, pageable);
            messages = messages.stream()
                    .sorted((m1, m2) -> m1.getMessageOrder().compareTo(m2.getMessageOrder()))
                    .toList();
        } else {
            messages = messageRepository.findMessagesByConversationIdOrderByOrder(conversationId);
        }

        List<MessageResponseDto> messageResponseDtos = messages.stream()
                .map(MessageResponseDto::from)
                .toList();

        return ConversationHistoryDto.from(conversationId, messageResponseDtos);
    }

    @Transactional
    public void addQuestion(Long conversationId, Long memberId, AddQuestionRequestDto requestDto) {
        Conversation conversation = conversationRepository.findActiveConversationByIdAndMemberId(conversationId, memberId)
                .orElseThrow(() -> new ConversationNotFoundException());

        Integer nextOrder = messageRepository.findNextMessageOrder(conversationId);

        String attachmentsJson = convertAttachmentsToJson(requestDto.attachments());

        ConversationMessage message = requestDto.toEntity(conversation, requestDto.content(), nextOrder, attachmentsJson);

        messageRepository.save(message);

        log.info("질문 추가 완료 - 메시지 순서: {}", nextOrder);
    }

    @Transactional
    public void addAnswer(Long conversationId, Long memberId, AddAnswerRequestDto requestDto) {
        Conversation conversation = conversationRepository.findActiveConversationByIdAndMemberId(conversationId, memberId)
                .orElseThrow(() -> new ConversationNotFoundException());

        Integer nextOrder = messageRepository.findNextMessageOrder(conversationId);

        String attachmentsJson = convertAttachmentsToJson(requestDto.attachments());

        ConversationMessage message = requestDto.toEntity(conversation, nextOrder, attachmentsJson);

        messageRepository.save(message);

        log.info("답변 추가 완료 - 메시지 순서: {}", nextOrder);
    }

    private String getLastMessagePreview(String lastMessageContent) {
        if (lastMessageContent == null || lastMessageContent.isEmpty()) {
            return "메시지가 없습니다.";
        }
        
        if (lastMessageContent.length() > 50) {
            return lastMessageContent.substring(0, 50) + "...";
        }
        return lastMessageContent;
    }

    private String convertAttachmentsToJson(List<FileAttachmentDto> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return null;
        }

        try {
            return objectMapper.writeValueAsString(attachments);
        } catch (JsonProcessingException e) {
            log.error("첨부파일 JSON 변환 실패", e);
            return null;
        }
    }

}
