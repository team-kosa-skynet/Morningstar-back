package com.gaebang.backend.domain.community.service;

import com.gaebang.backend.domain.community.entity.Board;
import com.gaebang.backend.domain.community.entity.Comment;
import com.gaebang.backend.domain.community.repository.BoardRepository;
import com.gaebang.backend.domain.community.repository.CommentRepository;
import com.gaebang.backend.domain.interview.llm.GeminiInterviewerGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.CompletableFuture;

@Slf4j
@RequiredArgsConstructor
@Service
public class ModerationService {

    private final BoardRepository boardRepository;
    private final CommentRepository commentRepository;
    private final ContentBackupService contentBackupService;
    private final GeminiInterviewerGateway geminiInterviewerGateway;

    @Value("${moderation.enabled:true}")
    private boolean moderationEnabled;

    @Value("${moderation.censorship.title-replacement:검열된 게시글입니다}")
    private String censoredTitle;

    @Value("${moderation.censorship.content-template:이 게시글은 부적절한 내용으로 인해 검열되었습니다.\\n\\n검열 사유: {reason}\\n문의: admin@morningstar.com}")
    private String contentTemplate;

    @Async("moderationExecutor")
    @Transactional
    public CompletableFuture<Void> moderateBoardAsync(Long boardId) {
        if (!moderationEnabled) {
            log.debug("컨텐츠 검열이 비활성화되어 있습니다.");
            return CompletableFuture.completedFuture(null);
        }

        try {
            Board board = boardRepository.findById(boardId).orElse(null);
            if (board == null) {
                log.warn("검열 대상 게시글을 찾을 수 없습니다. ID: {}", boardId);
                return CompletableFuture.completedFuture(null);
            }

            String contentToCheck = board.getTitle() + "\\n" + board.getContent();
            ModerationResult result = geminiInterviewerGateway.moderateContent(contentToCheck);

            if (result.isInappropriate()) {
                log.info("부적절한 게시글 발견 - ID: {}, 사유: {}", boardId, result.getReason());
                
                // 백업 생성
                contentBackupService.createBoardBackup(board, result.getReason());
                
                // 내용 교체
                String censoredContent = contentTemplate.replace("{reason}", result.getReason());
                board.censorContent(censoredTitle, censoredContent);
                boardRepository.save(board);
                
                log.info("게시글 검열 완료 - ID: {}", boardId);
            } else {
                // 검열 통과
                board.approveModerationContent();
                boardRepository.save(board);
                log.debug("게시글 검열 통과 - ID: {}", boardId);
            }

        } catch (Exception e) {
            log.error("게시글 검열 중 오류 발생 - ID: {}, 오류: {}", boardId, e.getMessage(), e);
        }

        return CompletableFuture.completedFuture(null);
    }

    @Async("moderationExecutor")
    @Transactional
    public CompletableFuture<Void> moderateCommentAsync(Long commentId) {
        if (!moderationEnabled) {
            log.debug("컨텐츠 검열이 비활성화되어 있습니다.");
            return CompletableFuture.completedFuture(null);
        }

        try {
            Comment comment = commentRepository.findById(commentId).orElse(null);
            if (comment == null) {
                log.warn("검열 대상 댓글을 찾을 수 없습니다. ID: {}", commentId);
                return CompletableFuture.completedFuture(null);
            }

            ModerationResult result = geminiInterviewerGateway.moderateContent(comment.getContent());

            if (result.isInappropriate()) {
                log.info("부적절한 댓글 발견 - ID: {}, 사유: {}", commentId, result.getReason());
                
                // 백업 생성
                contentBackupService.createCommentBackup(comment, result.getReason());
                
                // 내용 교체
                String censoredContent = "이 댓글은 부적절한 내용으로 인해 검열되었습니다.\\n\\n검열 사유: " + 
                                       result.getReason() + "\\n문의: admin@morningstar.com";
                comment.censorContent(censoredContent);
                commentRepository.save(comment);
                
                log.info("댓글 검열 완료 - ID: {}", commentId);
            } else {
                // 검열 통과
                comment.approveModerationContent();
                commentRepository.save(comment);
                log.debug("댓글 검열 통과 - ID: {}", commentId);
            }

        } catch (Exception e) {
            log.error("댓글 검열 중 오류 발생 - ID: {}, 오류: {}", commentId, e.getMessage(), e);
        }

        return CompletableFuture.completedFuture(null);
    }

    // 검열 결과를 나타내는 내부 클래스
    public static class ModerationResult {
        private final boolean inappropriate;
        private final String reason;

        public ModerationResult(boolean inappropriate, String reason) {
            this.inappropriate = inappropriate;
            this.reason = reason;
        }

        public boolean isInappropriate() {
            return inappropriate;
        }

        public String getReason() {
            return reason;
        }
    }
}