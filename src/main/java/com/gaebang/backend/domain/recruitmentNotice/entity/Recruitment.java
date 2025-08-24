package com.gaebang.backend.domain.recruitmentNotice.entity;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.gaebang.backend.global.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

@Entity
@Table(name = "recruitment")
@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class Recruitment extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long recruitmentId;

    @Column(name = "link")
    private String link;

    @Column(name = "company_name")
    private String companyName;

    @Column(name = "title")
    private String title;

    @Column(name = "technology_stack" , columnDefinition = "TEXT")
    private String technologyStack;

    @Column(name = "work_location")
    private String workLocation;

    @Column(name = "career_level")
    private String careerLevel;

    @Column(name = "work_type")
    private String workType;

    @Column(name = "education_level")
    private String educationLevel;

    @Column(name = "pub_date")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime pubDate;

    @Column(name = "expiration_date")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime expirationDate;

    // timestamp를 LocalDateTime으로 변환하여 pubDate 설정
    public void setPubDateFromTimestamp(String timestampStr) {
        if (timestampStr != null && !timestampStr.isEmpty()) {
            try {
                long timestamp = Long.parseLong(timestampStr);
                this.pubDate = LocalDateTime.ofInstant(
                        Instant.ofEpochSecond(timestamp),
                        ZoneId.systemDefault()
                );
            } catch (NumberFormatException e) {
                // 로그 처리 또는 기본값 설정
                this.pubDate = null;
            }
        }
    }

    // timestamp를 LocalDateTime으로 변환하여 expirationDate 설정
    public void setExpirationDateFromTimestamp(String timestampStr) {
        if (timestampStr != null && !timestampStr.isEmpty()) {
            try {
                long timestamp = Long.parseLong(timestampStr);
                this.expirationDate = LocalDateTime.ofInstant(
                        Instant.ofEpochSecond(timestamp),
                        ZoneId.systemDefault()
                );
            } catch (NumberFormatException e) {
                // 로그 처리 또는 기본값 설정
                this.expirationDate = null;
            }
        }
    }

}
