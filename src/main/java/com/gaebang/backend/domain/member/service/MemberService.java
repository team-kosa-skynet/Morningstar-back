package com.gaebang.backend.domain.member.service;

import com.gaebang.backend.domain.member.dto.request.*;
import com.gaebang.backend.domain.member.dto.response.GetUserIdByEmailResponseDto;
import com.gaebang.backend.domain.member.dto.response.GetUserResponseDto;
import com.gaebang.backend.domain.member.dto.response.SignUpResponseDto;
import com.gaebang.backend.domain.member.entity.Member;
import com.gaebang.backend.domain.member.exception.*;
import com.gaebang.backend.domain.member.repository.MemberRepository;
import com.gaebang.backend.domain.pointTier.repository.PointTierRepository;
import com.gaebang.backend.global.springsecurity.PrincipalDetails;
import com.gaebang.backend.global.util.NicknameGenerator;
import com.gaebang.backend.global.util.ResponseDTO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@Transactional
@RequiredArgsConstructor
public class MemberService {

    private final MemberRepository memberRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final PointTierRepository pointTierRepository;

    public SignUpResponseDto signup(SignUpRequestDto signUpRequestDto) {

        memberRepository.findByMemberBaseEmail(signUpRequestDto.email()).ifPresent(user -> {
            throw new EmailDuplicateException();
        });


        String encodedPassword = passwordEncoder.encode(signUpRequestDto.password());
        String generatedNickname = "";

        while (true) {
            generatedNickname = NicknameGenerator.generateName();
            Optional<Member> member = memberRepository.findByMemberBase_Nickname(generatedNickname);
            if (member.isEmpty()) {
                break;
            }
        }

        Member newMember = signUpRequestDto.toEntity(encodedPassword, generatedNickname);
        memberRepository.save(newMember);
        return SignUpResponseDto.fromEntity(newMember);
    }

    public void checkDuplicateEmail(String email) {
        memberRepository.findByMemberBaseEmail(email).ifPresent(user -> {
            throw new EmailDuplicateException();
        });
    }

    public void checkDuplicateNickname(String nickname) {
        memberRepository.findByMemberBase_Nickname(nickname).ifPresent(user -> {
            throw new NicknameAlreadyExistsException();
        });
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

    public void changeNickname(PrincipalDetails principalDetails,
                               @Valid ChangeNicknameRequestDto changeNicknameRequestDto) {

        memberRepository.findByMemberBase_Nickname(changeNicknameRequestDto.nickname())
                .ifPresent(user -> {
                    throw new NicknameAlreadyExistsException();
                });

        Member member = memberRepository.findById(principalDetails.getMember().getId())
                .orElseThrow(UserNotFoundException::new);

        member.getMemberBase().changeNickname(changeNicknameRequestDto.nickname());
    }

    public void deleteMember(PrincipalDetails principalDetails) {

        Member member = memberRepository.findById(principalDetails.getMember()
                .getId()).orElseThrow(UserNotFoundException::new);

        memberRepository.delete(member);
    }

    public ResponseDTO<GetUserResponseDto> getMemberInfo(PrincipalDetails principalDetails) {
        int level = getMemberTierOrder(principalDetails.getMember());
        return ResponseDTO.okWithData(GetUserResponseDto.fromEntity(principalDetails.getMember(),level));
    }

    public int getMemberTierOrder(Member member) {
        return pointTierRepository.findTierOrderByPoints(member.getPoints());
    }

    public int getMemberTierOrder(int memberPoints) {
        return pointTierRepository.findTierOrderByPoints(memberPoints);
    }

    public void checkPassword(PrincipalDetails principalDetails,
                              @Valid CheckPasswordRequestDto checkPasswordRequestDto) {
        Member member = principalDetails.getMember();
        String currentPassword = member.getMemberBase().getPassword();

        if (!passwordEncoder.matches(checkPasswordRequestDto.currentPassword(), currentPassword)) {
            throw new InvalidPasswordException();
        }
    }

    public void changePasswordByUserId(Long userId,
                                       @Valid ChangePasswordByUserIdRequestDto changePasswordByUserIdRequestDto) {

        Member member = memberRepository.findById(userId).orElseThrow(UserNotFoundException::new);

        String encodedNewPassword = passwordEncoder.encode(changePasswordByUserIdRequestDto.newPassword());
        member.getMemberBase().changePassword(encodedNewPassword);
        memberRepository.save(member);
    }

    public ResponseDTO<GetUserIdByEmailResponseDto> getUserIdByEmail(String email) {

        Member member = memberRepository.findByMemberBaseEmail(email).orElseThrow(UserNotFoundException::new);
        return ResponseDTO.okWithData(GetUserIdByEmailResponseDto.fromEntity(member));
    }
}

