package com.gaebang.backend.domain.community.service;

import com.gaebang.backend.domain.community.dto.reqeust.BoardReportRequestDto;
import com.gaebang.backend.domain.community.dto.response.BoardReportResponseDto;
import com.gaebang.backend.domain.community.entity.Board;
import com.gaebang.backend.domain.community.entity.BoardReport;
import com.gaebang.backend.domain.community.exception.BoardNotFoundException;
import com.gaebang.backend.domain.community.exception.BoardReportNotFoundException;
import com.gaebang.backend.domain.community.exception.DuplicateReportException;
import com.gaebang.backend.domain.community.repository.BoardReportRepository;
import com.gaebang.backend.domain.community.repository.BoardRepository;
import com.gaebang.backend.global.springsecurity.PrincipalDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Transactional
@RequiredArgsConstructor
@Service
public class BoardReportService {

    private final BoardRepository boardRepository;
    private final BoardReportRepository boardReportRepository;

    public void createBoardReport(BoardReportRequestDto boardReportRequestDto,
                                  PrincipalDetails principalDetails) {

        Long getBoardId = boardReportRequestDto.boardId();
        Long getMemberId = principalDetails.getMember().getId();

        Board findBoard = boardRepository.findById(getBoardId)
                .orElseThrow(BoardNotFoundException::new);

        validateDuplicateReport(getBoardId, getMemberId);

        BoardReport createBoardReport = boardReportRequestDto.toEntity(principalDetails.getMember(),
                findBoard);

        boardReportRepository.save(createBoardReport);
    }

    public Page<BoardReportResponseDto> getBoardReports(Pageable pageable) {
        Page<BoardReport> findBoardReport = boardReportRepository.findAll(pageable);
        return findBoardReport.map(BoardReportResponseDto::fromEntity);
    }

    public void deleteBoardReport(Long boardReportId) {
        boardReportRepository.findById(boardReportId)
                .orElseThrow(BoardReportNotFoundException::new);

        boardReportRepository.deleteById(boardReportId);
    }

    private void validateDuplicateReport(Long getBoardId, Long getMemberId) {
        Optional<BoardReport> findBoardReport = boardReportRepository.findByBoardIdAndMemberId(getBoardId,
                getMemberId);

        if (findBoardReport.isPresent()) {
            throw new DuplicateReportException();
        }
    }
}
