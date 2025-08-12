package com.gaebang.backend.domain.interviewTurn.controller;

import com.gaebang.backend.domain.interviewTurn.dto.request.StartSessionRequestDto;
import com.gaebang.backend.domain.interviewTurn.dto.request.TurnRequestDto;
import com.gaebang.backend.domain.interviewTurn.dto.response.NextTurnResponseDto;
import com.gaebang.backend.domain.interviewTurn.dto.response.StartSessionResponseDto;
import com.gaebang.backend.domain.interviewTurn.service.InterviewsService;
import com.gaebang.backend.global.springsecurity.PrincipalDetails;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/interviews")
public class InterviewsController {

    private final InterviewsService service;

    public InterviewsController(InterviewsService service) {
        this.service = service;
    }

    @PostMapping("/session")
    public ResponseEntity<StartSessionResponseDto> start(@Valid @RequestBody StartSessionRequestDto req,
                                                         @AuthenticationPrincipal PrincipalDetails principalDetails) throws Exception {
        Long memberId = principalDetails.getMember().getId();
        return ResponseEntity.ok(service.start(memberId, req));
    }

    @PostMapping("/turn")
    public ResponseEntity<NextTurnResponseDto> turn(@Valid @RequestBody TurnRequestDto req,
                                                    @AuthenticationPrincipal PrincipalDetails principalDetails) throws Exception {
        Long memberId = principalDetails.getMember().getId();
        return ResponseEntity.ok(service.nextTurn(req, memberId));
    }
}
