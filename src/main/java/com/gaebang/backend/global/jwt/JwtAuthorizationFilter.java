package com.gaebang.backend.global.jwt;

import com.gaebang.backend.domain.member.repository.MemberRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import com.gaebang.backend.global.springsecurity.PrincipalDetails;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * @author liyusang1
 * @implNote JWT를 이용한 인가 (Authorization) 코드
 */
@Component
public class JwtAuthorizationFilter extends OncePerRequestFilter {

    private final MemberRepository memberRepository;
    private final JwtProvider jwtProvider;

    public JwtAuthorizationFilter(
            MemberRepository memberRepository,
            JwtProvider jwtProvider
    ) {
        this.memberRepository = memberRepository;
        this.jwtProvider = jwtProvider;
    }

    /**
     * header가 아닌 cookie에서 토큰을 가져오려고 하는 경우 아래와 같이 바꾸면 된다.
     * accessToken = Arrays.stream(request.getCookies())
     * .filter(cookie ->cookie.getName().equals(JwtProperties.COOKIE_NAME)).findFirst().map(Cookie::getValue).orElse(null);
     */
    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain
    ) throws IOException, ServletException {
        //header에서 가져옴
        List<String> headerValues = Collections.list(request.getHeaders("Authorization"));
        String accessToken = headerValues.stream()
                .findFirst()
                .map(header -> header.replace("Bearer ", ""))
                .orElse(null);

        //현재 토큰을 사용 하여 인증을 시도 합니다.
        Authentication authentication = getUsernamePasswordAuthenticationToken(accessToken);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        chain.doFilter(request, response);
    }

    /**
     * JWT 토큰으로 User를 찾아서 UsernamePasswordAuthenticationToken를 만들어서 반환한다.
     */
    private Authentication getUsernamePasswordAuthenticationToken(String token) {
        if (token == null) {
            return null;
        }
        String email = jwtProvider.getEmail(token);
        if (email != null) {
            return memberRepository.findByMemberBaseEmail(email)
                    .map(PrincipalDetails::new)
                    .map(principalDetails -> new UsernamePasswordAuthenticationToken(
                            principalDetails, // principal
                            null, // credentials
                            principalDetails.getAuthorities()
                    )).orElseThrow(IllegalAccessError::new);
        }
        return null; // 유저가 없으면 NULL
    }
}