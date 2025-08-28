package com.gaebang.backend.domain.community.service;

import com.gaebang.backend.domain.community.repository.BoardRepository;
import com.gaebang.backend.domain.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@RequiredArgsConstructor
@Service
public class AIAssistantService {

    private final MemberRepository memberRepository;
    private final BoardRepository boardRepository;



}
