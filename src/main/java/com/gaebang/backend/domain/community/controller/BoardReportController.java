package com.gaebang.backend.domain.community.controller;

import com.gaebang.backend.domain.community.dto.reqeust.BoardReportRequestDto;
import com.gaebang.backend.domain.community.dto.response.BoardReportResponseDto;
import com.gaebang.backend.domain.community.service.BoardReportService;
import com.gaebang.backend.global.springsecurity.PrincipalDetails;
import com.gaebang.backend.global.util.ResponseDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RequiredArgsConstructor
@RequestMapping("/api")
@RestController
public class BoardReportController {

    private final BoardReportService boardReportService;

    @PostMapping("/boards/report")
    public ResponseEntity<ResponseDTO<Void>> createBoardReport(@RequestBody BoardReportRequestDto boardReportRequestDto,
                                                            @AuthenticationPrincipal PrincipalDetails principalDetails) {

        boardReportService.createBoardReport(boardReportRequestDto, principalDetails);

        ResponseDTO<Void> response = ResponseDTO.ok();
        return ResponseEntity
                .status(response.getCode())
                .build();
    }

    @GetMapping("/boards/report")
    public ResponseEntity<ResponseDTO<Page<BoardReportResponseDto>>> getBoardReports(Pageable pageable) {

        Page<BoardReportResponseDto> boardReportsDto = boardReportService.getBoardReports(pageable);

        ResponseDTO<Page<BoardReportResponseDto>> response = ResponseDTO.okWithData(boardReportsDto);
        return ResponseEntity
                .status(response.getCode())
                .body(response);
    }

    @DeleteMapping("/boards/report/{reportId}")
    public ResponseEntity<ResponseDTO<Void>> deleteBoardReport(@PathVariable Long reportId) {
        boardReportService.deleteBoardReport(reportId);

        ResponseDTO<Void> response = ResponseDTO.ok();

        return ResponseEntity
                .status(response.getCode())
                .build();
    }

}
