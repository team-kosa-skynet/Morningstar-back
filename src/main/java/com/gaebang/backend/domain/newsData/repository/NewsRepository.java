package com.gaebang.backend.domain.newsData.repository;

import com.gaebang.backend.domain.newsData.entity.NewsData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface NewsRepository extends JpaRepository<NewsData, Long> {

    // 중복 체크용 (서비스에서 사용)
    // boolean existsByLink(String link);
    @Query(value = "SELECT COUNT(*) FROM news WHERE link = ?1", nativeQuery = true)
    Long countExistingByLink(String link);

    // 특정 기간 뉴스 조회
    List<NewsData> findByPubDateBetween(LocalDateTime start, LocalDateTime end);

    // 최신 뉴스 조회
    List<NewsData> findTop100ByOrderByPubDateDesc();
}