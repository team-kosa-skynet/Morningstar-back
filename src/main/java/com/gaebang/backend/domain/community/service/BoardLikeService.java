package com.gaebang.backend.domain.community.service;

import com.gaebang.backend.domain.community.dto.response.BoardLikeResponseDto;
import com.gaebang.backend.domain.community.entity.Board;
import com.gaebang.backend.domain.community.entity.BoardLike;
import com.gaebang.backend.domain.community.exception.BoardNotFoundException;
import com.gaebang.backend.domain.community.repository.BoardLikeRepository;
import com.gaebang.backend.domain.community.repository.BoardRepository;
import com.gaebang.backend.domain.member.entity.Member;
import com.gaebang.backend.global.springsecurity.PrincipalDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Transactional
@RequiredArgsConstructor
@Service
public class BoardLikeService {

    private final BoardLikeRepository boardLikeRepository;
    private final BoardRepository boardRepository;

    // 게시글 좋아요(토글)
    public BoardLikeResponseDto togglePostLike(Long boardId, PrincipalDetails principalDetails) {
        Member loginMember = principalDetails.getMember();
        Board findBoard = boardRepository.findById(boardId)
                .orElseThrow(BoardNotFoundException::new);

        Optional<BoardLike> findBoardLike = boardLikeRepository.findByBoardIdAndMemberId(boardId, loginMember.getId());

        if (findBoardLike.isPresent()) {
            return removeBoardLike(boardId, findBoardLike.get());
        } else {
            return addBoardLike(boardId, loginMember, findBoard);
        }
    }

    private BoardLikeResponseDto removeBoardLike(Long boardId, BoardLike findBoardLike) {
        boardLikeRepository.delete(findBoardLike);
        Long boardLikeCount = boardLikeRepository.countByBoardId(boardId);

        return BoardLikeResponseDto.builder()
                .liked(false)
                .likeCount(boardLikeCount)
                .message("좋아요를 제거했습니다.")
                .build();
    }

    private BoardLikeResponseDto addBoardLike(Long boardId, Member loginMember, Board findBoard) {
        BoardLike createBoardLike = BoardLike.builder()
                .member(loginMember)
                .board(findBoard)
                .build();

        boardLikeRepository.save(createBoardLike);
        Long boardLikeCount = boardLikeRepository.countByBoardId(boardId);

        return BoardLikeResponseDto.builder()
                .liked(true)
                .likeCount(boardLikeCount)
                .message("좋아요를 추가했습니다.")
                .build();
    }

}
