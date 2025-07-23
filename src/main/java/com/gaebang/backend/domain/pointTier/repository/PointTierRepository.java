package com.gaebang.backend.domain.pointTier.repository;

import com.gaebang.backend.domain.pointTier.entity.PointTier;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PointTierRepository extends JpaRepository<PointTier, Long> {

    // 포인트에 해당하는 등급 찾기
    @Query("SELECT t FROM PointTier t WHERE :points >= t.minPoint AND " +
            "(:points <= t.maxPoint OR t.maxPoint IS NULL) " +
            "ORDER BY t.tierOrder DESC LIMIT 1")
    Optional<PointTier> findTierByPoints(@Param("points") int points);

    // 등급 순서로 정렬하여 모든 등급 조회
    List<PointTier> findAllByOrderByTierOrderAsc();

}
