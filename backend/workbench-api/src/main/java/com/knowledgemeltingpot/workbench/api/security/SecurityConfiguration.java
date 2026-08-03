package com.knowledgemeltingpot.workbench.api.security;

import com.knowledgemeltingpot.workbench.application.port.PasswordHasher;
import com.knowledgemeltingpot.workbench.application.port.UserRepository;
import com.knowledgemeltingpot.workbench.application.service.UserAccountService;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class SecurityConfiguration {

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    UserAccountService userAccountService(UserRepository userRepository, PasswordHasher passwordHasher, Clock clock) {
        return new UserAccountService(userRepository, passwordHasher, clock);
    }

    @Bean
    AuthenticationManager authenticationManager(UserDetailsService userDetailsService, PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityProblemWriter problemWriter,
            AccountPolicyFilter accountPolicyFilter,
            @Value("${workbench.security.secure-cookies:false}") boolean secureCookies) throws Exception {
        CookieCsrfTokenRepository csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepository.setCookieCustomizer(cookie -> cookie.path("/").sameSite("Strict").secure(secureCookies));

        http
                .csrf(csrf -> csrf.csrfTokenRepository(csrfRepository))
                .cors(cors -> cors.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/actuator/health/**",
                                "/api/v1/auth/csrf", "/api/v1/auth/login").permitAll()
                        .requestMatchers("/api/v1/users", "/api/v1/users/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/auth/password", "/api/v1/auth/me", "/api/v1/auth/logout")
                            .authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/scenes/*/releases").hasAnyRole("PUBLISHER", "ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/scenes/*/release-validations").hasAnyRole("PUBLISHER", "ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/**").hasAnyRole("OPERATOR", "ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/**").hasAnyRole("OPERATOR", "ADMIN")
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/**").hasAnyRole("OPERATOR", "ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/**").hasAnyRole("OPERATOR", "ADMIN")
                        .anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) -> problemWriter.write(response,
                                HttpServletResponse.SC_UNAUTHORIZED, "Authentication required", "Please sign in",
                                "authentication-required"))
                        .accessDeniedHandler((request, response, exception) -> problemWriter.write(response,
                                HttpServletResponse.SC_FORBIDDEN, "Access denied", "Insufficient permission",
                                "access-denied")))
                .logout(logout -> logout
                        .logoutUrl("/api/v1/auth/logout")
                        .deleteCookies("KMP_SESSION", "XSRF-TOKEN")
                        .invalidateHttpSession(true)
                        .clearAuthentication(true)
                        .logoutSuccessHandler((request, response, authentication) ->
                                response.setStatus(HttpServletResponse.SC_NO_CONTENT)))
                .sessionManagement(session -> session.sessionFixation(fixation -> fixation.migrateSession()))
                .addFilterBefore(accountPolicyFilter, AuthorizationFilter.class)
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                        .referrerPolicy(referrer -> referrer
                                .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)));
        return http.build();
    }
}
