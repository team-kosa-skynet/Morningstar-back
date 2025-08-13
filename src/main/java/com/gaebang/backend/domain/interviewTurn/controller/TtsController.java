package com.gaebang.backend.domain.interviewTurn.controller;

import com.gaebang.backend.domain.interviewTurn.dto.request.TtsRequestDto;
import com.gaebang.backend.domain.interviewTurn.llm.TtsGateway;
import com.gaebang.backend.domain.interviewTurn.service.TtsService;
import com.google.cloud.texttospeech.v1.ListVoicesRequest;
import com.google.cloud.texttospeech.v1.ListVoicesResponse;
import com.google.cloud.texttospeech.v1.TextToSpeechClient;
import com.google.cloud.texttospeech.v1.Voice;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RequestMapping("/api/interview")
@RestController
public class TtsController {

    private final TtsGateway ttsGateway;

    public TtsController(TtsGateway ttsGateway) {
        this.ttsGateway = ttsGateway;
    }

    // 간단 요청 DTO
    public static record TtsRequest(
            @NotBlank String text,
            String voice,   // 예: "ko-KR-Standard-A"
            String format   // "mp3" | "wav" | "ogg"
    ) {}

    @PostMapping("/tts")
    public ResponseEntity<byte[]> synthesize(@RequestBody TtsRequest req) throws Exception {
        String wantFormat = (req.format() == null || req.format().isBlank()) ? "mp3" : req.format();
        byte[] audio = ttsGateway.synthesize(req.text(), req.voice(), wantFormat);

        String contentType = switch (wantFormat.toLowerCase()) {
            case "wav", "linear16" -> "audio/wav";
            case "ogg", "ogg_opus" -> "audio/ogg";
            default -> "audio/mpeg";
        };

        HttpHeaders h = new HttpHeaders();
        h.set(HttpHeaders.CONTENT_TYPE, contentType);
        h.set(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"speech." + wantFormat + "\"");
        return new ResponseEntity<>(audio, h, HttpStatus.OK);
    }
}
