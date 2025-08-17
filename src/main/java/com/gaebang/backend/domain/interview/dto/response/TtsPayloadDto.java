package com.gaebang.backend.domain.interview.dto.response;

public record TtsPayloadDto(
        String format,   // "mp3" | "wav" | "ogg" (서버가 실제 인코딩한 포맷)
        String base64    // 오디오 바이트의 Base64 문자열
) {
}
