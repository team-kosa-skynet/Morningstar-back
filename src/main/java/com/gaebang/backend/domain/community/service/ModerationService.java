package com.gaebang.backend.domain.community.service;

import com.gaebang.backend.domain.community.dto.ModerationResult;
import com.gaebang.backend.domain.community.entity.Board;
import com.gaebang.backend.domain.community.entity.Comment;
import com.gaebang.backend.domain.community.entity.Image;
import com.gaebang.backend.domain.community.repository.BoardRepository;
import com.gaebang.backend.domain.community.repository.CommentRepository;
import com.gaebang.backend.domain.community.repository.ImageRepository;
import com.gaebang.backend.global.util.S3.S3ImageService;
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
    private final ImageRepository imageRepository;
    private final ContentBackupService contentBackupService;
    private final TextModerationService textModerationService;
    private final ImageModerationService imageModerationService;
    private final S3ImageService s3ImageService;

    @Value("${moderation.enabled:true}")
    private boolean moderationEnabled;

    @Value("${moderation.censorship.title-replacement:검열된 게시글입니다}")
    private String censoredTitle;

    @Value("${moderation.censorship.content-template:이 게시글은 부적절한 내용으로 인해 검열되었습니다.\n\n검열 사유: {reason}\n문의: admin@morningstar.com}")
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

            CompletableFuture<ModerationResult> textResultFuture = textModerationService.moderateTitleAndContent(
                board.getTitle(), board.getContent());
            
            ModerationResult textResult = textResultFuture.get();
            
            boolean textCensored = false;
            boolean imageCensored = false;
            String censorReason = null;
            
            // 텍스트 검열 결과 처리
            if (textResult.isInappropriate()) {
                log.info("부적절한 게시글 텍스트 발견 - ID: {}, 사유: {}", boardId, textResult.getReason());
                textCensored = true;
                censorReason = textResult.getReason();
            }

            // 이미지 검열 실행
            
            if (!board.getImages().isEmpty()) {
                log.info("게시글 이미지 검열 시작 - ID: {}, 이미지 수: {}", boardId, board.getImages().size());
                
                for (Image image : board.getImages()) {
                    if (imageModerationService.isSupportedImageFormat(image.getImageUrl())) {
                        CompletableFuture<ModerationResult> imageResultFuture = 
                            imageModerationService.moderateImage(image.getImageUrl());
                        
                        ModerationResult imageResult = imageResultFuture.get();
                        
                        if (imageResult.isInappropriate()) {
                            log.info("부적절한 게시글 이미지 발견 - ID: {}, 이미지 URL: {}, 사유: {}", 
                                    boardId, image.getImageUrl(), imageResult.getReason());
                            
                            imageCensored = true;
                            if (censorReason == null) {
                                censorReason = "이미지 검열: " + imageResult.getReason();
                            } else {
                                censorReason += " / 이미지 검열: " + imageResult.getReason();
                            }
                            
                            // 모든 이미지 삭제 (S3 + DB)
                            deleteAllImagesFromBoard(board);
                            break; // 하나의 이미지라도 부적절하면 모든 이미지 삭제 후 종료
                        }
                    } else {
                        log.debug("지원하지 않는 이미지 형식 - ID: {}, URL: {}", boardId, image.getImageUrl());
                    }
                }
                
                if (imageCensored) {
                    log.info("게시글 이미지 검열 완료 - ID: {}", boardId);
                } else {
                    log.debug("게시글 이미지 검열 통과 - ID: {}", boardId);
                }
            }
            
            // 최종 처리: 텍스트 또는 이미지 중 하나라도 부적절하면 검열 처리
            if (textCensored || imageCensored) {
                // 백업 생성 (중복 방지)
                contentBackupService.createBoardBackup(board, censorReason);
                
                // 게시글 내용을 검열 메시지로 교체
                String censoredContent = contentTemplate.replace("{reason}", censorReason);
                board.censorContent(censoredTitle, censoredContent);
                boardRepository.save(board);
                
                log.info("게시글 검열 완료 - ID: {}, 텍스트 검열: {}, 이미지 검열: {}", 
                        boardId, textCensored, imageCensored);
            } else {
                // 모든 검열 통과
                board.approveModerationContent();
                boardRepository.save(board);
                log.debug("게시글 전체 검열 통과 - ID: {}", boardId);
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

            CompletableFuture<ModerationResult> resultFuture = textModerationService.moderateText(comment.getContent());
            ModerationResult result = resultFuture.get();

            if (result.isInappropriate()) {
                log.info("부적절한 댓글 발견 - ID: {}, 사유: {}", commentId, result.getReason());
                
                contentBackupService.createCommentBackup(comment, result.getReason());
                
                String censoredContent = "이 댓글은 부적절한 내용으로 인해 검열되었습니다.\n\n검열 사유: " + 
                                       result.getReason() + "\n문의: admin@morningstar.com";
                comment.censorContent(censoredContent);
                commentRepository.save(comment);
                
                log.info("댓글 검열 완료 - ID: {}", commentId);
            } else {
                comment.approveModerationContent();
                commentRepository.save(comment);
                log.debug("댓글 검열 통과 - ID: {}", commentId);
            }

        } catch (Exception e) {
            log.error("댓글 검열 중 오류 발생 - ID: {}, 오류: {}", commentId, e.getMessage(), e);
        }

        return CompletableFuture.completedFuture(null);
    }

    /**
     * 게시글의 모든 이미지를 S3와 DB에서 삭제
     * @param board 게시글 엔티티
     */
    private void deleteAllImagesFromBoard(Board board) {
        if (board.getImages().isEmpty()) {
            return;
        }

        for (Image image : board.getImages()) {
            try {
                // S3에서 이미지 삭제
                s3ImageService.deleteImageFromS3(image.getImageUrl());
                log.debug("S3 이미지 삭제 완료 - URL: {}", image.getImageUrl());
            } catch (Exception e) {
                log.error("S3 이미지 삭제 실패 - URL: {}, 오류: {}", image.getImageUrl(), e.getMessage());
                // S3 삭제 실패해도 DB에서는 삭제 진행
            }
        }

        // DB에서 이미지 엔티티들 삭제
        try {
            imageRepository.deleteAll(board.getImages());
            log.debug("DB 이미지 엔티티 삭제 완료 - 게시글 ID: {}, 삭제된 이미지 수: {}", 
                     board.getId(), board.getImages().size());
        } catch (Exception e) {
            log.error("DB 이미지 엔티티 삭제 실패 - 게시글 ID: {}, 오류: {}", board.getId(), e.getMessage());
        }
    }
}