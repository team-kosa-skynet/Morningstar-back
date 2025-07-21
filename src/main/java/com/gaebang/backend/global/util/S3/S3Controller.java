package com.gaebang.backend.global.util.S3;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/s3")
@RequiredArgsConstructor
public class S3Controller {

    private final S3ImageService s3ImageService;

    @PostMapping("/upload")
    public ResponseEntity<?> s3Upload(@RequestPart(value = "image", required = false) MultipartFile image) {
        String uploadedImg = s3ImageService.upload(image);
        return ResponseEntity.ok(uploadedImg);
    }
}