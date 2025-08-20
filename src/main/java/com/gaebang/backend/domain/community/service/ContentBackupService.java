package com.gaebang.backend.domain.community.service;

import com.gaebang.backend.domain.community.entity.*;
import com.gaebang.backend.domain.community.repository.BoardBackupRepository;
import com.gaebang.backend.domain.community.repository.CommentBackupRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@RequiredArgsConstructor
@Service
public class ContentBackupService {

    private final BoardBackupRepository boardBackupRepository;
    private final CommentBackupRepository commentBackupRepository;

    @Transactional
    public void createBoardBackup(Board board, String censorReason) {
        try {
            BoardBackup backup = BoardBackup.createBackup(
                board.getId(),
                board.getTitle(),
                board.getContent(),
                censorReason
            );
            
            boardBackupRepository.save(backup);
            log.info("게시글 백업 생성 완료 - Board ID: {}, Backup ID: {}", board.getId(), backup.getId());
            
        } catch (Exception e) {
            log.error("게시글 백업 생성 실패 - Board ID: {}, 오류: {}", board.getId(), e.getMessage(), e);
            throw e;
        }
    }

    @Transactional
    public void createCommentBackup(Comment comment, String censorReason) {
        try {
            CommentBackup backup = CommentBackup.createBackup(
                comment.getId(),
                comment.getContent(),
                censorReason
            );
            
            commentBackupRepository.save(backup);
            log.info("댓글 백업 생성 완료 - Comment ID: {}, Backup ID: {}", comment.getId(), backup.getId());
            
        } catch (Exception e) {
            log.error("댓글 백업 생성 실패 - Comment ID: {}, 오류: {}", comment.getId(), e.getMessage(), e);
            throw e;
        }
    }

    // 추후 복구 기능용 메서드 (현재는 구현하지 않음)
    public BoardBackup findBoardBackup(Long boardId) {
        return boardBackupRepository.findByBoardId(boardId);
    }

    public CommentBackup findCommentBackup(Long commentId) {
        return commentBackupRepository.findByCommentId(commentId);
    }
}