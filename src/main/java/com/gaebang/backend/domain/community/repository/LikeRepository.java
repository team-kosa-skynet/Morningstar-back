package com.gaebang.backend.domain.community.repository;

import com.gaebang.backend.domain.community.entity.BoardLike;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LikeRepository extends JpaRepository<BoardLike, Long> {
}
