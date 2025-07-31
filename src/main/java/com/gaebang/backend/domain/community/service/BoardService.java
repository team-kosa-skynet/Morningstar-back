package com.gaebang.backend.domain.community.service;

import com.gaebang.backend.domain.community.dto.reqeust.BoardCreateAndEditRequestDto;
import com.gaebang.backend.domain.community.dto.response.BoardListResponseDto;
import com.gaebang.backend.domain.community.dto.response.BoardListProjectionDto;
import com.gaebang.backend.domain.community.dto.response.BoardDetailResponseDto;
import com.gaebang.backend.domain.community.dto.response.CommentResponseDto;
import com.gaebang.backend.domain.community.entity.Board;
import com.gaebang.backend.domain.community.entity.Image;
import com.gaebang.backend.domain.community.exception.BoardNotFoundException;
import com.gaebang.backend.domain.community.repository.BoardLikeRepository;
import com.gaebang.backend.domain.community.repository.BoardRepository;
import com.gaebang.backend.domain.community.repository.CommentRepository;
import com.gaebang.backend.domain.community.repository.ImageRepository;
import com.gaebang.backend.domain.community.util.TimeUtil;
import com.gaebang.backend.domain.member.entity.Member;
import com.gaebang.backend.domain.member.repository.MemberRepository;
import com.gaebang.backend.domain.member.service.MemberService;
import com.gaebang.backend.global.springsecurity.PrincipalDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Transactional
@RequiredArgsConstructor
@Service
public class BoardService {

    private final BoardRepository boardRepository;
    private final ImageRepository imageRepository;
    private final CommentRepository commentRepository;
    private final BoardLikeRepository boardLikeRepository;
    private final CommentService commentService;
    private final MemberService memberService;
    private final TimeUtil timeUtil;

    // 검색 조건 있을 시 사용
    public Page<BoardListResponseDto> getBoardByCondition(String condition, Pageable pageable) {
        Page<BoardListProjectionDto> getDtos = boardRepository.findByCondition(condition, pageable);
        return transformBoardDtos(getDtos);
    }

    // 마이페이지 조회 시 사용
    public Page<BoardListResponseDto> getBoardByWriter(String writer, Pageable pageable) {
        Page<BoardListProjectionDto> getDtos = boardRepository.findByWriter(writer, pageable);
        return transformBoardDtos(getDtos);
    }

    // 정책 변경으로 일시적으로 주석
    /*public List<BoardResponseDto> getBoardByContent(String content, Pageable pageable) {
        Page<BoardResponseDto> getDtos = boardRepository.findByContent(content, pageable);

        return convertPageToList(getDtos);
    }*/

    // 검색 조건 없이 조회
    public Page<BoardListResponseDto> getBoard(Pageable pageable) {
        Page<BoardListProjectionDto> getDtos = boardRepository.findAllBoardDtos(pageable);
        return transformBoardDtos(getDtos);
    }

    // 게시판 생성
    public void createBoard(PrincipalDetails principalDetails, BoardCreateAndEditRequestDto boardCreateAndEditRequestDto) {
        Member loginMember = principalDetails.getMember();
        Board createBoard = BoardCreateAndEditRequestDto.toEntity(loginMember, boardCreateAndEditRequestDto);
        
        List<String> images = boardCreateAndEditRequestDto.imageUrl();
        Board saveBoard = boardRepository.save(createBoard);

        images.forEach(url -> {
            Image saveImageEntity = Image.builder()
                    .imageUrl(url)
                    .board(saveBoard)
                    .build();
            imageRepository.save(saveImageEntity);
        });
    }

    // 게시판 수정
    public void editBoard(Long boardId,
                          BoardCreateAndEditRequestDto boardCreateAndEditRequestDto,
                          PrincipalDetails principalDetails) {
        Long findMemberId = principalDetails.getMember().getId();

        Board findBoard = boardRepository.findByIdAndMemberId(boardId, findMemberId)
                .orElseThrow(BoardNotFoundException::new);
        findBoard.updateBoard(boardCreateAndEditRequestDto);

        List<Image> findImages = imageRepository.findByBoardId(findBoard.getId());
        imageRepository.deleteAll(findImages);

        List<String> imageUrls = boardCreateAndEditRequestDto.imageUrl();
        List<Image> createImages = new ArrayList<>();
        imageUrls.forEach(url -> {
            Image saveImageEntity = Image.builder()
                    .imageUrl(url)
                    .board(findBoard)
                    .build();
            createImages.add(saveImageEntity);
        });
        imageRepository.saveAll(createImages);
        boardRepository.save(findBoard);
    }

    // 게시글 상세 조회
    public BoardDetailResponseDto getBoardDetail(Long boardId, Pageable commentPageable, PrincipalDetails principalDetails) {
        Board findBoard = boardRepository.findBoardDetailById(boardId)
                .orElseThrow(BoardNotFoundException::new);
        findBoard.plusviewCount();

        String displayTime = timeUtil.getDisplayTime(findBoard.getCreatedAt());

        Long commentCount = commentRepository.countByBoardIdAndDeleteYn(boardId, "N");
        Long likeCount = boardLikeRepository.countByBoardId(boardId);
        Page<CommentResponseDto> comments = commentService.getCommentsByBoardId(boardId, commentPageable, principalDetails);

        return BoardDetailResponseDto.fromEntity(findBoard, displayTime, commentCount, likeCount, comments);
    }

    // 게시글 삭제
    public void deleteBoard(Long boardId, PrincipalDetails principalDetails) {
        Long findMemberId = principalDetails.getMember().getId();

        Board findBoard = boardRepository.findByIdAndMemberId(boardId, findMemberId)
                .orElseThrow(BoardNotFoundException::new);

        findBoard.softDelete();

        // 연관된 이미지들도 함께 삭제 (CASCADE 설정 고려)
        imageRepository.deleteByBoardId(boardId);
    }

    private Page<BoardListResponseDto> transformBoardDtos(Page<BoardListProjectionDto> projectionDtos) {
        return projectionDtos.map(dto ->
                BoardListResponseDto.builder()
                        .boardId(dto.boardId())
                        .title(dto.title())
                        .commentCount(dto.commentCount())
                        .writer(dto.writer())
                        .writerLevel(memberService.getMemberTierOrder(dto.writerLevel()))
                        .imageUrl(dto.imageUrl())
                        .createdDate(timeUtil.getDisplayTime(dto.createdDate()))
                        .viewCount(dto.viewCount())
                        .likeCount(dto.likeCount())
                        .build()
        );
    }
}
