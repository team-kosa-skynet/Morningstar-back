package com.gaebang.backend.domain.community.repository;

import com.gaebang.backend.domain.community.entity.CommentBackup;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CommentBackupRepository extends JpaRepository<CommentBackup, Long> {
    CommentBackup findByCommentId(Long commentId);
}