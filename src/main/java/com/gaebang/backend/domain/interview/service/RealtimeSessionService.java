package com.gaebang.backend.domain.interview.service;

import com.gaebang.backend.domain.interview.client.OpenAiRealtimeClient;
import com.gaebang.backend.domain.interview.client.response.OpenAiRealtimeSessionResponseDto;
import com.gaebang.backend.domain.interview.dto.IceServer;
import com.gaebang.backend.domain.interview.dto.response.CreateRealtimeSessionResponseDto;
import com.gaebang.backend.domain.interview.entity.InterviewSession;
import com.gaebang.backend.domain.interview.repository.InterviewSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class RealtimeSessionService {

    private final InterviewSessionRepository sessionRepo;
    private final OpenAiRealtimeClient openAi;

    /**
     * 에페메랄 키 발급 + DB 세션 저장 + API 응답 DTO 리턴 (최소 구조)
     */
    public CreateRealtimeSessionResponseDto createEphemeral(String jobPosition, Long userId, String instructions) {
        String sys = (instructions == null || instructions.isBlank())
                ? """
                당신은 한국어 면접관입니다.
                - 친근하지만 전문적인 톤
                - 한 번에 하나의 질문만
                - 허구 정보 금지
                - 답변은 20~30초 내로 유도
                """
                : instructions;

        // 1) OpenAI 세션 생성
        OpenAiRealtimeSessionResponseDto oa = openAi.createSession(sys);

        // 2) 우리 DB 세션 생성 저장
        InterviewSession session = InterviewSession.startRealtime(
                "gpt-4o-realtime-preview",
                jobPosition,
                userId
        );
        session = sessionRepo.save(session);

        // 3) ICE 서버 매핑 (null 안전)
        List<IceServer> iceServers;
        if (oa.iceServers() == null) {
            iceServers = java.util.Collections.emptyList();
        } else {
            iceServers = oa.iceServers().stream()
                    .map(i -> new IceServer(i.urls(), i.username(), i.credential()))
                    .toList();
        }

        // 4) clientSecret null/필드 검증
        if (oa.clientSecret() == null
                || oa.clientSecret().value() == null
                || oa.clientSecret().expiresAt() == null) {
            throw new IllegalStateException("OpenAI가 유효한 에페메랄 키를 반환하지 않았습니다.");
        }

        // 5) API 응답
        return new CreateRealtimeSessionResponseDto(
                session.getId().toString(),
                oa.clientSecret().value(),
                oa.clientSecret().expiresAt(),
                iceServers
        );
    }
}