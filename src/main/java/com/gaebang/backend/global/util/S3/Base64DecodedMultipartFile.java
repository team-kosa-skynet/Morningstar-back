package com.gaebang.backend.global.util.S3;

import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.Files;
import java.util.Base64;
import java.util.UUID;

public class Base64DecodedMultipartFile implements MultipartFile {

    private final byte[] content;
    private final String name;
    private final String originalFilename;
    private final String contentType;

    public Base64DecodedMultipartFile(String base64String, String contentType) {
        // "data:image/jpeg;base64," 같은 Data URI 접두사를 제거합니다.
        String pureBase64 = base64String.startsWith("data:") ? base64String.split(",")[1] : base64String;
        this.content = Base64.getDecoder().decode(pureBase64);
        this.name = "file";
        this.contentType = contentType;
        // 파일 확장자를 결정합니다. (예: "image/jpeg" -> ".jpeg")
        String extension = "." + contentType.split("/")[1];
        // 고유한 파일 이름을 생성합니다.
        this.originalFilename = UUID.randomUUID().toString() + extension;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getOriginalFilename() {
        return originalFilename;
    }

    @Override
    public String getContentType() {
        return contentType;
    }

    @Override
    public boolean isEmpty() {
        return content == null || content.length == 0;
    }

    @Override
    public long getSize() {
        return content.length;
    }

    @Override
    public byte[] getBytes() throws IOException {
        return content;
    }

    @Override
    public InputStream getInputStream() throws IOException {
        return new ByteArrayInputStream(content);
    }

    @Override
    public void transferTo(File dest) throws IOException, IllegalStateException {
        Files.write(dest.toPath(), content);
    }
}