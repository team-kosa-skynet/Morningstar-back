package com.gaebang.backend.domain.community.repository;

import com.gaebang.backend.domain.community.entity.Image;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ImageRepository extends JpaRepository<Image, Long> {

    List<Image> findByBoardId(Long boardId);

    void deleteByBoardId(Long boardId);

}
