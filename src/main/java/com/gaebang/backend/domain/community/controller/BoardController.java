package com.gaebang.backend.domain.community.controller;

import com.gaebang.backend.domain.community.dto.reqeust.BoardCreateAndEditRequestDto;
import com.gaebang.backend.domain.community.dto.response.BoardDetailResponseDto;
import com.gaebang.backend.domain.community.dto.response.BoardListResponseDto;
import com.gaebang.backend.domain.community.service.BoardService;
import com.gaebang.backend.global.springsecurity.PrincipalDetails;
import com.gaebang.backend.global.util.ResponseDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RequiredArgsConstructor
@RequestMapping("/api")
@RestController
public class BoardController {

    private final BoardService boardService;

    // 게시판 상세 조회
    @GetMapping("/board/search")
    public ResponseEntity<ResponseDTO<Page<BoardListResponseDto>>> getBoardByCondition(
            @RequestParam("condition") String condition,
            Pageable pageable) {
        /*List<BoardResponseDto> boardDto;*/

        /*if (!condition.title().isBlank()) {
            boardDto = communityService.getBoardByCondition(condition.title(), pageable);
        } else if (!condition.writer().isBlank()) {
            boardDto = communityService.getBoardByWriter(condition.writer(), pageable);
        } else {
            boardDto = communityService.getBoardByContent(condition.content(), pageable);
        }*/

        Page<BoardListResponseDto> boardDto = boardService.getBoardByCondition(condition, pageable);

        ResponseDTO<Page<BoardListResponseDto>> responseDTO = ResponseDTO.okWithData(boardDto);
        return ResponseEntity.status(responseDTO.getCode()).body(responseDTO);
    }

    // 게시판 조회
    @GetMapping("/board")
    public ResponseEntity<ResponseDTO<Page<BoardListResponseDto>>> getBoard(Pageable pageable) {
        Page<BoardListResponseDto> boardDto = boardService.getBoard(pageable);

        ResponseDTO<Page<BoardListResponseDto>> listResponseDTO = ResponseDTO.okWithData(boardDto);

        return ResponseEntity
                .status(listResponseDTO.getCode())
                .body(listResponseDTO);
    }

    // 게시판 생성
    @PostMapping("/board")
    public ResponseEntity<Void> createBoard(@RequestBody BoardCreateAndEditRequestDto boardCreateAndEditRequestDto,
                                            @AuthenticationPrincipal PrincipalDetails principalDetails) {

        boardService.createBoard(principalDetails, boardCreateAndEditRequestDto);

        ResponseDTO<Void> ok = ResponseDTO.ok();

        return ResponseEntity
                .status(ok.getCode())
                .build();
    }

    // 게시판 수정
    @PatchMapping("/board/{boardId}")
    public ResponseEntity<ResponseDTO<Void>> editBoard(@PathVariable("boardId") Long boardId,
                                                       @RequestBody BoardCreateAndEditRequestDto boardCreateAndEditRequestDto,
                                                       @AuthenticationPrincipal PrincipalDetails principalDetails) {

        boardService.editBoard(boardId, boardCreateAndEditRequestDto, principalDetails);

        ResponseDTO<Void> ok = ResponseDTO.ok();

        return ResponseEntity
                .status(ok.getCode())
                .build();
    }

    // 게시판 상세 조회
    @GetMapping("/board/{boardId}")
    public ResponseEntity<ResponseDTO<BoardDetailResponseDto>> getBoardDetail(
            @PathVariable("boardId") Long boardId,
            Pageable commentPageable,
            @AuthenticationPrincipal PrincipalDetails principalDetails) {

        BoardDetailResponseDto boardDetail = boardService.getBoardDetail(boardId, commentPageable, principalDetails);

        ResponseDTO<BoardDetailResponseDto> dto = ResponseDTO.okWithData(boardDetail);

        return ResponseEntity
                .status(dto.getCode())
                .body(dto);
    }

    // 게시글 삭제
    @DeleteMapping("/board/{boardId}")
    public ResponseEntity<ResponseDTO<Void>> deleteBoard(@PathVariable Long boardId,
                                                         @AuthenticationPrincipal PrincipalDetails principalDetails) {

        boardService.deleteBoard(boardId, principalDetails);

        ResponseDTO<Void> ok = ResponseDTO.ok();

        return ResponseEntity
                .status(ok.getCode())
                .build();
    }
}
