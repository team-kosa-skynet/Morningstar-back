package com.gaebang.backend.domain.pointTier.service;

import com.gaebang.backend.domain.pointTier.entity.PointTier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import com.gaebang.backend.domain.pointTier.repository.PointTierRepository;

@Service
@RequiredArgsConstructor
public class PointTierService {

    private final PointTierRepository pointTierRepository;

    //포인트에 해당하는 등급 찾기
    public PointTier getTierByPoints(int points) {
        return pointTierRepository.findTierByPoints(points)
                .orElseThrow(() -> new IllegalStateException("해당 포인트에 맞는 등급을 찾을 수 없습니다: " + points));
    }

}
