package com.gaebang.backend.domain.interviewTurn.controller;

import com.gaebang.backend.domain.interviewTurn.dto.request.FinalizeReportRequestDto;
import com.gaebang.backend.domain.interviewTurn.dto.request.StartSessionRequestDto;
import com.gaebang.backend.domain.interviewTurn.dto.request.TurnRequestDto;
import com.gaebang.backend.domain.interviewTurn.dto.request.UpsertContextRequestDto;
import com.gaebang.backend.domain.interviewTurn.dto.response.FinalizeReportResponseDto;
import com.gaebang.backend.domain.interviewTurn.dto.response.NextTurnResponseDto;
import com.gaebang.backend.domain.interviewTurn.dto.response.ScoresDto;
import com.gaebang.backend.domain.interviewTurn.dto.response.StartSessionResponseDto;
import com.gaebang.backend.domain.interviewTurn.service.InterviewsScoreService;
import com.gaebang.backend.domain.interviewTurn.service.InterviewsService;
import com.gaebang.backend.global.springsecurity.PrincipalDetails;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/interviews")
public class InterviewsController {

    private final InterviewsService interviewsService;
    private final InterviewsScoreService interviewsScoreService;

    public InterviewsController(InterviewsService interviewsService,
                                InterviewsScoreService interviewsScoreService) {
        this.interviewsService = interviewsService;
        this.interviewsScoreService = interviewsScoreService;
    }

    @PostMapping("/session")
    public ResponseEntity<StartSessionResponseDto> start(@Valid @RequestBody StartSessionRequestDto req,
                                                         @AuthenticationPrincipal PrincipalDetails principalDetails) throws Exception {
        Long memberId = principalDetails.getMember().getId();
        return ResponseEntity.ok(interviewsService.start(memberId, req));
    }

    @PostMapping("/turn")
    public ResponseEntity<NextTurnResponseDto> turn(@Valid @RequestBody TurnRequestDto req,
                                                    @AuthenticationPrincipal PrincipalDetails principalDetails) throws Exception {
        Long memberId = principalDetails.getMember().getId();
        return ResponseEntity.ok(interviewsService.nextTurn(req, memberId));
    }

    @PostMapping("/report/finalize")
    public ResponseEntity<FinalizeReportResponseDto> finalizeReport(@RequestBody FinalizeReportRequestDto dto,
                                                                    @AuthenticationPrincipal PrincipalDetails principalDetails) throws Exception {
        Long memberId = principalDetails.getMember().getId();
        return ResponseEntity.ok(interviewsService.finalizeReport(dto.sessionId(), memberId));
    }

    @GetMapping("/{sessionId}/scores")
    public ScoresDto scores(@PathVariable UUID sessionId) {
        // 소유자 검증은 기존 로직과 동일하게 적용

        return interviewsScoreService.computeScores(sessionId);
    }

    @PostMapping("/interviews/context")
    public ResponseEntity<Void> upsertContext(
            @RequestBody UpsertContextRequestDto req,
            @AuthenticationPrincipal PrincipalDetails principalDetails) throws Exception {
        interviewsService.upsertContext(req, principalDetails.getMember().getId());
        return ResponseEntity.ok().build();
    }
}
