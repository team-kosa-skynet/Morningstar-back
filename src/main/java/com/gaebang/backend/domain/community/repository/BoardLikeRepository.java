package com.gaebang.backend.domain.community.repository;

import com.gaebang.backend.domain.community.entity.BoardLike;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface BoardLikeRepository extends JpaRepository<BoardLike, Long> {

    Long countByBoardId(Long boardId);

    Optional<BoardLike> findByBoardIdAndMemberId(Long boardId, Long memberId);
}
