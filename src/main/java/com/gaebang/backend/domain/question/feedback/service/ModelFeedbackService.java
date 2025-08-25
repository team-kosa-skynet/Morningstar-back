package com.gaebang.backend.domain.question.feedback.service;

import com.gaebang.backend.domain.member.entity.Member;
import com.gaebang.backend.domain.member.exception.UserNotFoundException;
import com.gaebang.backend.domain.member.repository.MemberRepository;
import com.gaebang.backend.domain.question.feedback.dto.request.SubmitFeedbackRequestDto;
import com.gaebang.backend.domain.question.feedback.dto.response.FeedbackOptionsResponseDto;
import com.gaebang.backend.domain.question.feedback.dto.response.SubmitFeedbackResponseDto;
import com.gaebang.backend.domain.question.feedback.entity.FeedbackCategory;
import com.gaebang.backend.domain.question.feedback.entity.ModelFeedback;
import com.gaebang.backend.domain.question.feedback.exception.InvalidFeedbackCategoryException;
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
    
    public FeedbackOptionsResponseDto getFeedbackOptions(String modelName) {
        log.info("피드백 옵션 조회 요청 - 모델: {}", modelName);
        return FeedbackOptionsResponseDto.create(modelName);
    }
    
    @Transactional
    public SubmitFeedbackResponseDto submitFeedback(
            SubmitFeedbackRequestDto requestDto, 
            PrincipalDetails principalDetails
    ) {
        log.info("피드백 제출 요청 - 모델: {}, 카테고리: {}, 사용자: {}", 
                requestDto.modelName(), requestDto.feedbackCategory(), principalDetails.getUsername());
        
        // 사용자 조회
        Member member = memberRepository.findById(principalDetails.getMember().getId())
                .orElseThrow(UserNotFoundException::new);
        
        // 피드백 카테고리 검증
        FeedbackCategory feedbackCategory;
        try {
            feedbackCategory = FeedbackCategory.valueOf(requestDto.feedbackCategory());
        } catch (IllegalArgumentException e) {
            log.error("유효하지 않은 피드백 카테고리: {}", requestDto.feedbackCategory());
            throw new InvalidFeedbackCategoryException();
        }
        
        // 피드백 엔티티 생성 및 저장
        ModelFeedback feedback = ModelFeedback.builder()
                .member(member)
                .modelName(requestDto.modelName())
                .conversationId(requestDto.conversationId())
                .feedbackCategory(feedbackCategory)
                .detailedComment(requestDto.detailedComment())
                .build();
        
        ModelFeedback savedFeedback = modelFeedbackRepository.save(feedback);
        
        log.info("피드백 저장 완료 - ID: {}, 모델: {}, 타입: {}", 
                savedFeedback.getFeedbackId(), 
                savedFeedback.getModelName(),
                savedFeedback.getFeedbackType());
        
        return SubmitFeedbackResponseDto.success(savedFeedback.getFeedbackId());
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