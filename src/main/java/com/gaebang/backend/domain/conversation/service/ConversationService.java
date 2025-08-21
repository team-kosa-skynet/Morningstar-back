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
import com.gaebang.backend.domain.question.common.service.FileProcessingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class ConversationService {

    private final ConversationRepository conversationRepository;
    private final ConversationMessageRepository messageRepository;
    private final MemberRepository memberRepository;
    private final ObjectMapper objectMapper;
    private final FileProcessingService fileProcessingService;

    @Transactional
    public CreateConversationResponseDto createConversation(Long memberId, CreateConversationRequestDto requestDto) {
        log.info("새 대화방 생성 - 사용자 ID: {}, 제목: {}", memberId, requestDto.title());

        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 사용자입니다."));

        Conversation conversation = Conversation.builder()
                .member(member)
                .title(requestDto.title())
                .build();

        Conversation savedConversation = conversationRepository.save(conversation);
        log.info("대화방 생성 완료 - 대화방 ID: {}", savedConversation.getConversationId());

        return CreateConversationResponseDto.from(savedConversation);
    }

    @Transactional
    public void updateConversationTitle(Long conversationId, Long memberId, UpdateConversationTitleRequestDto requestDto) {
        log.info("대화방 제목 수정 - 대화방 ID: {}, 사용자 ID: {}, 새 제목: {}", conversationId, memberId, requestDto.title());

        Conversation conversation = conversationRepository.findActiveConversationByIdAndMemberId(conversationId, memberId)
                .orElseThrow(() -> new ConversationNotFoundException());

        conversation.updateTitle(requestDto.title());
        log.info("대화방 제목 수정 완료");
    }

    @Transactional
    public void deleteConversation(Long conversationId, Long memberId) {
        log.info("대화방 삭제 - 대화방 ID: {}, 사용자 ID: {}", conversationId, memberId);

        Conversation conversation = conversationRepository.findActiveConversationByIdAndMemberId(conversationId, memberId)
                .orElseThrow(() -> new ConversationNotFoundException());

        conversation.deactivate();
        log.info("대화방 삭제 완료");
    }

    public ConversationListResponseDto getConversationList(Long memberId) {
        log.info("대화방 목록 조회 - 사용자 ID: {}", memberId);

        List<Conversation> conversations = conversationRepository
                .findActiveConversationsByMemberIdOrderByModifiedDateDesc(memberId);

        List<ConversationSummaryDto> summaryDtos = conversations.stream()
                .map(conversation -> {
                    Long messageCount = messageRepository.countMessagesByConversationId(conversation.getConversationId());
                    String lastMessagePreview = getLastMessagePreview(conversation.getConversationId());
                    return ConversationSummaryDto.from(conversation, messageCount, lastMessagePreview);
                })
                .toList();

        log.info("대화방 목록 조회 완료 - 총 {}개", summaryDtos.size());
        return ConversationListResponseDto.of(summaryDtos, (long) summaryDtos.size());
    }

    public ConversationDetailResponseDto getConversationDetail(Long conversationId, Long memberId) {
        log.info("대화방 상세 조회 - 대화방 ID: {}, 사용자 ID: {}", conversationId, memberId);

        Conversation conversation = conversationRepository.findActiveConversationByIdAndMemberId(conversationId, memberId)
                .orElseThrow(() -> new ConversationNotFoundException());

        List<ConversationMessage> messages = messageRepository.findMessagesByConversationIdOrderByOrder(conversationId);

        List<MessageResponseDto> messageResponseDtos = messages.stream()
                .map(MessageResponseDto::from)
                .toList();

        log.info("대화방 상세 조회 완료 - 메시지 {}개", messageResponseDtos.size());
        return ConversationDetailResponseDto.from(conversation, messageResponseDtos);
    }

    public ConversationHistoryDto getConversationHistory(Long conversationId, Long memberId, Integer maxMessages) {
        log.info("대화 히스토리 조회 - 대화방 ID: {}, 사용자 ID: {}, 최대 메시지: {}",
                conversationId, memberId, maxMessages);

        conversationRepository.findActiveConversationByIdAndMemberId(conversationId, memberId)
                .orElseThrow(() -> new IllegalArgumentException("대화방을 찾을 수 없거나 접근 권한이 없습니다."));

        List<ConversationMessage> messages;

        if (maxMessages != null && maxMessages > 0) {
            messages = messageRepository.findRecentMessagesByConversationId(conversationId, maxMessages);
            messages = messages.stream()
                    .sorted((m1, m2) -> m1.getMessageOrder().compareTo(m2.getMessageOrder()))
                    .toList();
        } else {
            messages = messageRepository.findMessagesByConversationIdOrderByOrder(conversationId);
        }

        List<MessageResponseDto> messageResponseDtos = messages.stream()
                .map(MessageResponseDto::from)
                .toList();

        log.info("대화 히스토리 조회 완료 - 메시지 {}개", messageResponseDtos.size());
        return ConversationHistoryDto.from(conversationId, messageResponseDtos);
    }

    @Transactional
    public void addQuestion(Long conversationId, Long memberId, AddQuestionRequestDto requestDto) {
        log.info("질문 추가 - 대화방 ID: {}, 사용자 ID: {}", conversationId, memberId);

        Conversation conversation = conversationRepository.findActiveConversationByIdAndMemberId(conversationId, memberId)
                .orElseThrow(() -> new ConversationNotFoundException());

        Integer nextOrder = messageRepository.findNextMessageOrder(conversationId);

        // 파일 내용을 포함한 content 생성
        String contentWithFiles = buildContentWithAttachments(requestDto.content(), requestDto.attachments());

        String attachmentsJson = convertAttachmentsToJson(requestDto.attachments());

        ConversationMessage message = ConversationMessage.builder()
                .conversation(conversation)
                .role(MessageRole.USER)
                .content(contentWithFiles)  // 파일 내용 포함
                .messageOrder(nextOrder)
                .attachments(attachmentsJson)
                .build();

        messageRepository.save(message);

        log.info("질문 추가 완료 - 메시지 순서: {}", nextOrder);
    }

    @Transactional
    public void addAnswer(Long conversationId, Long memberId, AddAnswerRequestDto requestDto) {
        log.info("답변 추가 - 대화방 ID: {}, 사용자 ID: {}, 모델: {}", conversationId, memberId, requestDto.aiModel());

        Conversation conversation = conversationRepository.findActiveConversationByIdAndMemberId(conversationId, memberId)
                .orElseThrow(() -> new ConversationNotFoundException());

        Integer nextOrder = messageRepository.findNextMessageOrder(conversationId);

        String attachmentsJson = convertAttachmentsToJson(requestDto.attachments());

        ConversationMessage message = ConversationMessage.builder()
                .conversation(conversation)
                .role(MessageRole.ASSISTANT)
                .content(requestDto.content())
                .aiModel(requestDto.aiModel())
                .messageOrder(nextOrder)
                .attachments(attachmentsJson)
                .build();

        messageRepository.save(message);

        log.info("답변 추가 완료 - 메시지 순서: {}", nextOrder);
    }

    private String getLastMessagePreview(Long conversationId) {
        List<ConversationMessage> messages = messageRepository.findMessagesByConversationIdOrderByOrder(conversationId);
        if (messages.isEmpty()) {
            return "메시지가 없습니다.";
        }
        
        ConversationMessage lastMessage = messages.get(messages.size() - 1);
        String content = lastMessage.getContent();
        if (content.length() > 50) {
            return content.substring(0, 50) + "...";
        }
        return content;
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

    /**
     * 사용자 텍스트와 파일 첨부 정보를 결합하여 content 생성
     * 파일 내용을 실제로 추출해서 포함시킴
     */
    private String buildContentWithAttachments(String userText, List<FileAttachmentDto> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return userText;
        }

        StringBuilder contentBuilder = new StringBuilder(userText);

        for (FileAttachmentDto attachment : attachments) {
            try {
                // 파일 처리 결과에서 텍스트나 이미지 정보 추출
                Map<String, Object> fileInfo = getProcessedFileInfo(attachment);

                String fileType = (String) fileInfo.get("type");
                String fileName = attachment.fileName();

                contentBuilder.append("\n\n--- 파일: ").append(fileName).append(" ---\n");

                if ("text".equals(fileType)) {
                    String extractedText = (String) fileInfo.get("extractedText");
                    contentBuilder.append(extractedText);
                } else if ("image".equals(fileType)) {
                    contentBuilder.append("이전에 업로드한 이미지: ").append(fileName);
                } else {
                    contentBuilder.append("첨부파일: ").append(fileName);
                }

                contentBuilder.append("\n--- 파일 끝 ---");

            } catch (Exception e) {
                log.error("파일 내용 추가 실패: {}", attachment.fileName(), e);
                contentBuilder.append("\n\n--- 파일: ").append(attachment.fileName()).append(" ---\n");
                contentBuilder.append("[파일 처리 실패]");
                contentBuilder.append("\n--- 파일 끝 ---");
            }
        }

        return contentBuilder.toString();
    }

    /**
     * FileAttachment 정보로부터 실제 파일을 다시 처리해서 정보 획득
     * 실제 구현에서는 S3에서 파일을 다운로드하거나 캐시된 정보를 사용
     */
    private Map<String, Object> getProcessedFileInfo(FileAttachmentDto attachment) {
        // 여기서는 간단한 예시 구현
        // 실제로는 S3에서 파일을 다운로드하고 FileProcessingService로 처리해야 함
        Map<String, Object> result = new HashMap<>();

        String fileName = attachment.fileName();
        String mimeType = attachment.mimeType();

        if (mimeType != null && mimeType.startsWith("image/")) {
            result.put("type", "image");
            result.put("fileName", fileName);
        } else {
            result.put("type", "text");
            result.put("fileName", fileName);
            result.put("extractedText", "[파일 내용을 다시 추출해야 함: " + fileName + "]");
        }

        return result;
    }
}
