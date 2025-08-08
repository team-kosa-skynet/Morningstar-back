package com.gaebang.backend.global.springsecurity;

import com.gaebang.backend.global.jwt.JwtAuthenticationFilter;
import com.gaebang.backend.global.jwt.JwtAuthorizationFilter;
import com.gaebang.backend.global.util.CustomResponseUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.servlet.handler.HandlerMappingIntrospector;

import java.util.Arrays;

@Configuration
@RequiredArgsConstructor
public class SpringSecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final JwtAuthorizationFilter jwtAuthorizationFilter;
    private final PrincipalOauth2UserService principalOauth2UserService;
    private final OAuth2LoginSuccessHandler oAuth2LoginSuccessHandler;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   HandlerMappingIntrospector introspector) throws Exception {

        // CORS 설정 추가
        http.cors(cors -> cors
                .configurationSource(request -> {
                    CorsConfiguration configuration = new CorsConfiguration();
                    configuration.applyPermitDefaultValues();
                    configuration.setAllowCredentials(true);
                    configuration.addAllowedOriginPattern("");
                    configuration.addAllowedOriginPattern("http://localhost:8081");
                    configuration.addAllowedOriginPattern("http://localhost:3000");
                    configuration.addAllowedOriginPattern("https://gaebang.site");
                    configuration.addAllowedOriginPattern("http://gaebang.site");
                    configuration.addAllowedOriginPattern("https://www.gaebang.site");
                    configuration.addAllowedOriginPattern("http://www.gaebang.site");
                    configuration.setAllowedMethods(
                            Arrays.asList("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD"));

                    // 다른 도메인도 필요에 따라 추가
                    configuration.setAllowCredentials(true); // 쿠키를 포함한 크로스 도메인 요청을 허용
                    return configuration;
                }));

        // basic authentication
        http.httpBasic(AbstractHttpConfigurer::disable); // basic authentication filter 비활성화
        // csrf
        http.csrf(AbstractHttpConfigurer::disable);
        // remember-me
        http.rememberMe(AbstractHttpConfigurer::disable);
        // stateless
        http.sessionManagement(
                session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        // jwt filter
        http.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthorizationFilter, BasicAuthenticationFilter.class);

        http.authorizeHttpRequests(authz -> authz
                /*
                .requestMatchers(new AntPathRequestMatcher("ant matcher")).authenticated()
                .requestMatchers(new AntPathRequestMatcher("role sample")).hasRole("ADMIN")
                .requestMatchers(HttpMethod.OPTIONS, "/basket/**").permitAll() // OPTIONS 메서드에 대한 권한 허용
                .requestMatchers(new AntPathRequestMatcher("role sample", HttpMethod.POST.name())).hasRole("ADMIN") */
                .requestMatchers(new AntPathRequestMatcher("/login/**")).permitAll()
                .requestMatchers(new AntPathRequestMatcher("/api/member/signup")).permitAll()
                .requestMatchers(new AntPathRequestMatcher("/api/member/check-duplicated-email/**")).permitAll()
                .requestMatchers(new AntPathRequestMatcher("/api/member/check-duplicated-nickname/**")).permitAll()
                .requestMatchers(new AntPathRequestMatcher("/api/member/password/user/**")).permitAll()
                .requestMatchers(new AntPathRequestMatcher("/api/member/id")).permitAll()
                .requestMatchers(new AntPathRequestMatcher("/api/member/test/jwt")).permitAll()
                .requestMatchers(new AntPathRequestMatcher("/api/boards/search")).permitAll()
                .requestMatchers(new AntPathRequestMatcher("/api/boards")).permitAll()
                .requestMatchers(new AntPathRequestMatcher("/api/boards/{boardId}")).permitAll()
                .requestMatchers(new AntPathRequestMatcher("/api/s3/upload/**")).permitAll()
                .requestMatchers(new AntPathRequestMatcher("/items/**")).permitAll()
                .requestMatchers(new AntPathRequestMatcher("/free-boards/**")).permitAll()
                .requestMatchers(new AntPathRequestMatcher("/reply/free-board/**")).permitAll()
                .requestMatchers(new AntPathRequestMatcher("/reply/item/**")).permitAll()
                .requestMatchers(new AntPathRequestMatcher("/member/item/**")).permitAll()
                .requestMatchers(new AntPathRequestMatcher("/member/{memberId}")).permitAll()
                .requestMatchers(new AntPathRequestMatcher("/api/email/**")).permitAll()
                .requestMatchers(new AntPathRequestMatcher("/api/payment/redirect/**")).permitAll()
                .requestMatchers(new AntPathRequestMatcher("/api/news/**")).permitAll()
                .requestMatchers(new AntPathRequestMatcher("/api/interview/**")).permitAll()
                .anyRequest().authenticated());

        /**
         * @implNote 사용자 프로필 정보를 가져오고 그 정보를 토대로 회원가입을 자동으로 진행
         * 정보가 추가 적으로 필요하면 추가적으로 요구 받아야함
         * OAuth 완료가 되면 엑세스토큰 + 사용자 프로필 정보를 한번에 받음 로그인 성공시 principalOauth2UserService에서 처리 후
         * oAuth2LoginSuccessHandler에서 리디렉트 처리
         */
        http.oauth2Login(oauth2 -> oauth2
                .userInfoEndpoint(
                        userInfoEndpoint -> userInfoEndpoint.userService(principalOauth2UserService))
                .successHandler(oAuth2LoginSuccessHandler)
        );

        http.exceptionHandling(exceptionHandling -> {
            exceptionHandling.authenticationEntryPoint(
                    (request, response, authException) -> CustomResponseUtil.fail(response,
                            "로그인을 진행해 주세요", HttpStatus.UNAUTHORIZED));

            exceptionHandling.accessDeniedHandler(
                    (request, response, accessDeniedException) -> CustomResponseUtil.fail(response,
                            "접근 권한이 없습니다", HttpStatus.FORBIDDEN));
        });

        return http.build();
    }
}