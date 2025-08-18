package com.gaebang.backend.domain.interview.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.multipart.MultipartFile;

public record FileUploadRequestDto(
        @NotNull MultipartFile file,
        @NotBlank String fileName,
        String description
) {
}