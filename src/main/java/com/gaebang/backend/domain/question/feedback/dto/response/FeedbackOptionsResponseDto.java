package com.gaebang.backend.domain.question.feedback.dto.response;

import com.gaebang.backend.domain.question.feedback.entity.FeedbackCategory;

import java.util.Arrays;
import java.util.List;

public record FeedbackOptionsResponseDto(
        List<FeedbackOptionDto> positiveOptions,
        List<FeedbackOptionDto> negativeOptions
) {
    
    public static FeedbackOptionsResponseDto create() {
        List<FeedbackOptionDto> positiveOptions = Arrays.stream(FeedbackCategory.values())
                .filter(category -> category.getFeedbackType() == FeedbackCategory.FeedbackType.POSITIVE)
                .map(category -> new FeedbackOptionDto(category.name(), category.getDisplayName()))
                .toList();
        
        List<FeedbackOptionDto> negativeOptions = Arrays.stream(FeedbackCategory.values())
                .filter(category -> category.getFeedbackType() == FeedbackCategory.FeedbackType.NEGATIVE)
                .map(category -> new FeedbackOptionDto(category.name(), category.getDisplayName()))
                .toList();
        
        return new FeedbackOptionsResponseDto(positiveOptions, negativeOptions);
    }
    
    public record FeedbackOptionDto(
            String code,
            String displayName
    ) {}
}