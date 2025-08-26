package com.gaebang.backend.domain.question.feedback.service;

import com.gaebang.backend.domain.member.entity.Member;
import com.gaebang.backend.domain.member.exception.UserNotFoundException;
import com.gaebang.backend.domain.member.repository.MemberRepository;
import com.gaebang.backend.domain.question.common.entity.AiModel;
import com.gaebang.backend.domain.question.common.repository.AiModelRepository;
import com.gaebang.backend.domain.question.feedback.dto.request.SubmitFeedbackRequestDto;
import com.gaebang.backend.domain.question.feedback.dto.response.FeedbackOptionsResponseDto;
import com.gaebang.backend.domain.question.feedback.dto.response.SubmitFeedbackResponseDto;
import com.gaebang.backend.domain.question.feedback.entity.FeedbackCategory;
import com.gaebang.backend.domain.question.feedback.entity.ModelFeedback;
import com.gaebang.backend.domain.question.feedback.exception.InvalidFeedbackCategoryException;
import com.gaebang.backend.domain.question.feedback.exception.ModelNotFoundException;
import com.gaebang.backend.domain.question.feedback.repository.ModelFeedbackRepository;
import com.gaebang.backend.global.springsecurity.PrincipalDetails;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class ModelFeedbackService {
    
    private final ModelFeedbackRepository modelFeedbackRepository;
    private final MemberRepository memberRepository;
    private final AiModelRepository aiModelRepository;
    
    public FeedbackOptionsResponseDto getFeedbackOptions() {
        return FeedbackOptionsResponseDto.create();
    }

    @Transactional
    public SubmitFeedbackResponseDto submitFeedback(
            SubmitFeedbackRequestDto requestDto,
            PrincipalDetails principalDetails
    ) {
        // 사용자 조회
        Member member = memberRepository.findById(principalDetails.getMember().getId())
                .orElseThrow(UserNotFoundException::new);

        // 모델명 검증 (활성화된 모델인지 확인)
        if (requestDto.positiveModel() != null) {
            aiModelRepository.findByModelNameAndIsActiveTrue(requestDto.positiveModel())
                    .orElseThrow(() -> {
                        return new ModelNotFoundException();
                    });
        }

        if (requestDto.negativeModel() != null) {
            aiModelRepository.findByModelNameAndIsActiveTrue(requestDto.negativeModel())
                    .orElseThrow(() -> {
                        return new ModelNotFoundException();
                    });
        }

        // 피드백 카테고리 검증
        if (requestDto.positiveFeedback() != null) {
            try {
                FeedbackCategory positiveFeedbackCategory = FeedbackCategory.valueOf(requestDto.positiveFeedback());
                if (positiveFeedbackCategory.getFeedbackType() != FeedbackCategory.FeedbackType.POSITIVE) {
                    throw new InvalidFeedbackCategoryException();
                }
            } catch (IllegalArgumentException e) {
                throw new InvalidFeedbackCategoryException();
            }
        }

        if (requestDto.negativeFeedback() != null) {
            try {
                FeedbackCategory negativeFeedbackCategory = FeedbackCategory.valueOf(requestDto.negativeFeedback());
                if (negativeFeedbackCategory.getFeedbackType() != FeedbackCategory.FeedbackType.NEGATIVE) {
                    throw new InvalidFeedbackCategoryException();
                }
            } catch (IllegalArgumentException e) {
                throw new InvalidFeedbackCategoryException();
            }
        }

        // 피드백 엔티티 생성 및 저장
        ModelFeedback feedback = requestDto.toEntity(member);
        ModelFeedback savedFeedback = modelFeedbackRepository.save(feedback);

        return SubmitFeedbackResponseDto.fromEntity(savedFeedback);
    }
    
    public Long getPositiveFeedbackCount(String modelName) {
        var positiveCategories = Arrays.stream(FeedbackCategory.values())
                .filter(category -> category.getFeedbackType() == FeedbackCategory.FeedbackType.POSITIVE)
                .toList();
        
        return modelFeedbackRepository.countPositiveFeedbackByModelName(modelName, positiveCategories);
    }
    
    public Long getNegativeFeedbackCount(String modelName) {
        var negativeCategories = Arrays.stream(FeedbackCategory.values())
                .filter(category -> category.getFeedbackType() == FeedbackCategory.FeedbackType.NEGATIVE)
                .toList();
        
        return modelFeedbackRepository.countNegativeFeedbackByModelName(modelName, negativeCategories);
    }
}