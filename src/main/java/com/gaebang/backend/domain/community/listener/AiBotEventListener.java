package com.gaebang.backend.domain.community.listener;

import com.gaebang.backend.domain.aibot.service.AiBotService;
import com.gaebang.backend.domain.community.entity.Board;
import com.gaebang.backend.domain.community.entity.BoardCategory;
import com.gaebang.backend.domain.community.entity.ModerationStatus;
import com.gaebang.backend.domain.community.event.ModerationCompletedEvent;
import com.gaebang.backend.domain.community.repository.BoardRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * AI 봇 이벤트 리스너
 * 검열 완료된 질문 게시글에 대해 자동 AI 답변 생성
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiBotEventListener {
    
    private final BoardRepository boardRepository;
    private final AiBotService aiBotService;
    
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleModerationCompleted(ModerationCompletedEvent event) {
        try {
            log.info("[AiBot] 검열 완료 이벤트 수신 - boardId: {}", event.getBoardId());
            
            // 게시글 조회
            Board board = boardRepository.findById(event.getBoardId()).orElse(null);
            if (board == null) {
                log.warn("[AiBot] 게시글을 찾을 수 없습니다 - boardId: {}", event.getBoardId());
                return;
            }
            
            // 방어적 검증: 검열 상태 재확인
            if (board.getModerationStatus() != ModerationStatus.APPROVED) {
                log.warn("[AiBot] 검열 미통과 게시글에 이벤트 수신 - boardId: {}, status: {}", 
                        board.getId(), board.getModerationStatus());
                return;
            }
            
            // 질문 카테고리 확인
            if (board.getCategory() != BoardCategory.QUESTION) {
                log.debug("[AiBot] 질문 카테고리가 아님 - boardId: {}, category: {}", 
                        board.getId(), board.getCategory());
                return;
            }
            
            // 짧은 질문 필터링 (제목 + 내용 20자 미만)
            String fullQuestion = board.getTitle() + board.getContent();
            if (fullQuestion.length() < 20) {
                log.debug("[AiBot] 너무 짧은 질문으로 제외 - boardId: {}, length: {}", 
                        board.getId(), fullQuestion.length());
                return;
            }
            
            log.info("[AiBot] AI 답변 생성 대상 확인 - boardId: {}", board.getId());
            
            // AI 답변 생성 시작
            aiBotService.generateAnswerAsync(board.getId());
            
        } catch (Exception e) {
            log.error("[AiBot] 이벤트 처리 실패 - boardId: {}, error: {}", 
                    event.getBoardId(), e.getMessage(), e);
        }
    }
}