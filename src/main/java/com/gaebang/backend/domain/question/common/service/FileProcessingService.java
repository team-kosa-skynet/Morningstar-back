package com.gaebang.backend.domain.question.common.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class FileProcessingService {

    private final Tika tika = new Tika();

    public Map<String, Object> processFile(MultipartFile file) {
        Map<String, Object> result = new HashMap<>();
        String fileName = file.getOriginalFilename();

        try {
            log.info("=== 파일 처리 시작 ===");
            log.info("파일명: {}", fileName);
            log.info("파일 크기: {} bytes", file.getSize());

            result.put("fileName", fileName);
            result.put("fileSize", file.getSize());

            String mimeType = detectMimeTypeSafely(file);
            log.info("감지된 MIME 타입: {}", mimeType);
            result.put("mimeType", mimeType);

            if (isImageFile(mimeType)) {
                log.info("이미지 파일로 인식됨");
                result.put("type", "image");
                String base64 = encodeToBase64(file);
                log.info("Base64 인코딩 완료 - 길이: {} 문자", base64.length());
                log.info("Base64 시작 부분: {}", base64.length() > 50 ? base64.substring(0, 50) + "..." : base64);
                result.put("base64", base64);
            } else if (isTextBasedFile(mimeType)) {
                log.info("텍스트 파일로 인식됨");

                // PDF 특별 처리
                if (mimeType.equals("application/pdf")) {
                    String extractedText = extractTextSafely(file, mimeType);

                    if (isPdfWithNoText(extractedText)) {
                        log.info("PDF에서 텍스트 추출 실패 또는 빈 텍스트 - 이미지로 변환 시도");
                        String base64Image = convertPdfToBase64Image(file);

                        if (base64Image != null) {
                            result.put("type", "image");
                            result.put("base64", base64Image);
                            result.put("mimeType", "image/png");
                            log.info("PDF → 이미지 변환 성공 - Base64 길이: {} 문자", base64Image.length());
                        } else {
                            result.put("type", "text");
                            result.put("extractedText", "[PDF 처리 실패: 텍스트 추출 및 이미지 변환 모두 실패]");
                            log.warn("PDF 텍스트 추출 및 이미지 변환 모두 실패");
                        }
                    } else {
                        result.put("type", "text");
                        result.put("extractedText", extractedText);
                        log.info("PDF 텍스트 추출 성공 - 길이: {} 문자", extractedText.length());
                    }
                } else {
                    // 일반 텍스트 파일 처리
                    result.put("type", "text");
                    result.put("extractedText", extractTextSafely(file, mimeType));
                }
            } else {
                result.put("type", "unsupported");
                log.warn("지원하지 않는 파일 형식: {} (파일: {})", mimeType, fileName);
            }

            log.info("파일 처리 완료 - 타입: {}", result.get("type"));
            log.info("=== 파일 처리 끝 ===");

            return result;

        } catch (Exception e) {
            log.error("파일 처리 중 오류 발생: {}", fileName, e);

            result.put("fileName", fileName);
            result.put("fileSize", file.getSize());
            result.put("type", "error");
            result.put("error", e.getMessage());
            return result;
        }
    }

    private boolean isPdfWithNoText(String extractedText) {
        return extractedText == null ||
                extractedText.trim().isEmpty() ||
                extractedText.startsWith("[파일 처리 실패:") ||
                extractedText.startsWith("[텍스트 추출 실패:");
    }

    private String convertPdfToBase64Image(MultipartFile file) {
        try {
            log.info("PDF → 이미지 변환 시작: {}", file.getOriginalFilename());

            // PDFBox 3.x 버전용 API 사용
            PDDocument document = PDDocument.load(file.getBytes());
            PDFRenderer pdfRenderer = new PDFRenderer(document);

            // 첫 번째 페이지만 렌더링 (300 DPI)
            BufferedImage image = pdfRenderer.renderImageWithDPI(0, 300);

            // BufferedImage를 PNG 바이트 배열로 변환
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "PNG", baos);
            byte[] imageBytes = baos.toByteArray();

            document.close();
            baos.close();

            // Base64 인코딩
            String base64 = Base64.getEncoder().encodeToString(imageBytes);
            log.info("PDF → 이미지 변환 완료 - 이미지 크기: {}x{}, Base64 길이: {} 문자",
                    image.getWidth(), image.getHeight(), base64.length());

            return base64;

        } catch (Exception e) {
            log.error("PDF → 이미지 변환 실패: {}", file.getOriginalFilename(), e);
            return null;
        }
    }


    private String detectMimeTypeSafely(MultipartFile file) {
        try {
            return tika.detect(file.getInputStream());
        } catch (Exception e) {
            log.warn("Tika MIME 타입 감지 실패, 대체 방법 사용: {}", file.getOriginalFilename(), e);

            String contentType = file.getContentType();
            if (contentType != null && !contentType.isEmpty()) {
                return contentType;
            }

            return getMimeTypeByExtension(file.getOriginalFilename());
        }
    }

    private String extractTextSafely(MultipartFile file, String mimeType) {
        try {
            return tika.parseToString(file.getInputStream());
        } catch (TikaException | IOException e) {
            log.warn("Tika 텍스트 추출 실패, 대체 방법 사용: {} ({})", file.getOriginalFilename(), e.getMessage());

            if (mimeType.startsWith("text/")) {
                try {
                    return new String(file.getBytes(), "UTF-8");
                } catch (Exception ex) {
                    log.error("텍스트 파일 직접 읽기 실패: {}", file.getOriginalFilename(), ex);
                    return "[텍스트 추출 실패: " + ex.getMessage() + "]";
                }
            }

            return "[파일 처리 실패: " + e.getMessage() + "]";
        }
    }

    private String getMimeTypeByExtension(String fileName) {
        if (fileName == null) return "application/octet-stream";

        String extension = getFileExtension(fileName).toLowerCase();

        return switch (extension) {
            case "txt" -> "text/plain";
            case "pdf" -> "application/pdf";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "csv" -> "text/csv";
            case "html" -> "text/html";
            case "xml" -> "text/xml";
            case "json" -> "application/json";
            default -> "application/octet-stream";
        };
    }

    private String getFileExtension(String fileName) {
        int lastDotIndex = fileName.lastIndexOf('.');
        return lastDotIndex > 0 ? fileName.substring(lastDotIndex + 1) : "";
    }

    private boolean isImageFile(String mimeType) {
        return mimeType.startsWith("image/") &&
                (mimeType.contains("jpeg") || mimeType.contains("png") ||
                        mimeType.contains("gif") || mimeType.contains("webp"));
    }

    private boolean isTextBasedFile(String mimeType) {
        return mimeType.equals("application/pdf") ||
                mimeType.equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document") ||
                mimeType.equals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet") ||
                mimeType.equals("application/vnd.openxmlformats-officedocument.presentationml.presentation") ||
                mimeType.startsWith("text/") ||
                mimeType.equals("application/json");
    }

    private String encodeToBase64(MultipartFile file) throws IOException {
        byte[] bytes = file.getBytes();
        return Base64.getEncoder().encodeToString(bytes);
    }
}
