package com.gaebang.backend.global.springsecurity;

import com.gaebang.backend.domain.member.service.MemberService;
import com.gaebang.backend.global.jwt.JwtProvider;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * @implNote 해당 클래스는 SimpleUrlAuthenticationSuccessHandler를 상속받은 OAuth 로그인 성공 후 로직을 처리 하는 클래스
 * 로그인 성공 후 리디렉트 하게 설정 했습니다.
 * 프론트 배포사이트 -> http://localhost:5173/auth/social
 * https://tripcometrue.vercel.app
 * 스프링 코드 내로 리디렉트 설정 하고 싶은 경우
 * String redirectUrl = "/user/oauth-success?token="+token;
 */
@Component
@RequiredArgsConstructor
public class OAuth2LoginSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final JwtProvider jwtProvider;
    private final MemberService memberService;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException, ServletException {

        PrincipalDetails principalDetails = (PrincipalDetails) authentication.getPrincipal();
        String token = jwtProvider.createToken(principalDetails.getMember());
        Long memberId = principalDetails.getMember().getId();

        String email = principalDetails.getEmail();
        String name = principalDetails.getUsername();
        String role = principalDetails.getMember().getMemberBase().getAuthority();
        int point = principalDetails.getMember().getPoints();
        int level = memberService.getMemberTierOrder(principalDetails.getMember());

        //한국어 인코딩 설정
        String encodedName = URLEncoder.encode(name, StandardCharsets.UTF_8);

        String redirectUrl = "http://www.gaebang.site/auth/social?email=" + email + "&name=" + encodedName
                + "&token=" + token + "&userId=" + memberId + "&role=" + role + "&point=" + point+ "&level=" + level;
        getRedirectStrategy().sendRedirect(request, response, redirectUrl);
    }
}
