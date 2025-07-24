package com.gaebang.backend.domain.community.controller;

import com.gaebang.backend.domain.community.service.BoardLikeService;
import com.gaebang.backend.global.springsecurity.PrincipalDetails;
import com.gaebang.backend.global.util.ResponseDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RequestMapping("/api")
@RequiredArgsConstructor
@RestController
public class BoardLikeController {

    private final BoardLikeService boardLikeService;

    // 게시판 좋아요(토클)
    @PostMapping("/api/boards/{boardId}/like")
    public ResponseEntity<ResponseDTO<Void>> toggleBoardLike(@PathVariable Long boardId,
                                                             @AuthenticationPrincipal PrincipalDetails principalDetails) {
        boardLikeService.togglePostLike(boardId, principalDetails);

        ResponseDTO<Void> ok = ResponseDTO.ok();

        return ResponseEntity
                .status(ok.getCode())
                .build();
    }
}
