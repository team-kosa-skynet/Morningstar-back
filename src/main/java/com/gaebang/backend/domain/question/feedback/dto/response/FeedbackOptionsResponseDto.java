package com.gaebang.backend.domain.question.feedback.dto.response;

import com.gaebang.backend.domain.question.feedback.entity.FeedbackCategory;
import lombok.Builder;

import java.util.Arrays;
import java.util.List;

@Builder
public record FeedbackOptionsResponseDto(
        String modelName,
        List<FeedbackOptionDto> positiveOptions,
        List<FeedbackOptionDto> negativeOptions
) {
    
    public static FeedbackOptionsResponseDto create(String modelName) {
        List<FeedbackOptionDto> positiveOptions = Arrays.stream(FeedbackCategory.values())
                .filter(category -> category.getFeedbackType() == FeedbackCategory.FeedbackType.POSITIVE)
                .map(category -> new FeedbackOptionDto(category.name(), category.getDisplayName()))
                .toList();
        
        List<FeedbackOptionDto> negativeOptions = Arrays.stream(FeedbackCategory.values())
                .filter(category -> category.getFeedbackType() == FeedbackCategory.FeedbackType.NEGATIVE)
                .map(category -> new FeedbackOptionDto(category.name(), category.getDisplayName()))
                .toList();
        
        return FeedbackOptionsResponseDto.builder()
                .modelName(modelName)
                .positiveOptions(positiveOptions)
                .negativeOptions(negativeOptions)
                .build();
    }
    
    @Builder
    public record FeedbackOptionDto(
            String code,
            String displayName
    ) {}
}