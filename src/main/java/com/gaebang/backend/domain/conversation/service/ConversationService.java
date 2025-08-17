package com.gaebang.backend.domain.conversation.service;

import com.gaebang.backend.domain.conversation.dto.request.*;
import com.gaebang.backend.domain.conversation.dto.response.*;
import com.gaebang.backend.domain.conversation.entity.Conversation;
import com.gaebang.backend.domain.conversation.entity.ConversationMessage;
import com.gaebang.backend.domain.conversation.entity.MessageRole;
import com.gaebang.backend.domain.conversation.repository.ConversationMessageRepository;
import com.gaebang.backend.domain.conversation.repository.ConversationRepository;
import com.gaebang.backend.domain.member.entity.Member;
import com.gaebang.backend.domain.member.exception.UserNotFoundException;
import com.gaebang.backend.domain.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 대화방과 메시지 관리를 담당하는 핵심 서비스
 * LLM 질문 서비스들이 이 서비스를 통해 대화 히스토리를 관리함
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class ConversationService {

    private final ConversationRepository conversationRepository;
    private final ConversationMessageRepository messageRepository;
    private final MemberRepository memberRepository;

    /**
     * 새로운 대화방을 생성합니다
     * 사용자가 "새 채팅" 버튼을 클릭했을 때 호출
     *
     * @param memberId 대화방을 생성할 사용자 ID
     * @param request 대화방 생성 요청 (제목 포함, 선택사항)
     * @return 생성된 대화방 정보
     * @throws UserNotFoundException 사용자를 찾을 수 없는 경우
     */
    @Transactional
    public CreateConversationResponseDto createConversation(Long memberId, CreateConversationRequestDto request) {
        log.info("새 대화방 생성 시작 - 사용자 ID: {}, 제목: {}", memberId, request.title());

        // 사용자 존재 확인
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new UserNotFoundException());

        // 제목 결정: 사용자 입력이 있으면 사용, 없으면 기본값
        String title = (request.title() != null && !request.title().trim().isEmpty())
                ? request.title().trim()
                : "새로운 대화";

        // 대화방 생성 및 저장
        Conversation conversation = Conversation.builder()
                .title(title)
                .member(member)
                .build();

        Conversation savedConversation = conversationRepository.save(conversation);

        log.info("새 대화방 생성 완료 - 대화방 ID: {}", savedConversation.getConversationId());
        return CreateConversationResponseDto.from(savedConversation);
    }

    /**
     * 사용자의 모든 활성 대화방 목록을 조회합니다 (사이드바용)
     * ChatGPT 왼쪽 사이드바처럼 최근 대화방부터 표시
     *
     * @param memberId 조회할 사용자 ID
     * @return 대화방 목록과 총 개수
     */
    public ConversationListResponseDto getConversationList(Long memberId) {
        log.info("대화방 목록 조회 - 사용자 ID: {}", memberId);

        // 사용자의 활성 대화방들을 최신순으로 조회
        List<Conversation> conversations = conversationRepository
                .findActiveConversationsByMemberIdOrderByModifiedDateDesc(memberId);

        // 각 대화방의 요약 정보 생성 (메시지 개수, 마지막 메시지 미리보기 포함)
        List<ConversationSummaryDto> summaryDtos = conversations.stream()
                .map(conversation -> {
                    // 각 대화방의 메시지 개수 조회
                    Long messageCount = messageRepository.countMessagesByConversationId(conversation.getConversationId());

                    // 마지막 메시지 미리보기 생성 (최대 50자)
                    String lastMessagePreview = getLastMessagePreview(conversation.getConversationId());

                    return ConversationSummaryDto.from(conversation, messageCount, lastMessagePreview);
                })
                .toList();

        log.info("대화방 목록 조회 완료 - 총 {}개", summaryDtos.size());
        return ConversationListResponseDto.of(summaryDtos, (long) summaryDtos.size());
    }

    /**
     * 특정 대화방의 상세 정보와 전체 메시지 히스토리를 조회합니다
     * 사용자가 사이드바에서 특정 대화방을 클릭했을 때 호출
     *
     * @param conversationId 조회할 대화방 ID
     * @param memberId 요청한 사용자 ID (보안 검증용)
     * @return 대화방 상세 정보와 전체 메시지 목록
     * @throws IllegalArgumentException 대화방을 찾을 수 없거나 접근 권한이 없는 경우
     */
    public ConversationDetailResponseDto getConversationDetail(Long conversationId, Long memberId) {
        log.info("대화방 상세 조회 - 대화방 ID: {}, 사용자 ID: {}", conversationId, memberId);

        // 대화방 존재 확인 및 접근 권한 검증
        Conversation conversation = conversationRepository
                .findActiveConversationByIdAndMemberId(conversationId, memberId)
                .orElseThrow(() -> new IllegalArgumentException("대화방을 찾을 수 없거나 접근 권한이 없습니다."));

        // 해당 대화방의 모든 메시지를 시간순으로 조회
        List<ConversationMessage> messages = messageRepository
                .findMessagesByConversationIdOrderByOrder(conversationId);

        // 메시지 엔티티를 응답 DTO로 변환
        List<MessageResponseDto> messageResponseDtos = messages.stream()
                .map(MessageResponseDto::from)
                .toList();

        log.info("대화방 상세 조회 완료 - 메시지 {}개", messageResponseDtos.size());
        return ConversationDetailResponseDto.from(conversation, messageResponseDtos);
    }

    /**
     * LLM API 호출을 위한 대화 히스토리를 조회합니다 (Question 서비스 전용)
     * OpenAI, Claude, Gemini 서비스에서 이전 대화 맥락을 포함해서 API를 호출할 때 사용
     *
     * @param conversationId 대화방 ID
     * @param memberId 사용자 ID (보안 검증용)
     * @param maxMessages 최대 포함할 메시지 개수 (토큰 제한 관리용, null이면 전체)
     * @return LLM API 호출용 히스토리 (messages 배열 형태)
     */
    public ConversationHistoryDto getConversationHistory(Long conversationId, Long memberId, Integer maxMessages) {
        log.info("대화 히스토리 조회 - 대화방 ID: {}, 사용자 ID: {}, 최대 메시지: {}",
                conversationId, memberId, maxMessages);

        // 대화방 접근 권한 검증
        conversationRepository.findActiveConversationByIdAndMemberId(conversationId, memberId)
                .orElseThrow(() -> new IllegalArgumentException("대화방을 찾을 수 없거나 접근 권한이 없습니다."));

        List<ConversationMessage> messages;

        if (maxMessages != null && maxMessages > 0) {
            // 최근 N개 메시지만 조회 (토큰 제한이 있는 경우)
            messages = messageRepository.findRecentMessagesByConversationId(conversationId, maxMessages);
            // 시간순 정렬 (최근 메시지 조회는 역순으로 오므로)
            messages = messages.stream()
                    .sorted((m1, m2) -> m1.getMessageOrder().compareTo(m2.getMessageOrder()))
                    .toList();
        } else {
            // 전체 메시지 조회
            messages = messageRepository.findMessagesByConversationIdOrderByOrder(conversationId);
        }

        // 메시지를 LLM API 형태로 변환
        List<MessageResponseDto> messageResponseDtos = messages.stream()
                .map(MessageResponseDto::from)
                .toList();

        log.info("대화 히스토리 조회 완료 - 메시지 {}개", messageResponseDtos.size());
        return ConversationHistoryDto.from(conversationId, messageResponseDtos);
    }

    /**
     * 대화방에 사용자 질문을 추가합니다
     * Question 서비스에서 사용자가 새로운 질문을 했을 때 호출
     *
     * @param conversationId 대화방 ID
     * @param memberId 사용자 ID
     * @param request 질문 내용
     * @return 추가된 메시지 정보
     */
    @Transactional
    public MessageResponseDto addQuestion(Long conversationId, Long memberId, AddQuestionRequestDto request) {
        log.info("사용자 질문 추가 - 대화방 ID: {}, 사용자 ID: {}", conversationId, memberId);

        // 대화방 접근 권한 검증
        Conversation conversation = conversationRepository
                .findActiveConversationByIdAndMemberId(conversationId, memberId)
                .orElseThrow(() -> new IllegalArgumentException("대화방을 찾을 수 없거나 접근 권한이 없습니다."));

        // 다음 메시지 순서 번호 조회
        Integer nextOrder = messageRepository.findNextMessageOrder(conversationId);

        // 사용자 질문 메시지 생성
        ConversationMessage questionMessage = ConversationMessage.builder()
                .conversation(conversation)
                .role(MessageRole.USER)
                .content(request.content())
                .aiModel(null) // 사용자 질문이므로 AI 모델 없음
                .messageOrder(nextOrder)
                .build();

        ConversationMessage savedMessage = messageRepository.save(questionMessage);

        // 첫 번째 질문이면 대화방 제목을 자동 생성 (제목이 "새로운 대화"인 경우만)
        if (nextOrder == 1 && "새로운 대화".equals(conversation.getTitle())) {
            updateConversationTitleFromFirstQuestion(conversation, request.content());
        }

        log.info("사용자 질문 추가 완료 - 메시지 ID: {}", savedMessage.getMessageId());
        return MessageResponseDto.from(savedMessage);
    }

    /**
     * 대화방에 AI 답변을 추가합니다
     * 사용자가 3개의 답변 중 하나를 선택했을 때 호출
     *
     * @param conversationId 대화방 ID
     * @param memberId 사용자 ID
     * @param request AI 답변 내용과 모델 정보
     * @return 추가된 답변 메시지 정보
     */
    @Transactional
    public MessageResponseDto addAnswer(Long conversationId, Long memberId, AddAnswerRequestDto request) {
        log.info("AI 답변 추가 - 대화방 ID: {}, 사용자 ID: {}, 모델: {}",
                conversationId, memberId, request.aiModel());

        // 대화방 접근 권한 검증
        Conversation conversation = conversationRepository
                .findActiveConversationByIdAndMemberId(conversationId, memberId)
                .orElseThrow(() -> new IllegalArgumentException("대화방을 찾을 수 없거나 접근 권한이 없습니다."));

        // 다음 메시지 순서 번호 조회
        Integer nextOrder = messageRepository.findNextMessageOrder(conversationId);

        // AI 답변 메시지 생성
        ConversationMessage answerMessage = ConversationMessage.builder()
                .conversation(conversation)
                .role(MessageRole.ASSISTANT)
                .content(request.content())
                .aiModel(request.aiModel())
                .messageOrder(nextOrder)
                .build();

        ConversationMessage savedMessage = messageRepository.save(answerMessage);

        log.info("AI 답변 추가 완료 - 메시지 ID: {}, 모델: {}", savedMessage.getMessageId(), request.aiModel());
        return MessageResponseDto.from(savedMessage);
    }

    /**
     * 대화방 제목을 수정합니다
     * 사용자가 대화방 제목을 직접 편집할 때 사용
     *
     * @param conversationId 대화방 ID
     * @param memberId 사용자 ID
     * @param request 새로운 제목
     * @return 수정된 대화방 정보
     */
    @Transactional
    public CreateConversationResponseDto updateConversationTitle(Long conversationId, Long memberId,
                                                                 UpdateConversationTitleRequestDto request) {
        log.info("대화방 제목 수정 - 대화방 ID: {}, 사용자 ID: {}, 새 제목: {}",
                conversationId, memberId, request.title());

        // 대화방 접근 권한 검증
        Conversation conversation = conversationRepository
                .findActiveConversationByIdAndMemberId(conversationId, memberId)
                .orElseThrow(() -> new IllegalArgumentException("대화방을 찾을 수 없거나 접근 권한이 없습니다."));

        // 제목 업데이트
        conversation.updateTitle(request.title());
        Conversation updatedConversation = conversationRepository.save(conversation);

        log.info("대화방 제목 수정 완료 - 대화방 ID: {}", conversationId);
        return CreateConversationResponseDto.from(updatedConversation);
    }

    /**
     * 대화방을 삭제합니다 (소프트 삭제)
     * 실제로 DB에서 삭제하지 않고 isActive를 false로 변경
     *
     * @param conversationId 삭제할 대화방 ID
     * @param memberId 사용자 ID
     */
    @Transactional
    public void deleteConversation(Long conversationId, Long memberId) {
        log.info("대화방 삭제 - 대화방 ID: {}, 사용자 ID: {}", conversationId, memberId);

        // 대화방 접근 권한 검증
        Conversation conversation = conversationRepository
                .findActiveConversationByIdAndMemberId(conversationId, memberId)
                .orElseThrow(() -> new IllegalArgumentException("대화방을 찾을 수 없거나 접근 권한이 없습니다."));

        // 소프트 삭제
        conversation.deactivate();
        conversationRepository.save(conversation);

        log.info("대화방 삭제 완료 - 대화방 ID: {}", conversationId);
    }

    // ===== Private Helper Methods =====

    /**
     * 마지막 메시지의 미리보기 텍스트를 생성합니다 (사이드바 표시용)
     *
     * @param conversationId 대화방 ID
     * @return 마지막 메시지의 첫 50자 (없으면 빈 문자열)
     */
    private String getLastMessagePreview(Long conversationId) {
        List<ConversationMessage> recentMessages = messageRepository
                .findRecentMessagesByConversationId(conversationId, 1);

        if (recentMessages.isEmpty()) {
            return "";
        }

        String content = recentMessages.get(0).getContent();
        return content.length() > 50 ? content.substring(0, 50) + "..." : content;
    }

    /**
     * 첫 번째 질문을 바탕으로 대화방 제목을 자동 생성합니다
     *
     * @param conversation 대화방 엔티티
     * @param firstQuestion 첫 번째 질문 내용
     */
    private void updateConversationTitleFromFirstQuestion(Conversation conversation, String firstQuestion) {
        // 첫 질문에서 제목 생성 (최대 50자, 의미있는 부분 추출)
        String autoTitle = generateTitleFromQuestion(firstQuestion);
        conversation.updateTitle(autoTitle);
        conversationRepository.save(conversation);

        log.info("대화방 제목 자동 생성 - 대화방 ID: {}, 제목: {}", conversation.getConversationId(), autoTitle);
    }

    /**
     * 질문 내용에서 의미있는 제목을 추출합니다
     *
     * @param question 질문 내용
     * @return 생성된 제목 (최대 50자)
     */
    private String generateTitleFromQuestion(String question) {
        // 간단한 제목 생성 로직 (실제로는 더 정교한 로직 사용 가능)
        String cleaned = question.trim().replaceAll("\n", " ").replaceAll("\\s+", " ");

        if (cleaned.length() <= 50) {
            return cleaned;
        }

        // 50자에서 단어 단위로 자르기
        String truncated = cleaned.substring(0, 50);
        int lastSpaceIndex = truncated.lastIndexOf(' ');

        if (lastSpaceIndex > 20) { // 너무 짧지 않으면 단어 단위로 자르기
            return truncated.substring(0, lastSpaceIndex) + "...";
        } else {
            return truncated + "...";
        }
    }
}
