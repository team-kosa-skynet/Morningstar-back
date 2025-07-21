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
}
