package com.gaebang.backend.domain.community.controller;

import com.gaebang.backend.domain.community.dto.reqeust.CommentRequestDto;
import com.gaebang.backend.domain.community.service.CommentService;
import com.gaebang.backend.global.springsecurity.PrincipalDetails;
import com.gaebang.backend.global.util.ResponseDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RequestMapping("/api")
@RequiredArgsConstructor
@RestController
public class CommentController {

    private final CommentService commentService;

    // 댓글 생성
    @PostMapping("/comments")
    public ResponseEntity<ResponseDTO<Void>> addComment(@RequestBody CommentRequestDto commentRequestDto,
                                                  @AuthenticationPrincipal PrincipalDetails principalDetails) {
        commentService.createComment(commentRequestDto, principalDetails);

        ResponseDTO<Void> ok = ResponseDTO.ok();

        return ResponseEntity
                .status(ok.getCode())
                .build();
    }

    // 댓글 삭제
    @DeleteMapping("/comments/{commentId}")
    public ResponseEntity<ResponseDTO<Void>> deleteComment(@PathVariable Long commentId,
                                                           @AuthenticationPrincipal PrincipalDetails principalDetails) {
        commentService.deleteComment(commentId, principalDetails);

        ResponseDTO<Void> ok = ResponseDTO.ok();

        return ResponseEntity
                .status(ok.getCode())
                .build();
    }

    // 댓글 수정
    @PatchMapping("/comments/{commentId}")
    public ResponseEntity<ResponseDTO<Void>> editComment(@PathVariable Long commentId,
                                                         @RequestBody CommentRequestDto commentRequestDto,
                                                         @AuthenticationPrincipal PrincipalDetails principalDetails) {
        commentService.editComment(commentId, commentRequestDto, principalDetails);

        ResponseDTO<Void> ok = ResponseDTO.ok();

        return ResponseEntity
                .status(ok.getCode())
                .build();
    }
}
