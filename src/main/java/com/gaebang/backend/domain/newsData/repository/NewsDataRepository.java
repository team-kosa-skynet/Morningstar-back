package com.gaebang.backend.domain.newsData.repository;

import com.gaebang.backend.domain.newsData.entity.NewsData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface NewsDataRepository extends JpaRepository<NewsData, Long> {

    // 중복 체크용 (서비스에서 사용)
    // boolean existsByLink(String link);
    @Query(value = "SELECT COUNT(*) FROM news WHERE link = ?1", nativeQuery = true)
    Long countExistingByLink(String link);

    // 특정 기간 뉴스 조회
    List<NewsData> findByPubDateBetween(LocalDateTime start, LocalDateTime end);

    // 최신 뉴스 조회
    @Query("SELECT n FROM NewsData n WHERE n.isActive = 1 ORDER BY n.pubDate DESC")
    List<NewsData> findAllActiveNewsOrderByPubDateDesc();

    // 또는 더 정확하게 24시간 이내
    @Query("SELECT n FROM NewsData n WHERE n.pubDate >= :startDate AND n.pubDate < :endDate ORDER BY n.pubDate DESC")
    List<NewsData> findNewsByDateRange(@Param("startDate") LocalDateTime startDate, @Param("endDate") LocalDateTime endDate);

    // isPopular를 1로 설정하는 메서드
    @Modifying
    @Query("UPDATE NewsData n SET n.isPopular = 1 WHERE n.newsId = :newsId")
    void markAsPopular(@Param("newsId") Long newsId);

    // isActive를 0로 설정하는 메서드
    @Modifying
    @Query("UPDATE NewsData n SET n.isActive = 0 WHERE n.newsId = :newsId")
    void markAsActive(@Param("newsId") Long newsId);
}