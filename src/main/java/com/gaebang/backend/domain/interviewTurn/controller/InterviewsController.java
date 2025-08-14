package com.gaebang.backend.domain.interviewTurn.controller;

import com.gaebang.backend.domain.interviewTurn.dto.request.FinalizeReportRequestDto;
import com.gaebang.backend.domain.interviewTurn.dto.request.StartSessionRequestDto;
import com.gaebang.backend.domain.interviewTurn.dto.request.TurnRequestDto;
import com.gaebang.backend.domain.interviewTurn.dto.request.UpsertContextRequestDto;
import com.gaebang.backend.domain.interviewTurn.dto.response.FinalizeReportResponseDto;
import com.gaebang.backend.domain.interviewTurn.dto.response.NextTurnResponseDto;
import com.gaebang.backend.domain.interviewTurn.dto.response.ScoresDto;
import com.gaebang.backend.domain.interviewTurn.dto.response.StartSessionResponseDto;
import com.gaebang.backend.domain.interviewTurn.service.DocumentParsingService;
import com.gaebang.backend.domain.interviewTurn.service.InterviewsScoreService;
import com.gaebang.backend.domain.interviewTurn.service.InterviewsService;
import com.gaebang.backend.global.springsecurity.PrincipalDetails;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@RestController
@RequestMapping("/api/interviews")
public class InterviewsController {

    private final InterviewsService interviewsService;
    private final InterviewsScoreService interviewsScoreService;
    private final DocumentParsingService documentParsingService;

    @Value("${interview.defaults.with-audio:true}")
    private boolean defaultWithAudio;

    public InterviewsController(InterviewsService interviewsService,
                                InterviewsScoreService interviewsScoreService,
                                DocumentParsingService documentParsingService) {
        this.interviewsService = interviewsService;
        this.interviewsScoreService = interviewsScoreService;
        this.documentParsingService = documentParsingService;
    }

    @PostMapping("/session")
    public ResponseEntity<StartSessionResponseDto> start(@Valid @RequestBody StartSessionRequestDto req,
                                                         @AuthenticationPrincipal PrincipalDetails principalDetails,
                                                         @RequestParam(required = false) Boolean withAudio
    ) throws Exception {
        Long memberId = principalDetails.getMember().getId();
        boolean includeAudio = (withAudio != null) ? withAudio : defaultWithAudio;
        return ResponseEntity.ok(interviewsService.start(memberId, req, includeAudio));
    }

    @PostMapping("/turn")
    public ResponseEntity<NextTurnResponseDto> turn(@Valid @RequestBody TurnRequestDto req,
                                                    @AuthenticationPrincipal PrincipalDetails principalDetails,
                                                    @RequestParam(required = false) Boolean withAudio
    ) throws Exception {
        Long memberId = principalDetails.getMember().getId();
        boolean includeAudio = (withAudio != null) ? withAudio : defaultWithAudio;
        return ResponseEntity.ok(interviewsService.nextTurn(req, memberId, includeAudio));
    }

    @PostMapping("/report/finalize")
    public ResponseEntity<FinalizeReportResponseDto> finalizeReport(@RequestBody FinalizeReportRequestDto dto,
                                                                    @AuthenticationPrincipal PrincipalDetails principalDetails) throws Exception {
        Long memberId = principalDetails.getMember().getId();
        return ResponseEntity.ok(interviewsService.finalizeReport(dto.sessionId(), memberId));
    }

    @GetMapping("/{sessionId}/scores")
    public ScoresDto scores(@PathVariable UUID sessionId) {
        return interviewsScoreService.computeScores(sessionId);
    }

    @PostMapping("/context")
    public ResponseEntity<Void> upsertContext(
            @RequestBody UpsertContextRequestDto req,
            @AuthenticationPrincipal PrincipalDetails principalDetails) throws Exception {
        interviewsService.upsertContext(req, principalDetails.getMember().getId());
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
