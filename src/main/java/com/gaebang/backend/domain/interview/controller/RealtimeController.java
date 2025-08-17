package com.gaebang.backend.domain.interview.controller;

import com.gaebang.backend.domain.interview.dto.request.CreateRealtimeSessionRequestDto;
import com.gaebang.backend.domain.interview.dto.response.CreateRealtimeSessionResponseDto;
import com.gaebang.backend.domain.interview.service.RealtimeSessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/realtime")
@RequiredArgsConstructor
public class RealtimeController {

    private final RealtimeSessionService realtimeSessionService;

    @PostMapping("/session")
    public ResponseEntity<CreateRealtimeSessionResponseDto> createSession(
            @RequestBody CreateRealtimeSessionRequestDto req
    ) {

        CreateRealtimeSessionResponseDto res = realtimeSessionService.createEphemeral(
                req.jobPosition(),
                req.userId(),
                req.instructions()
        );

        return ResponseEntity.ok(res);
    }

}
