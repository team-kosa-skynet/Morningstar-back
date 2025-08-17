package com.gaebang.backend.domain.interview.controller;

import com.gaebang.backend.domain.interview.dto.request.KickoffRequestDto;
import com.gaebang.backend.domain.interview.dto.request.TurnRequestDto;
import com.gaebang.backend.domain.interview.dto.response.TurnResponseDto;
import com.gaebang.backend.domain.interview.service.TurnService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/interview")
@RequiredArgsConstructor
public class TurnController {

    private final TurnService turnService;

    @PostMapping("/turn")
    public TurnResponseDto turn(@RequestBody TurnRequestDto req) {
        return turnService.processTurn(req);
    }

    @PostMapping("/kickoff")
    public TurnResponseDto kickoff(@RequestBody KickoffRequestDto req) {
        return turnService.kickoff(req);
    }
}
