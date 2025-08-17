package com.gaebang.backend.domain.interviewTurn.service;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class DocumentContentExtractor {
    
    public Map<String, Object> extractStructuredInfo(String rawText) {
        // 1단계: 키워드 기반 섹션 분리
        Map<String, String> sections = extractSections(rawText);
        
        // 2단계: 각 섹션에서 정규식으로 구체적 정보 추출
        Map<String, Object> structuredInfo = new HashMap<>();
        
        // 기술 스택 추출
        List<String> techStacks = extractTechStacks(sections);
        structuredInfo.put("techStacks", techStacks);
        
        // 프로젝트 정보 추출
        List<Map<String, String>> projects = extractProjects(sections);
        structuredInfo.put("projects", projects);
        
        // 경력 정보 추출 (회사명은 제외하고 역할/기간만)
        List<Map<String, String>> careers = extractCareers(sections);
        structuredInfo.put("careers", careers);
        
        return structuredInfo;
    }
    
    // 1단계: 키워드로 섹션 분리
    private Map<String, String> extractSections(String text) {
        Map<String, String> sections = new HashMap<>();
        
        String[] techKeywords = {"기술\\s*스택", "기술\\s*역량", "보유\\s*기술", "사용\\s*기술"};
        String[] projectKeywords = {"프로젝트\\s*경험", "주요\\s*프로젝트", "개발\\s*경험", "프로젝트"};
        String[] careerKeywords = {"경력\\s*사항", "근무\\s*경험", "직무\\s*경험", "경력"};
        
        sections.put("techStacks", extractSectionByKeywords(text, techKeywords));
        sections.put("projects", extractSectionByKeywords(text, projectKeywords));
        sections.put("careers", extractSectionByKeywords(text, careerKeywords));
        
        return sections;
    }
    
    private String extractSectionByKeywords(String text, String[] keywords) {
        for (String keyword : keywords) {
            Pattern pattern = Pattern.compile(
                "(" + keyword + ".*?)(?=(기술|프로젝트|경력|학력|자격|수상|마무리|결론|$))",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL
            );
            
            Matcher matcher = pattern.matcher(text);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return "";
    }
    
    // 2단계: 기술 스택 정규식 추출
    private List<String> extractTechStacks(Map<String, String> sections) {
        Set<String> techStacks = new HashSet<>();
        
        // 기술 스택 패턴들
        String[] techPatterns = {
            // 프로그래밍 언어
            "\\b(Java|Python|JavaScript|TypeScript|Kotlin|Scala|Go|Rust|C\\+\\+|C#)\\b",
            // 프론트엔드
            "\\b(React|Vue|Angular|Svelte|Next\\.js|Nuxt\\.js|jQuery)\\b",
            // 백엔드 프레임워크
            "\\b(Spring|Django|Flask|Express|Nest\\.js|FastAPI|Laravel|Rails)\\b",
            // 데이터베이스
            "\\b(MySQL|PostgreSQL|MongoDB|Redis|Oracle|MariaDB|SQLite)\\b",
            // 클라우드/인프라
            "\\b(AWS|GCP|Azure|Docker|Kubernetes|Jenkins|Git|GitLab)\\b",
            // 스타일링
            "\\b(HTML|CSS|SCSS|SASS|Tailwind|Bootstrap)\\b"
        };
        
        String searchText = sections.getOrDefault("techStacks", "") + 
                           " " + sections.getOrDefault("projects", "");
        
        for (String pattern : techPatterns) {
            Pattern p = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
            Matcher m = p.matcher(searchText);
            while (m.find()) {
                techStacks.add(m.group(1));
            }
        }
        
        return new ArrayList<>(techStacks);
    }
    
    // 프로젝트 정보 추출 (개인정보 제외)
    private List<Map<String, String>> extractProjects(Map<String, String> sections) {
        List<Map<String, String>> projects = new ArrayList<>();
        String projectSection = sections.getOrDefault("projects", "");
        
        // 프로젝트 기간 패턴
        Pattern durationPattern = Pattern.compile(
            "(\\d{4})\\s*[년.-]\\s*(\\d{1,2})\\s*[월.-]?\\s*~\\s*(\\d{4})\\s*[년.-]\\s*(\\d{1,2})\\s*[월.-]?"
        );
        
        // 역할 패턴
        Pattern rolePattern = Pattern.compile(
            "(팀장|리더|프론트엔드|백엔드|풀스택|개발자|담당|PM|PL)"
        );
        
        Matcher durationMatcher = durationPattern.matcher(projectSection);
        while (durationMatcher.find()) {
            Map<String, String> project = new HashMap<>();
            project.put("duration", durationMatcher.group());
            
            // 해당 프로젝트 주변 텍스트에서 역할 찾기
            int start = Math.max(0, durationMatcher.start() - 100);
            int end = Math.min(projectSection.length(), durationMatcher.end() + 100);
            String surrounding = projectSection.substring(start, end);
            
            Matcher roleMatcher = rolePattern.matcher(surrounding);
            if (roleMatcher.find()) {
                project.put("role", roleMatcher.group());
            }
            
            projects.add(project);
        }
        
        return projects;
    }
    
    // 경력 정보 추출 (개인 식별 정보 제외)
    private List<Map<String, String>> extractCareers(Map<String, String> sections) {
        List<Map<String, String>> careers = new ArrayList<>();
        String careerSection = sections.getOrDefault("careers", "");
        
        // 경력 기간 패턴
        Pattern experiencePattern = Pattern.compile(
            "(\\d+)\\s*년\\s*(\\d+)?\\s*개월?|" +
            "(\\d{4})\\s*[년.-]\\s*(\\d{1,2})\\s*[월.-]?\\s*~\\s*(\\d{4})\\s*[년.-]\\s*(\\d{1,2})\\s*[월.-]?"
        );
        
        // 직무/역할 패턴
        Pattern rolePattern = Pattern.compile(
            "(개발자|엔지니어|프로그래머|PM|PL|팀장|대리|과장|주임|인턴|신입|경력)"
        );
        
        Matcher experienceMatcher = experiencePattern.matcher(careerSection);
        while (experienceMatcher.find()) {
            Map<String, String> career = new HashMap<>();
            career.put("duration", experienceMatcher.group());
            
            // 주변 텍스트에서 역할 찾기
            int start = Math.max(0, experienceMatcher.start() - 50);
            int end = Math.min(careerSection.length(), experienceMatcher.end() + 50);
            String surrounding = careerSection.substring(start, end);
            
            Matcher roleMatcher = rolePattern.matcher(surrounding);
            if (roleMatcher.find()) {
                career.put("role", roleMatcher.group());
            }
            
            careers.add(career);
        }
        
        return careers;
    }
}