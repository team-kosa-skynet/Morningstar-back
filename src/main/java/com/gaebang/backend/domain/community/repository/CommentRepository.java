package com.gaebang.backend.domain.community.repository;

import com.gaebang.backend.domain.community.entity.Comment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CommentRepository extends JpaRepository<Comment, Long> {

    Page<Comment> findByBoardIdAndDeleteYnOrderByCreatedAtDesc(Long boardId, String deleteYn, Pageable pageable);

    Optional<Comment> findByIdAndBoardIdAndDeleteYn(Long commentId, Long boardId, String deleteYn);

    Long countByBoardIdAndDeleteYn(Long boardId, String deleteYn);

    Optional<Comment> findByIdAndMemberIdAndDeleteYn(Long commentId, Long memberId, String deleteYn);
}
