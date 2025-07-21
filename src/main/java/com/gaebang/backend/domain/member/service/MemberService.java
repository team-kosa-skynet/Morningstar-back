package com.gaebang.backend.domain.member.service;

import com.gaebang.backend.domain.member.dto.request.PasswordRequestDto;
import com.gaebang.backend.domain.member.dto.request.SignUpRequestDto;
import com.gaebang.backend.domain.member.dto.response.SignUpResponseDto;
import com.gaebang.backend.domain.member.entity.Member;
import com.gaebang.backend.domain.member.exception.CurrentPasswordNotMatchException;
import com.gaebang.backend.domain.member.exception.EmailDuplicateException;
import com.gaebang.backend.domain.member.exception.NewPasswordNotMatchException;
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

        Member newMember = signUpRequestDto.toEntity(encodedPassword, generateName());
        memberRepository.save(newMember);
        return SignUpResponseDto.fromEntity(newMember);
    }

    public void checkDuplicateEmail(String email) {
        memberRepository.findByMemberBaseEmail(email).ifPresent(user -> {
            throw new EmailDuplicateException();
        });
    }

    public String generateName() {
        List<String> first = Arrays.asList("자유로운", "서운한",
                "당당한", "배부른", "수줍은", "멋있는",
                "용기있는", "심심한", "잘생긴", "이쁜", "눈웃음치는", "행복한", "사랑스러운", "순수한");
        List<String> name = Arrays.asList("사자", "코끼리", "호랑이", "곰", "여우", "늑대", "너구리",
                "참새", "고슴도치", "강아지", "고양이", "거북이", "토끼", "앵무새", "하이에나", "펭귄", "하마",
                "얼룩말", "치타", "악어", "기린", "수달", "염소", "다람쥐", "판다", "코알라", "앵무새", "독수리", "알파카");
        Collections.shuffle(first);
        Collections.shuffle(name);
        return first.get(0) + name.get(0);
    }

    public void changePassword(
            PrincipalDetails principalDetails, PasswordRequestDto passwordRequestDto) {
        Member member = getLoginMember(principalDetails);

        String currentPassword = member.getMemberBase().getPassword();
        String newPassword = passwordRequestDto.newPassword();

        //새 비밀번호, 현재 비밀번호와 동일여부  최종 검증
        if (passwordEncoder.matches(newPassword, currentPassword)) {
            throw new NewPasswordSameAsOldException();
        }

        //새비밀번호 재입력값 동일여부 최종 검증
        if (!passwordRequestDto.newPassword().equals(passwordRequestDto.confirmPassword())) {
            throw new NewPasswordNotMatchException();
        }

        String encodedNewPassword = passwordEncoder.encode(newPassword);
        member.getMemberBase().changePassword(encodedNewPassword);
        memberRepository.save(member);
    }

    public void checkPassword(
            PrincipalDetails principalDetails, PasswordRequestDto passwordRequestDto) {
        Member member = principalDetails.getMember();
        String currentPassword = member.getMemberBase().getPassword();
        String inPuttedCurrentPassword = passwordRequestDto.currentPassword();
        String newPassword = passwordRequestDto.newPassword();

        // 현재 비밀번호만 입력된 경우
        if (passwordRequestDto.currentPassword() != null
                && passwordRequestDto.newPassword() == null) {
            if (!passwordEncoder.matches(passwordRequestDto.currentPassword(), currentPassword)) {
                throw new CurrentPasswordNotMatchException();
            }
        }

        // 새 비밀번호만 입력된 경우
        if (passwordRequestDto.newPassword() != null
                && passwordRequestDto.currentPassword() == null) {
            if (passwordEncoder.matches(passwordRequestDto.newPassword(), currentPassword)) {
                throw new NewPasswordSameAsOldException();
            }
        }

        // 새 비밀번호 확인이 입력된 경우
        if (passwordRequestDto.confirmPassword() != null) {
            if (!passwordRequestDto.newPassword().equals(passwordRequestDto.confirmPassword())) {
                throw new NewPasswordNotMatchException();
            }
        }
    }

    public Member getLoginMember(PrincipalDetails principalDetails) {
        Member member = principalDetails.getMember();
        return member;
    }
}

