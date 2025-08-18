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
    public ResponseEntity<StartSessionResponseDto> start(@Valid @RequestBody StartSessionRequestDto req,
                                                         @AuthenticationPrincipal PrincipalDetails principalDetails,
                                                         @RequestParam(required = false) Boolean withAudio
    ) throws Exception {
        Long memberId = principalDetails.getMember().getId();
        boolean includeAudio = (withAudio != null) ? withAudio : defaultWithAudio;
        return ResponseEntity.ok(interviewService.start(memberId, req, includeAudio));
    }

    @PostMapping("/turn")
    public ResponseEntity<NextTurnResponseDto> turn(@Valid @RequestBody TurnRequestDto req,
                                                    @AuthenticationPrincipal PrincipalDetails principalDetails,
                                                    @RequestParam(required = false) Boolean withAudio
    ) throws Exception {
        Long memberId = principalDetails.getMember().getId();
        boolean includeAudio = (withAudio != null) ? withAudio : defaultWithAudio;
        return ResponseEntity.ok(interviewService.nextTurn(req, memberId, includeAudio));
    }

    @PostMapping("/report/finalize")
    public ResponseEntity<FinalizeReportResponseDto> finalizeReport(@RequestBody FinalizeReportRequestDto dto,
                                                                    @AuthenticationPrincipal PrincipalDetails principalDetails) throws Exception {
        Long memberId = principalDetails.getMember().getId();
        return ResponseEntity.ok(interviewService.finalizeReport(dto.sessionId(), memberId));
    }

    @GetMapping("/{sessionId}/scores")
    public ScoresDto scores(@PathVariable UUID sessionId) {
        return interviewScoreService.computeScores(sessionId);
    }

    @PostMapping("/context")
    public ResponseEntity<Void> upsertContext(
            @RequestBody UpsertContextRequestDto req,
            @AuthenticationPrincipal PrincipalDetails principalDetails) throws Exception {
        interviewService.upsertContext(req, principalDetails.getMember().getId());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/documents/parse")
    public ResponseEntity<String> parseDocument(
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal PrincipalDetails principalDetails) throws Exception {
        
        // 컨트롤러에서 파일 검증
        validateFile(file);
        
        Long memberId = principalDetails.getMember().getId();
        String documentId = documentParsingService.parseAndSaveDocument(file, memberId);
        return ResponseEntity.ok(documentId);
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
