package com.gaebang.backend.domain.question.gemini.dto.response;

import java.util.List;

public record Content(
        List<Part> parts,
        String role
) {
}