package com.gaebang.backend.domain.member.service;

import com.gaebang.backend.domain.member.dto.request.ChangePasswordRequestDto;
import com.gaebang.backend.domain.member.dto.request.SignUpRequestDto;
import com.gaebang.backend.domain.member.dto.response.SignUpResponseDto;
import com.gaebang.backend.domain.member.entity.Member;
import com.gaebang.backend.domain.member.exception.EmailDuplicateException;
import com.gaebang.backend.domain.member.exception.InvalidPasswordException;
import com.gaebang.backend.domain.member.exception.NewPasswordSameAsOldException;
import com.gaebang.backend.domain.member.repository.MemberRepository;
import com.gaebang.backend.global.springsecurity.PrincipalDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Service
@Transactional
@RequiredArgsConstructor
public class MemberService {

    private final MemberRepository memberRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    public SignUpResponseDto signup(SignUpRequestDto signUpRequestDto) {

        memberRepository.findByMemberBaseEmail(signUpRequestDto.email()).ifPresent(user -> {
            throw new EmailDuplicateException();
        });


        String encodedPassword = passwordEncoder.encode(signUpRequestDto.password());
        String generatedNickname = "";

        while (true) {
            generatedNickname = generateName();
            Optional<Member> member = memberRepository.findByMemberBase_Nickname(generatedNickname);
            if (member.isEmpty()) {
                break;
            }
        }

        Member newMember = signUpRequestDto.toEntity(encodedPassword,generatedNickname);
        memberRepository.save(newMember);
        return SignUpResponseDto.fromEntity(newMember);
    }

    public void checkDuplicateEmail(String email) {
        memberRepository.findByMemberBaseEmail(email).ifPresent(user -> {
            throw new EmailDuplicateException();
        });
    }

    public String generateName() {
        List<String> part1 = Arrays.asList(
                "자유로운", "행복한", "당당한", "수줍은", "용기있는", "엉뚱한", "신나는",
                "장난꾸러기", "호기심많은", "커피마시는", "코딩하는", "산책하는", "노래하는", "상상하는",
                "게으른", "부지런한", "슬기로운", "사랑스러운", "단순한", "긍정적인", "평화로운",
                "솔직한", "의리있는", "매력적인", "배고픈", "피곤한", "여행하는", "꿈꾸는", "웃기는"
        );

        List<String> part2 = Arrays.asList(
                "숲속의", "바다의", "도시의", "우주속의", "구름위의", "도서관의", "남극의", "사막의",
                "하얀", "까만", "노란", "파란", "빨간", "초록", "보라색", "무지개색", "황금색",
                "새벽의", "아침의", "점심의", "저녁의", "한밤중의", "비밀스러운", "솜털같은", "반짝이는",
                "줄무늬", "물방울무늬", "그림자속", "언덕위의", "동굴속"
        );

        List<String> part3 = Arrays.asList(
                "사자", "호랑이", "코끼리", "강아지", "고양이", "쿼카", "카피바라", "알파카",
                "펭귄", "북극곰", "다람쥐", "고슴도치", "부엉이", "미어캣", "너구리", "수달",
                "돌고래", "거북이", "악어", "카멜레온", "치타", "기린", "판다", "코알라",
                "별", "달", "해"
        );

        Collections.shuffle(part1);
        Collections.shuffle(part2);
        Collections.shuffle(part3);

        // 띄어쓰기 없이 조합
        return part1.get(0) + part2.get(0) + part3.get(0);
        // 띄어쓰기를 넣어 가독성을 높이려면 아래 코드를 사용
        // return part1.get(0) + " " + part2.get(0) + " " + part3.get(0);
    }

    public void changePassword(
            PrincipalDetails principalDetails, ChangePasswordRequestDto changePasswordRequestDto) {
        Member member = principalDetails.getMember();

        String currentPassword = member.getMemberBase().getPassword();
        String newPassword = changePasswordRequestDto.newPassword();

        //비밀번호 일치 검증
        if (!passwordEncoder.matches(changePasswordRequestDto.currentPassword(), currentPassword)) {
            throw new InvalidPasswordException();
        }

        //새 비밀번호, 현재 비밀번호와 동일여부  최종 검증
        if (passwordEncoder.matches(newPassword, currentPassword)) {
            throw new NewPasswordSameAsOldException();
        }

        String encodedNewPassword = passwordEncoder.encode(newPassword);
        member.getMemberBase().changePassword(encodedNewPassword);
        memberRepository.save(member);
    }
}

