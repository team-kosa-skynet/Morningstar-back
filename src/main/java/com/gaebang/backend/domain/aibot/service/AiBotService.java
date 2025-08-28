package com.gaebang.backend.domain.aibot.service;

import com.gaebang.backend.domain.aibot.entity.*;
import com.gaebang.backend.domain.aibot.repository.BotResponseRepository;
import com.gaebang.backend.domain.aibot.repository.BotResponseRequestRepository;
import com.gaebang.backend.domain.community.entity.Board;
import com.gaebang.backend.domain.community.entity.Comment;
import com.gaebang.backend.domain.community.repository.BoardRepository;
import com.gaebang.backend.domain.community.service.CommentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;

/**
 * AI 봇 서비스 - 핵심 비즈니스 로직
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiBotService {
    
    private final BotResponseRepository botResponseRepository;
    private final BotResponseRequestRepository botResponseRequestRepository;
    private final BoardRepository boardRepository;
    private final CommentService commentService;
    // TODO: QuestionAnswerAiService 의존성은 다음 단계에서 추가
    
    @Value("${aibot.enabled:true}")
    private boolean aiBotEnabled;
    
    @Value("${aibot.confidence.threshold:0.7}")
    private double confidenceThreshold;
    
    @Value("${aibot.max-daily-responses:100}")
    private int maxDailyResponses;
    
    /**
     * 새 게시글에 대한 AI 답변 생성 처리 (비동기)
     */
    @Async("aiBotExecutor")
    @Transactional
    public CompletableFuture<Void> generateAnswerAsync(Long boardId) {
        try {
            log.info("[AiBot] AI 답변 생성 시작 - boardId: {}", boardId);
            
            // 1. 기본 검증
            if (!aiBotEnabled) {
                log.debug("[AiBot] AI 봇 기능이 비활성화됨");
                return CompletableFuture.completedFuture(null);
            }
            
            // 2. 일일 한도 확인
            if (isExceedingDailyLimit()) {
                log.warn("[AiBot] 일일 답변 생성 한도 초과");
                return CompletableFuture.completedFuture(null);
            }
            
            // 3. 게시글 조회
            Board board = boardRepository.findById(boardId)
                    .orElseThrow(() -> new IllegalArgumentException("게시글을 찾을 수 없습니다: " + boardId));
            
            // 4. 중복 답변 방지 (이미 AI 답변이 있는지 확인)
            if (botResponseRepository.existsPostedResponseForBoard(boardId)) {
                log.debug("[AiBot] 이미 AI 답변이 존재함 - boardId: {}", boardId);
                return CompletableFuture.completedFuture(null);
            }
            
            // 5. 답변 요청 기록 생성
            BotResponseRequest request = createResponseRequest(boardId, RequestType.AUTO);
            
            // 6. AI 답변 생성 및 처리
            generateAndProcessResponse(board, request);
            
            log.info("[AiBot] AI 답변 생성 완료 - boardId: {}", boardId);
            return CompletableFuture.completedFuture(null);
            
        } catch (Exception e) {
            log.error("[AiBot] AI 답변 생성 실패 - boardId: {}, error: {}", boardId, e.getMessage(), e);
            return CompletableFuture.completedFuture(null);
        }
    }
    
    /**
     * AI 답변 생성 및 게시 처리
     */
    private void generateAndProcessResponse(Board board, BotResponseRequest request) {
        try {
            // 1. BotResponse 엔티티 생성
            BotResponse botResponse = BotResponse.builder()
                    .boardId(board.getId())
                    .question(board.getTitle() + "\n" + board.getContent())
                    .status(ResponseStatus.PENDING)
                    .aiProvider(getCurrentAiProvider())
                    .questionCategory("GENERAL") // TODO: 질문 분류 로직 추가
                    .build();
            
            botResponseRepository.save(botResponse);
            request.markAsProcessing();
            botResponseRequestRepository.save(request);
            
            // TODO: Phase 3에서 실제 AI 답변 생성 로직 구현
            // 현재는 더미 답변으로 테스트
            String dummyAnswer = generateDummyAnswer(board);
            double dummyConfidence = 0.85; // 임시 신뢰도
            
            // 2. 답변 업데이트
            botResponse.markAsGenerated(dummyAnswer, dummyConfidence);
            botResponseRepository.save(botResponse);
            
            // 3. 신뢰도 기준 확인
            if (botResponse.getConfidenceScore() < confidenceThreshold) {
                log.warn("[AiBot] 낮은 신뢰도로 인한 답변 보류 - boardId: {}, confidence: {}", 
                        board.getId(), botResponse.getConfidenceScore());
                botResponse.markAsFailed("신뢰도 부족 (threshold: " + confidenceThreshold + ")");
                botResponseRepository.save(botResponse);
                return;
            }
            
            // 4. 검열 통과로 가정 (TODO: 실제 검열 연동)
            botResponse.markAsModerated();
            botResponseRepository.save(botResponse);
            
            // 5. Comment로 답변 게시
            Comment aiComment = createAiComment(board, botResponse);
            botResponse.markAsPosted(aiComment.getId());
            botResponseRepository.save(botResponse);
            
            // 6. 요청 완료 처리
            request.markAsCompleted();
            botResponseRequestRepository.save(request);
            
            log.info("[AiBot] AI 답변 게시 완료 - boardId: {}, commentId: {}, confidence: {}", 
                    board.getId(), aiComment.getId(), botResponse.getConfidenceScore());
            
        } catch (Exception e) {
            log.error("[AiBot] 답변 생성 및 처리 실패 - boardId: {}, error: {}", board.getId(), e.getMessage(), e);
            request.markAsCancelled("처리 중 오류: " + e.getMessage());
            botResponseRequestRepository.save(request);
        }
    }
    
    /**
     * AI 답변을 Comment로 생성
     */
    private Comment createAiComment(Board board, BotResponse botResponse) {
        // AI 답변임을 명시하는 서명 추가
        String finalAnswer = botResponse.getResponse() + 
                "\n\n---\n" +
                "🤖 *이 답변은 AI가 생성했습니다. 참고용으로 활용해주세요.*\n" +
                "👍👎 *답변이 도움이 되셨나요? 피드백을 남겨주세요.*";
        
        // Comment 생성 (기존 CommentService 활용)
        // TODO: CommentService에 createAiComment 메서드 추가 필요
        return commentService.createAiComment(board.getId(), finalAnswer, 
                botResponse.getAiProvider().getDisplayName(), botResponse.getConfidenceScore());
    }
    
    /**
     * 임시 더미 답변 생성 (Phase 3에서 실제 AI로 교체)
     */
    private String generateDummyAnswer(Board board) {
        return String.format("안녕하세요! '%s'에 대한 질문에 답변드리겠습니다.\n\n" +
                "이는 현재 개발 중인 AI 봇의 테스트 답변입니다. " +
                "실제 서비스에서는 더 정확하고 유용한 답변을 제공할 예정입니다.\n\n" +
                "질문 내용: %s", 
                board.getTitle(), 
                board.getContent().length() > 50 ? 
                    board.getContent().substring(0, 50) + "..." : 
                    board.getContent());
    }
    
    /**
     * 일일 생성 한도 확인
     */
    private boolean isExceedingDailyLimit() {
        LocalDateTime today = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime tomorrow = today.plusDays(1);
        
        long todayCount = botResponseRepository.countByStatusAndCreatedAtBetween(
                ResponseStatus.POSTED, today, tomorrow);
        
        return todayCount >= maxDailyResponses;
    }
    
    /**
     * 응답 요청 생성
     */
    private BotResponseRequest createResponseRequest(Long boardId, RequestType type) {
        BotResponseRequest request = BotResponseRequest.builder()
                .boardId(boardId)
                .requestType(type)
                .priority(type == RequestType.MANUAL ? 1 : 3)
                .build();
        return botResponseRequestRepository.save(request);
    }
    
    /**
     * 현재 AI 제공자 결정
     */
    private AiProvider getCurrentAiProvider() {
        // TODO: application.yml의 ai.provider 설정에 따라 결정
        return AiProvider.GEMINI;
    }
}