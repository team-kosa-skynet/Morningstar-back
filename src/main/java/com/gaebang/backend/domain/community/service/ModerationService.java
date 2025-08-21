package com.gaebang.backend.domain.community.service;

import com.gaebang.backend.domain.community.dto.ModerationResult;
import com.gaebang.backend.domain.community.entity.Board;
import com.gaebang.backend.domain.community.entity.Comment;
import com.gaebang.backend.domain.community.repository.BoardRepository;
import com.gaebang.backend.domain.community.repository.CommentRepository;
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
    private final TextModerationService textModerationService;
    private final ImageModerationService imageModerationService;

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

            // 1단계: 텍스트 검열 (Circuit Breaker 적용)
            CompletableFuture<ModerationResult> textResultFuture = textModerationService.moderateTitleAndContent(
                board.getTitle(), board.getContent());
            
            ModerationResult textResult = textResultFuture.get(); // 동기 처리

            if (textResult.isInappropriate()) {
                log.info("부적절한 게시글 텍스트 발견 - ID: {}, 사유: {}", boardId, textResult.getReason());
                
                // 백업 생성
                contentBackupService.createBoardBackup(board, textResult.getReason());
                
                // 내용 교체
                String censoredContent = contentTemplate.replace("{reason}", textResult.getReason());
                board.censorContent(censoredTitle, censoredContent);
                boardRepository.save(board);
                
                log.info("게시글 텍스트 검열 완료 - ID: {}", boardId);
                return CompletableFuture.completedFuture(null);
            }

            // 2단계: 텍스트가 적절한 경우에만 이미지 검열 수행
            if (!board.getImages().isEmpty()) {
                log.debug("게시글 이미지 검열 시작 - ID: {}, 이미지 수: {}", boardId, board.getImages().size());
                
                for (var image : board.getImages()) {
                    // 지원되는 형식만 검열
                    if (imageModerationService.isSupportedImageFormat(image.getImageUrl())) {
                        // Circuit Breaker가 적용된 이미지 검열
                        CompletableFuture<ModerationResult> imageResultFuture = 
                            imageModerationService.moderateImage(image.getImageUrl());
                        
                        ModerationResult imageResult = imageResultFuture.get(); // 동기 처리
                        
                        if (imageResult.isInappropriate()) {
                            log.info("부적절한 게시글 이미지 발견 - ID: {}, 이미지 URL: {}, 사유: {}", 
                                    boardId, image.getImageUrl(), imageResult.getReason());
                            
                            // 백업 생성 (이미지 검열 사유)
                            contentBackupService.createBoardBackup(board, "이미지 검열: " + imageResult.getReason());
                            
                            // 내용 교체
                            String censoredContent = contentTemplate.replace("{reason}", "이미지 검열: " + imageResult.getReason());
                            board.censorContent(censoredTitle, censoredContent);
                            boardRepository.save(board);
                            
                            log.info("게시글 이미지 검열 완료 - ID: {}", boardId);
                            return CompletableFuture.completedFuture(null);
                        }
                    } else {
                        log.warn("지원하지 않는 이미지 형식 - ID: {}, URL: {}", boardId, image.getImageUrl());
                    }
                }
                
                log.debug("게시글 이미지 검열 통과 - ID: {}", boardId);
            }

            // 모든 검열 통과
            board.approveModerationContent();
            boardRepository.save(board);
            log.debug("게시글 전체 검열 통과 - ID: {}", boardId);

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

            // Circuit Breaker가 적용된 댓글 텍스트 검열
            CompletableFuture<ModerationResult> resultFuture = textModerationService.moderateText(comment.getContent());
            ModerationResult result = resultFuture.get(); // 동기 처리

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
}