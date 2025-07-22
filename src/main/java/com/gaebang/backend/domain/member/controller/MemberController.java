package com.gaebang.backend.domain.member.controller;

import com.gaebang.backend.domain.member.dto.request.ChangeNicknameRequestDto;
import com.gaebang.backend.domain.member.dto.request.ChangePasswordRequestDto;
import com.gaebang.backend.domain.member.dto.request.SignUpRequestDto;
import com.gaebang.backend.domain.member.dto.response.SignUpResponseDto;
import com.gaebang.backend.domain.member.dto.response.TestUserResponseDto;
import com.gaebang.backend.domain.member.entity.Member;
import com.gaebang.backend.domain.member.service.MemberService;
import com.gaebang.backend.global.springsecurity.PrincipalDetails;
import com.gaebang.backend.global.util.ResponseDTO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/member")
@RequiredArgsConstructor
public class MemberController {

    private final MemberService memberService;

    @PostMapping("/signup")
    public ResponseEntity<ResponseDTO<SignUpResponseDto>> signup(
            @Valid @RequestBody SignUpRequestDto signUpRequestDto) {
        SignUpResponseDto signUpResponseDto = memberService.signup(signUpRequestDto);
        ResponseDTO<SignUpResponseDto> response = ResponseDTO.okWithData(signUpResponseDto);
        return ResponseEntity
                .status(response.getCode())
                .body(response);
    }

    // Authenticated user 샘플테스트 코드입니다
    @GetMapping("/test/jwt")
    public ResponseEntity<ResponseDTO<TestUserResponseDto>> test(
            @AuthenticationPrincipal PrincipalDetails principalDetails) {
        Member member = principalDetails.getMember();

        TestUserResponseDto testUserResponseDto = TestUserResponseDto.fromEntity(member);
        ResponseDTO<TestUserResponseDto> response = ResponseDTO.okWithData(testUserResponseDto);
        return ResponseEntity
                .status(response.getCode())
                .body(response);
    }

    @GetMapping("/check-duplicated-email")
    public ResponseEntity<ResponseDTO<Void>> checkDuplicateEmail(
            @RequestParam String email) {
        memberService.checkDuplicateEmail(email);
        ResponseDTO<Void> response = ResponseDTO.ok();
        return ResponseEntity
                .status(response.getCode())
                .body(response);
    }

    @PatchMapping("/password")
    public ResponseEntity<ResponseDTO<Void>> changePassword(
            @AuthenticationPrincipal PrincipalDetails principalDetails,
            @Valid @RequestBody ChangePasswordRequestDto changePasswordRequestDto) {

        memberService.changePassword(principalDetails, changePasswordRequestDto);
        ResponseDTO<Void> response = ResponseDTO.okWithMessage("비밀번호가 성공적으로 변경되었습니다.");
        return ResponseEntity
                .status(response.getCode())
                .body(response);
    }

    @PatchMapping("/nickname")
    public ResponseEntity<ResponseDTO<Void>> changeNickname(
            @AuthenticationPrincipal PrincipalDetails principalDetails,
            @Valid @RequestBody ChangeNicknameRequestDto changeNicknameRequestDto) {

        memberService.changeNickname(principalDetails, changeNicknameRequestDto);
        ResponseDTO<Void> response = ResponseDTO.okWithMessage("닉네임이 성공적으로 변경되었습니다.");
        return ResponseEntity
                .status(response.getCode())
                .body(response);
    }

    @DeleteMapping("")
    public ResponseEntity<ResponseDTO<Void>> deleteMember(
            @AuthenticationPrincipal PrincipalDetails principalDetails) {

        memberService.deleteMember(principalDetails);
        ResponseDTO<Void> response = ResponseDTO.okWithMessage("회원탈퇴가 완료되었습니다.");
        return ResponseEntity
                .status(response.getCode())
                .body(response);
    }
}