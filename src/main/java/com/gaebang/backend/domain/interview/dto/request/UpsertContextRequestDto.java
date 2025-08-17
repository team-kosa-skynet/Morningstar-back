package com.gaebang.backend.domain.interview.dto.request;

import java.util.List;
import java.util.UUID;

public record UpsertContextRequestDto(
        UUID sessionId,
        String role,                 // BACKEND/FRONTEND 등 (없으면 기존 유지)
        List<String> skills,
        List<DocItem> docs // 문서 텍스트(선택)
) {
    public record DocItem(
            String title,
            String text) {
    }
}
