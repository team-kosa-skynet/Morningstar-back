package com.gaebang.backend.global.jwt;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaebang.backend.domain.member.dto.request.LoginRequestDto;
import com.gaebang.backend.domain.member.dto.response.LoginResponseDto;
import com.gaebang.backend.domain.member.entity.Member;
import com.gaebang.backend.domain.member.repository.MemberRepository;
import com.gaebang.backend.domain.member.service.MemberService;
import com.gaebang.backend.global.springsecurity.PrincipalDetails;
import com.gaebang.backend.global.util.ResponseDTO;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * @implNote JWT를 이용한 로그인 인증 (Authentication) 코드
 */
@Component
public class JwtAuthenticationFilter extends UsernamePasswordAuthenticationFilter {

    private final JwtProvider jwtProvider;
    MemberRepository memberRepository;
    MemberService memberService;

    public JwtAuthenticationFilter(
            AuthenticationManager authenticationManager,
            JwtProvider jwtProvider,
            MemberRepository memberRepository,
            MemberService memberService
    ) {
        super.setAuthenticationManager(authenticationManager);
        this.jwtProvider = jwtProvider;
        this.memberRepository = memberRepository;
        this.memberService = memberService;
    }

    /**
     * 로그인 인증 시도
     */
    @Override
    public Authentication attemptAuthentication(
            HttpServletRequest request,
            HttpServletResponse response
    ) throws AuthenticationException {
        try {
            // 요청된 JSON 데이터를 객체로 파싱
            ObjectMapper objectMapper = new ObjectMapper();
            LoginRequestDto loginRequest = objectMapper.readValue(request.getInputStream(),
                    LoginRequestDto.class);

            // 로그인할 때 입력한 email과 password를 가지고 authenticationToken를 생성
            UsernamePasswordAuthenticationToken authenticationToken = new UsernamePasswordAuthenticationToken(
                    loginRequest.email(),
                    loginRequest.password(),
                    new ArrayList<>(List.of(new SimpleGrantedAuthority("ROLE_USER")))
            );

            return this.getAuthenticationManager().authenticate(authenticationToken);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 인증 성공시 쿠키에 jwt토큰을 담으려면 아래와 같이 바꾸면 됨
     * Cookie cookie = new Cookie(JwtProperties.COOKIE_NAME,token);
     * cookie.setMaxAge(JwtProperties.ACCESS_TOKEN_EXPIRATION_TIME / 1000 * 2);
     * // setMaxAge는 초단위
     * cookie.setSecure(true);
     * cookie.setPath("/");
     * response.addCookie(cookie)
     * 발급후 redirect로 이동 클라이언트에게 http 리다이렉션 요청 코드 response.sendRedirect("/");
     */
    @Override
    protected void successfulAuthentication(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain,
            Authentication authResult
    ) throws IOException {
        Member member = ((PrincipalDetails) authResult.getPrincipal()).getMember();
        String token = jwtProvider.createToken(member);

        int level = memberService.getMemberTierOrder(member);
        LoginResponseDto loginResponseDto = LoginResponseDto.fromEntity(member, token, level);
        ResponseDTO<LoginResponseDto> loginResponse = ResponseDTO.okWithData(loginResponseDto);

        sendJsonResponse(response, loginResponse, HttpStatus.OK);
    }

    /**
     * 인증실패
     */
    @Override
    protected void unsuccessfulAuthentication(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception
    ) throws IOException, ServletException {
        String authenticationErrorMessage = getAuthenticationErrorMessage(exception);

        ResponseDTO<Void> errorResponse = ResponseDTO.errorWithMessage(HttpStatus.BAD_REQUEST,
                authenticationErrorMessage);
        sendJsonResponse(response, errorResponse, HttpStatus.BAD_REQUEST);
    }

    private void sendJsonResponse(HttpServletResponse response, Object responseData,
                                  HttpStatus httpStatus) throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);

        String jsonResponse = objectMapper.writeValueAsString(responseData);

        response.setStatus(httpStatus.value());
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(jsonResponse);
    }

    private String getAuthenticationErrorMessage(AuthenticationException exception) {
        if (exception instanceof BadCredentialsException) {
            return "이메일 또는 비밀번호 에러";
        } else if (exception instanceof UsernameNotFoundException) {
            return "존재하지 않는 유저";
        } else {
            return "인증 실패";
        }
    }
}