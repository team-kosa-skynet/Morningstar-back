package com.gaebang.backend.domain.interview.controller;

import com.gaebang.backend.domain.interview.dto.request.FinalizeReportRequestDto;
import com.gaebang.backend.domain.interview.dto.request.StartSessionRequestDto;
import com.gaebang.backend.domain.interview.dto.request.TurnRequestDto;
import com.gaebang.backend.domain.interview.dto.request.UpsertContextRequestDto;
import com.gaebang.backend.domain.interview.dto.response.FinalizeReportResponseDto;
import com.gaebang.backend.domain.interview.dto.response.NextTurnResponseDto;
import com.gaebang.backend.domain.interview.dto.response.ScoresDto;
import com.gaebang.backend.domain.interview.dto.response.StartSessionResponseDto;
import com.gaebang.backend.domain.interview.service.DocumentParsingService;
import com.gaebang.backend.domain.interview.service.InterviewScoreService;
import com.gaebang.backend.domain.interview.service.InterviewService;
import com.gaebang.backend.global.springsecurity.PrincipalDetails;
import com.gaebang.backend.global.util.ResponseDTO;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@RestController
@RequestMapping("/api/interview")
public class InterviewController {

    private final InterviewService interviewService;
    private final InterviewScoreService interviewScoreService;
    private final DocumentParsingService documentParsingService;

    @Value("${interview.defaults.with-audio:true}")
    private boolean defaultWithAudio;

    public InterviewController(InterviewService interviewService,
                               InterviewScoreService interviewScoreService,
                               DocumentParsingService documentParsingService) {
        this.interviewService = interviewService;
        this.interviewScoreService = interviewScoreService;
        this.documentParsingService = documentParsingService;
    }

    @PostMapping("/session")
    public ResponseEntity<ResponseDTO<StartSessionResponseDto>> start(@Valid @RequestBody StartSessionRequestDto req,
                                                                       @AuthenticationPrincipal PrincipalDetails principalDetails,
                                                                       @RequestParam(required = false) Boolean withAudio
    ) throws Exception {
        Long memberId = principalDetails.getMember().getId();
        boolean includeAudio = (withAudio != null) ? withAudio : defaultWithAudio;
        StartSessionResponseDto result = interviewService.start(memberId, req, includeAudio);
        ResponseDTO<StartSessionResponseDto> response = ResponseDTO.okWithData(result, "면접 세션이 성공적으로 시작되었습니다.");
        return ResponseEntity.status(response.getCode()).body(response);
    }

    @PostMapping("/turn")
    public ResponseEntity<ResponseDTO<NextTurnResponseDto>> turn(@Valid @RequestBody TurnRequestDto req,
                                                                 @AuthenticationPrincipal PrincipalDetails principalDetails,
                                                                 @RequestParam(required = false) Boolean withAudio
    ) throws Exception {
        Long memberId = principalDetails.getMember().getId();
        boolean includeAudio = (withAudio != null) ? withAudio : defaultWithAudio;
        NextTurnResponseDto result = interviewService.nextTurn(req, memberId, includeAudio);
        ResponseDTO<NextTurnResponseDto> response = ResponseDTO.okWithData(result, "면접 답변이 성공적으로 처리되었습니다.");
        return ResponseEntity.status(response.getCode()).body(response);
    }

    @PostMapping("/report/finalize")
    public ResponseEntity<ResponseDTO<FinalizeReportResponseDto>> finalizeReport(@RequestBody FinalizeReportRequestDto dto,
                                                                                 @AuthenticationPrincipal PrincipalDetails principalDetails) throws Exception {
        Long memberId = principalDetails.getMember().getId();
        FinalizeReportResponseDto result = interviewService.finalizeReport(dto.sessionId(), memberId);
        ResponseDTO<FinalizeReportResponseDto> response = ResponseDTO.okWithData(result, "면접 보고서가 성공적으로 생성되었습니다.");
        return ResponseEntity.status(response.getCode()).body(response);
    }

    @GetMapping("/{sessionId}/scores")
    public ResponseEntity<ResponseDTO<ScoresDto>> scores(@PathVariable UUID sessionId) {
        ScoresDto result = interviewScoreService.computeScores(sessionId);
        ResponseDTO<ScoresDto> response = ResponseDTO.okWithData(result, "점수 조회가 성공적으로 완료되었습니다.");
        return ResponseEntity.status(response.getCode()).body(response);
    }

    @PostMapping("/context")
    public ResponseEntity<ResponseDTO<Void>> upsertContext(
            @RequestBody UpsertContextRequestDto req,
            @AuthenticationPrincipal PrincipalDetails principalDetails) throws Exception {
        interviewService.upsertContext(req, principalDetails.getMember().getId());
        ResponseDTO<Void> response = ResponseDTO.okWithMessage("면접 컨텍스트가 성공적으로 업데이트되었습니다.");
        return ResponseEntity.status(response.getCode()).body(response);
    }

    @PostMapping("/documents/parse")
    public ResponseEntity<ResponseDTO<String>> parseDocument(
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal PrincipalDetails principalDetails) throws Exception {
        
        // 컨트롤러에서 파일 검증
        validateFile(file);
        
        Long memberId = principalDetails.getMember().getId();
        String documentId = documentParsingService.parseAndSaveDocument(file, memberId);
        ResponseDTO<String> response = ResponseDTO.okWithData(documentId, "문서가 성공적으로 파싱되었습니다.");
        return ResponseEntity.status(response.getCode()).body(response);
    }

    private void validateFile(MultipartFile file) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("파일이 비어있습니다.");
        }
        
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null) {
            throw new IllegalArgumentException("파일명이 없습니다.");
        }
        
        // 파일 크기 체크 (예: 50MB)
        long maxSize = 50 * 1024 * 1024; // 50MB
        if (file.getSize() > maxSize) {
            throw new IllegalArgumentException("파일 크기가 너무 큽니다. (최대 50MB)");
        }
        
        // 지원 파일 형식 체크
        String extension = getFileExtension(originalFilename);
        if (!isSupportedFileType(extension)) {
            throw new IllegalArgumentException("지원하지 않는 파일 형식입니다. (.pdf, .docx, .txt만 지원)");
        }
    }

    private String getFileExtension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "";
        }
        return filename.substring(filename.lastIndexOf(".") + 1).toLowerCase();
    }

    private boolean isSupportedFileType(String extension) {
        return extension.equals("pdf") || 
               extension.equals("docx") || 
               extension.equals("txt");
    }
}
