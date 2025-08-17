package com.gaebang.backend.domain.recruitmentNotice.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.gaebang.backend.domain.recruitmentNotice.entity.Recruitment;
import java.time.LocalDateTime;

public record RecruitmentResponseDto(
    Long recruitmentId,
    String companyName,
    String title,
    String technologyStack,
    String workLocation,
    String careerLevel,
    String workType,
    String educationLevel,
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    LocalDateTime pubDate,
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    LocalDateTime expirationDate,
    String link
) {

    public static RecruitmentResponseDto fromEntity(Recruitment recruitment) {
        return new RecruitmentResponseDto(
                recruitment.getRecruitmentId(),
                recruitment.getCompanyName(),
                recruitment.getTitle(),
                recruitment.getTechnologyStack(),
                recruitment.getWorkLocation(),
                recruitment.getCareerLevel(),
                recruitment.getWorkType(),
                recruitment.getEducationLevel(),
                recruitment.getPubDate(),
                recruitment.getExpirationDate(),
                recruitment.getLink()
        );
    }

}
