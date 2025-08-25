package com.gaebang.backend.global.util.S3;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.CannedAccessControlList;
import com.amazonaws.services.s3.model.DeleteObjectRequest;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.util.IOUtils;
import com.gaebang.backend.global.exception.S3Exception;
import io.github.cdimascio.dotenv.Dotenv;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLDecoder;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Slf4j
@RequiredArgsConstructor
@Component
public class S3ImageService {

    private static Dotenv dotenv = Dotenv.load();
    private final AmazonS3 amazonS3;
    private final String bucketName = dotenv.get("BUCKET_NAME");;

    public String upload(MultipartFile image) {
        if(image.isEmpty() || Objects.isNull(image.getOriginalFilename())){
            throw new S3Exception();
        }
        return this.uploadImage(image);
    }

    private String uploadImage(MultipartFile image) {
        this.validateImageFileExtention(image.getOriginalFilename());
        try {
            return this.uploadImageToS3(image);
        } catch (IOException e) {
            throw new S3Exception();
        }
    }

    private void validateImageFileExtention(String filename) {
        int lastDotIndex = filename.lastIndexOf(".");
        if (lastDotIndex == -1) {
            throw new S3Exception();
        }

        String extention = filename.substring(lastDotIndex + 1).toLowerCase();
        List<String> allowedExtentionList = Arrays.asList("jpg", "jpeg", "png", "gif");

        if (!allowedExtentionList.contains(extention)) {
            throw new S3Exception();
        }
    }

    private String uploadImageToS3(MultipartFile image) throws IOException {
        String originalFilename = image.getOriginalFilename(); //원본 파일 명
        String extention = originalFilename.substring(originalFilename.lastIndexOf(".")); //확장자 명

        String s3FileName = UUID.randomUUID().toString().substring(0, 10) + originalFilename; //변경된 파일 명

        InputStream is = image.getInputStream();
        byte[] bytes = IOUtils.toByteArray(is);

        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentType("image/" + extention);
        metadata.setContentLength(bytes.length);
        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(bytes);

        try{
            PutObjectRequest putObjectRequest =
                    new PutObjectRequest(bucketName, s3FileName, byteArrayInputStream, metadata)
                            .withCannedAcl(CannedAccessControlList.PublicRead);
            amazonS3.putObject(putObjectRequest); // put image to S3
        }catch (Exception e){
            throw new S3Exception();
        }finally {
            byteArrayInputStream.close();
            is.close();
        }

        return amazonS3.getUrl(bucketName, s3FileName).toString();
    }

    public void deleteImageFromS3(String imageAddress){
        String key = getKeyFromImageAddress(imageAddress);
        try{
            amazonS3.deleteObject(new DeleteObjectRequest(bucketName, key));
        }catch (Exception e){
            throw new S3Exception();
        }
    }

    private String getKeyFromImageAddress(String imageAddress){
        try{
            URL url = new URL(imageAddress);
            String decodingKey = URLDecoder.decode(url.getPath(), "UTF-8");
            return decodingKey.substring(1); // 맨 앞의 '/' 제거
        }catch (MalformedURLException | UnsupportedEncodingException e){
            throw new S3Exception();
        }
    }

