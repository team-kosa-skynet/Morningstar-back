package com.gaebang.backend.domain.interview.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaebang.backend.domain.interview.entity.UploadedDocument;
import com.gaebang.backend.domain.interview.repository.UploadedDocumentRepository;
import com.gaebang.backend.domain.member.entity.Member;
import com.gaebang.backend.domain.member.repository.MemberRepository;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.UUID;

@Service
public class DocumentParsingService {

    private final UploadedDocumentRepository uploadedDocumentRepository;
    private final MemberRepository memberRepository;
    private final DocumentContentExtractor documentContentExtractor;
    private final ObjectMapper objectMapper;

    public DocumentParsingService(UploadedDocumentRepository uploadedDocumentRepository,
                                  MemberRepository memberRepository,
                                  DocumentContentExtractor documentContentExtractor,
                                  ObjectMapper objectMapper) {
        this.uploadedDocumentRepository = uploadedDocumentRepository;
        this.memberRepository = memberRepository;
        this.documentContentExtractor = documentContentExtractor;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public String parseAndSaveDocument(MultipartFile file, Long memberId) throws Exception {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new IllegalArgumentException("회원을 찾을 수 없습니다: " + memberId));
        
        String fileType = getFileType(file);
        String rawText = extractContent(file, fileType);
        
        // 구조화된 정보 추출 (개인정보 제외)
        Map<String, Object> structuredInfo = documentContentExtractor.extractStructuredInfo(rawText);
        String structuredJson = objectMapper.writeValueAsString(structuredInfo);
        
        UUID documentId = UUID.randomUUID();
        UploadedDocument document = UploadedDocument.create(
                documentId,
                member,
                file.getOriginalFilename(),
                fileType,
                file.getSize(),
                structuredJson  // 원본 텍스트 대신 구조화된 정보 저장
        );
        
        uploadedDocumentRepository.save(document);
        
        return documentId.toString();
    }

    private String getFileType(MultipartFile file) {
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || !originalFilename.contains(".")) {
            return "";
        }
        return originalFilename.substring(originalFilename.lastIndexOf(".") + 1).toLowerCase();
    }

    private String extractContent(MultipartFile file, String fileType) throws Exception {
        switch (fileType) {
            case "pdf":
                return extractFromPdf(file);
            case "docx":
                return extractFromDocx(file);
            case "txt":
                return extractFromTxt(file);
            default:
                throw new IllegalArgumentException("지원하지 않는 파일 형식: " + fileType);
        }
    }

    private String extractFromPdf(MultipartFile file) throws Exception {
        try (PDDocument document = Loader.loadPDF(file.getBytes())) {
            
            PDFTextStripper stripper = new PDFTextStripper();
            
            // 텍스트 추출 옵션 설정
            stripper.setSortByPosition(true); // 위치 기반 정렬로 정확한 순서 보장
            stripper.setStartPage(1);
            stripper.setEndPage(document.getNumberOfPages());
            
            String extractedText = stripper.getText(document);
            
            return extractedText.replaceAll("\\n{3,}", "\n\n").trim();
        }
    }

    private String extractFromDocx(MultipartFile file) throws Exception {
        try (XWPFDocument document = new XWPFDocument(file.getInputStream())) {
            
            XWPFWordExtractor extractor = new XWPFWordExtractor(document);
            
            String extractedText = extractor.getText();
            extractor.close();
            
            // 불필요한 공백 정리
            return extractedText.replaceAll("\\n{3,}", "\n\n").trim();
        }
    }

    private String extractFromTxt(MultipartFile file) throws Exception {
        return new String(file.getBytes(), "UTF-8");
    }
}