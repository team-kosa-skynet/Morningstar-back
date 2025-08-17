package com.gaebang.backend.domain.interview.dto.response;

import com.gaebang.backend.domain.interview.dto.IceServer;

import java.time.OffsetDateTime;
import java.util.List;

public record CreateRealtimeSessionResponseDto(
        String sessionId,              // 우리 세션 ID(UUID)
        String ephemeralKey,           // 브라우저가 WebRTC 연결에 쓸 임시 키
        OffsetDateTime expiresAt,      // 만료 시각
        List<IceServer> iceServers // WebRTC ICE 서버 정보(필요한 최소만)
) {
}
