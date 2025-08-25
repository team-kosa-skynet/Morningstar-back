package com.gaebang.backend.domain.question.common.entity;

import com.gaebang.backend.global.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * AI 모델 정보 엔티티
 * 각 AI 제공업체별 지원 모델과 기능을 관리
 */
@Entity
@Table(name = "ai_models")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AiModel extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "model_id")
    private Long modelId;

    /**
     * AI 제공업체 (claude, gemini, openai)
     */
    @Column(nullable = false, length = 20)
    private String provider;

    /**
     * 모델명 (claude-3-haiku-20240307, gpt-4o 등)
     */
    @Column(name = "model_name", nullable = false, length = 100)
    private String modelName;

    /**
     * 파일 업로드 지원 여부
     */
    @Column(name = "supports_files", nullable = false)
    private Boolean supportsFiles;

    /**
     * 해당 제공업체의 기본 모델 여부
     */
    @Column(name = "is_default", nullable = false)
    private Boolean isDefault = false;

    /**
     * 활성화 상태 (비활성화된 모델은 API에서 제외)
     */
    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    /**
     * 이미지 생성 지원 여부
     */
    @Column(name = "is_create_image", nullable = false)
    private Boolean isCreateImage = false;

    @Builder
    public AiModel(String provider, String modelName, Boolean supportsFiles, Boolean isDefault, Boolean isActive, Boolean isCreateImage) {
        this.provider = provider;
        this.modelName = modelName;
        this.supportsFiles = supportsFiles;
        this.isDefault = isDefault != null ? isDefault : false;
        this.isActive = isActive != null ? isActive : true;
        this.isCreateImage = isCreateImage != null ? isCreateImage : false;
    }

    /**
     * 모델 활성화/비활성화
     */
    public void updateActiveStatus(Boolean isActive) {
        this.isActive = isActive;
    }

    /**
     * 기본 모델 설정/해제
     */
    public void updateDefaultStatus(Boolean isDefault) {
        this.isDefault = isDefault;
    }

    /**
     * 파일 지원 여부 업데이트
     */
    public void updateFileSupport(Boolean supportsFiles) {
        this.supportsFiles = supportsFiles;
    }

    /**
     * 이미지 생성 지원 여부 업데이트
     */
    public void updateImageCreateSupport(Boolean isCreateImage) {
        this.isCreateImage = isCreateImage;
    }
}