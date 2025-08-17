package com.gaebang.backend.domain.recruitmentNotice.service;

import com.gaebang.backend.domain.newsData.util.HtmlUtils;
import com.gaebang.backend.domain.newsData.util.HttpClientUtil;
import com.gaebang.backend.domain.recruitmentNotice.dto.response.RecruitmentResponseDto;
import com.gaebang.backend.domain.recruitmentNotice.entity.Recruitment;
import com.gaebang.backend.domain.recruitmentNotice.repository.RecruitmentRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cdimascio.dotenv.Dotenv;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RecruitmentService {

    private final HttpClientUtil httpClient;
    private final RecruitmentRepository recruitmentRepository;

    private static Dotenv dotenv = Dotenv.load();

    private String authKey = dotenv.get("SARAMIN_KEY");

    private static final String SARAMIN_API_URL = "https://oapi.saramin.co.kr/job-search";
    private static final int DEFAULT_DISPLAY_COUNT = 100;

    // DB에서 채용정보 조회
    public List<RecruitmentResponseDto> getRecruitmentData() {

        LocalDateTime now = LocalDateTime.now();
        List<Recruitment> recruitments = recruitmentRepository.findByExpirationDateAfterOrderByPubDateDesc(now);

        return recruitments.stream()
                .map(recruitment -> RecruitmentResponseDto.fromEntity(recruitment))
                .collect(Collectors.toList());
    }

    // 채용정보 데이터를 조회하고 DB에 저장
//    @Scheduled(cron = "0 */5 * * * *", zone = "Asia/Seoul") // 5분마다 실행
    @Scheduled(cron = "0 */10 * * * *", zone = "Asia/Seoul") // 10분마다 실행
//    @Scheduled(cron = "*/30 * * * * *", zone = "Asia/Seoul") // 30초마다 실행
    @Transactional
    public void fetchAndSaveRecruitment() {
        try {
            log.info("scheduled 실행 중");

            String response = getRecruitmentApiResponse();
            List<Recruitment> recruitmentList = parseRecruitmentResponse(response);

            // 중복 제거 (링크 기준)
            recruitmentList = removeDuplicates(recruitmentList);

            // 이미 존재하는 뉴스 필터링
            recruitmentList = filterExistingRecruitment(recruitmentList);

            if (!recruitmentList.isEmpty()) {
                // 배치 저장
                recruitmentRepository.saveAll(recruitmentList);
                log.info("채용정보 데이터 {}건 저장 완료", recruitmentList.size());
            } else {
                log.info("저장할 새로운 채용정보가 없습니다.");
            }

        } catch (Exception e) {
            log.error("채용정보 데이터 처리 중 오류", e);
            throw new RuntimeException("채용정보 데이터 처리 실패", e);
        }
    }

    // API 응답 조회
    private String getRecruitmentApiResponse() throws Exception {
        String apiUrl = buildApiUrl(87, 0, DEFAULT_DISPLAY_COUNT);
        Map<String, String> headers = buildHeaders();

        String response = httpClient.get(apiUrl, headers);
        log.info("채용정보 API 응답 수신 완료");

        return response;
    }

    // JSON 응답을 Recruitment 엔티티 리스트로 변환
    private List<Recruitment> parseRecruitmentResponse(String response) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode jsonNode = mapper.readTree(response);

        List<Recruitment> recruitmentList = new ArrayList<>();
        JsonNode jobs = jsonNode.get("jobs").get("job");

        if (jobs != null && jobs.isArray()) {
            for (JsonNode job : jobs) {
                try {
                    Recruitment recruitment = createRecruitmentFromJson(job);
                    recruitmentList.add(recruitment);
                } catch (Exception e) {
                    log.warn("채용정보 아이템 파싱 중 오류 - 해당 아이템 건너뜀: {}", e.getMessage());
                }
            }
        }

        return recruitmentList;
    }

    // JSON 아이템을 Recruitment 엔티티로 변환
    // HtmlUtils.cleanText -> 내가 설정한 util 코드 -> json 으로 받아온 것을 깔끔한 문자열로만 나타낼 수 있게
    private Recruitment createRecruitmentFromJson(JsonNode job) {
        Recruitment recruitment = Recruitment.builder()
                .link(job.get("url").asText())
                .companyName(HtmlUtils.cleanText(job.get("company").get("detail").get("name").asText()))
                .title(HtmlUtils.cleanText(job.get("position").get("title").asText()))
                .technologyStack(HtmlUtils.cleanText(job.get("position").get("job-code").get("name").asText()))
                .workLocation(HtmlUtils.cleanText(job.get("position").get("location").get("name").asText()))
                .careerLevel(HtmlUtils.cleanText(job.get("position").get("experience-level").get("name").asText()))
                .workType(HtmlUtils.cleanText(job.get("position").get("job-type").get("name").asText()))
                .educationLevel(HtmlUtils.cleanText(job.get("position").get("required-education-level").get("name").asText()))
                .build();

        // pubDate 설정
        String pubDateStr = job.get("posting-timestamp").asText();
        String expirationDate = job.get("expiration-timestamp").asText();
        if (pubDateStr != null && !pubDateStr.isEmpty()) {
            // 날짜 설정
            recruitment.setPubDateFromTimestamp(pubDateStr);
            recruitment.setExpirationDateFromTimestamp(expirationDate);
        }

        return recruitment;
    }

    // 중복 제거 (링크 기준)
    private List<Recruitment> removeDuplicates(List<Recruitment> recruitmentList) {
        return recruitmentList.stream()
                .collect(Collectors.toMap(
                        Recruitment::getLink,
                        Function.identity(),
                        (existing, replacement) -> existing
                ))
                .values()
                .stream()
                .collect(Collectors.toList());
    }

    // 이미 DB에 존재하는 채용정보 필터링
    private List<Recruitment> filterExistingRecruitment(List<Recruitment> recruitmentList) {
        return recruitmentList.stream()
                .filter(recruitment -> {
                    Long count = recruitmentRepository.countExistingByLink(recruitment.getLink());
                    return count == 0; // 0이면 존재하지 않음
                })
                .collect(Collectors.toList());
    }

    // API URL을 생성 (정렬 옵션 포함)
    private String buildApiUrl(int jobCode, int start, int count) {
        return String.format("%s?access-key=%s&job_mid_cd=%d&start=%d&count=%d",
                SARAMIN_API_URL, authKey, jobCode, start, count);
    }

    // header 생성
    private Map<String, String> buildHeaders() {
        return Map.of(
                "Accept", "application/json"
        );
    }
}