    /**
     * 이미지 URL을 Base64로 인코딩 (검열 시스템용)
     * @param imageUrl S3 이미지 URL
     * @return Base64 인코딩된 이미지 문자열
     * @throws IOException 이미지 읽기 실패 시
     */
    public String encodeImageToBase64(String imageUrl) throws IOException {
        if (imageUrl == null || imageUrl.trim().isEmpty()) {
            throw new IllegalArgumentException("이미지 URL이 비어있습니다.");
        }

        try {
            // URL 연결 생성
            URL url = new URL(imageUrl);
            java.net.URLConnection connection = url.openConnection();
            connection.setConnectTimeout(10000); // 10초 연결 타임아웃
            connection.setReadTimeout(30000);    // 30초 읽기 타임아웃
            
            // Content-Type 확인
            String contentType = connection.getContentType();
            
            // Content-Type이 없거나 잘못된 경우 URL 확장자로 추정
            if (!isValidImageContentType(contentType)) {
                String guessedType = guessContentTypeFromUrl(imageUrl);
                if (isValidImageContentType(guessedType)) {
                    contentType = guessedType;
                } else {
                    throw new IllegalArgumentException("지원하지 않는 이미지 형식: " + contentType);
                }
            }

            // 이미지 데이터 읽기
            try (InputStream inputStream = connection.getInputStream()) {
                byte[] imageBytes = IOUtils.toByteArray(inputStream);
                
                // 크기 제한 확인 (20MB)
                if (imageBytes.length > 20 * 1024 * 1024) {
                    throw new IllegalArgumentException("이미지 크기가 20MB를 초과합니다.");
                }
                
                // Base64 인코딩
                String base64Image = java.util.Base64.getEncoder().encodeToString(imageBytes);
                
                log.debug("이미지 Base64 인코딩 완료 - URL: {}, 크기: {}bytes", imageUrl, imageBytes.length);
                return base64Image;
            }
            
        } catch (Exception e) {
            log.error("이미지 Base64 인코딩 실패 - URL: {}, 오류: {}", imageUrl, e.getMessage());
            throw new IOException("이미지 인코딩 실패: " + e.getMessage(), e);
        }
    }

    /**
     * MIME 타입이 지원되는 이미지 형식인지 확인
     * @param contentType MIME 타입
     * @return 지원 여부
     */
    private boolean isValidImageContentType(String contentType) {
        if (contentType == null) return false;
        
        // MIME 타입 정규화 (소문자 변환, 공백 제거)
        String normalizedType = contentType.toLowerCase().trim();
        
        // 세미콜론 이후 파라미터 제거 (예: "image/png; charset=UTF-8" -> "image/png")
        if (normalizedType.contains(";")) {
            normalizedType = normalizedType.substring(0, normalizedType.indexOf(";")).trim();
        }
        
        return normalizedType.equals("image/jpeg") ||
               normalizedType.equals("image/jpg") ||
               normalizedType.equals("image/png") ||
               normalizedType.equals("image/webp");
    }

    /**
     * URL 확장자로부터 MIME 타입 추정
     * @param imageUrl 이미지 URL
     * @return 추정된 MIME 타입
     */
    private String guessContentTypeFromUrl(String imageUrl) {
        if (imageUrl == null) return null;
        
        try {
            // URL 디코딩 처리
            String decodedUrl = URLDecoder.decode(imageUrl, "UTF-8");
            String lowerUrl = decodedUrl.toLowerCase();
            
            if (lowerUrl.endsWith(".jpg") || lowerUrl.endsWith(".jpeg")) {
                return "image/jpeg";
            } else if (lowerUrl.endsWith(".png")) {
                return "image/png";
            } else if (lowerUrl.endsWith(".webp")) {
                return "image/webp";
            }
        } catch (UnsupportedEncodingException e) {
            log.warn("URL 디코딩 실패: {}", imageUrl);
        }
        
        return null;
    }

    /**
     * 이미지 URL에서 MIME 타입 추출
     * @param imageUrl 이미지 URL
     * @return MIME 타입
     */
    public String getMimeTypeFromUrl(String imageUrl) {
        if (imageUrl == null) return null;
        
        String lowerUrl = imageUrl.toLowerCase();
        if (lowerUrl.endsWith(".jpg") || lowerUrl.endsWith(".jpeg")) {
            return "image/jpeg";
        } else if (lowerUrl.endsWith(".png")) {
            return "image/png";
        } else if (lowerUrl.endsWith(".webp")) {
            return "image/webp";
        }
        
        return null;
    }

    /**
     * Base64 문자열의 크기 계산 (대략적)
     * @param base64String Base64 문자열
     * @return 예상 크기 (bytes)
     */
    public long calculateBase64Size(String base64String) {
        if (base64String == null) return 0;
        return (long) (base64String.length() * 0.75); // Base64는 원본의 약 133% 크기
    }
}
