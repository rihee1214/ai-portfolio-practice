package com.example.marketplace.identity.web;

import com.example.marketplace.identity.infrastructure.ArgonPasswordHasher;
import com.example.marketplace.identity.infrastructure.LoginMembers;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.DelegatingFilterProxyRegistrationBean;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.session.jdbc.config.annotation.web.http.EnableJdbcHttpSession;
import org.springframework.session.web.http.DefaultCookieSerializer;
import org.springframework.session.web.http.SessionRepositoryFilter;

@Configuration(proxyBeanMethods = false)
@Profile("db")
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
@EnableWebSecurity
@EnableJdbcHttpSession(tableName = "marketplace.spring_session", maxInactiveIntervalInSeconds = 1800)
public class AuthConfiguration {
    @Bean
    LoginMembers loginMembers(NamedParameterJdbcTemplate jdbc) { return new LoginMembers(jdbc); }

    @Bean
    DefaultCookieSerializer cookieSerializer(@Value("${marketplace.session.secure:true}") boolean secure) {
        var cookie = new DefaultCookieSerializer();
        cookie.setCookieName("SESSION");
        cookie.setCookiePath("/");
        cookie.setUseHttpOnlyCookie(true);
        cookie.setUseSecureCookie(secure);
        cookie.setSameSite("Lax");
        return cookie;
    }

    @Bean
    FilterRegistrationBean<SessionRepositoryFilter<?>> sessionRegistration(SessionRepositoryFilter<?> filter) {
        var registration = new FilterRegistrationBean<SessionRepositoryFilter<?>>(filter);
        registration.setOrder(-110);
        return registration;
    }

    @Bean
    DelegatingFilterProxyRegistrationBean securityRegistration() {
        var registration = new DelegatingFilterProxyRegistrationBean("springSecurityFilterChain");
        registration.setOrder(-100);
        return registration;
    }

    @Bean
    SecurityFilterChain authenticationChain(HttpSecurity http, LoginMembers members,
            com.example.marketplace.identity.api.AccessPolicy access) throws Exception {
        var hasher = new ArgonPasswordHasher();
        var provider = new DaoAuthenticationProvider(members);
        provider.setPasswordEncoder(new PasswordEncoder() {
            @Override
            public String encode(CharSequence raw) { return hasher.encode(raw.toString()); }
            @Override
            public boolean matches(CharSequence raw, String encoded) {
                return raw != null && raw.length() <= 256 && hasher.matches(raw.toString(), encoded);
            }
        });
        http.securityMatcher("/api/**")
                .authenticationManager(new org.springframework.security.authentication.ProviderManager(provider))
                .requestCache(cache -> cache.disable())
                .csrf(csrf -> csrf.csrfTokenRepository(new HttpSessionCsrfTokenRepository()))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/auth/csrf").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/auth/login",
                                "/api/auth/signup", "/api/auth/verify-email",
                                "/api/auth/password/request", "/api/auth/password/reset").permitAll()
                        .requestMatchers("/api/seller/**").hasRole("SELLER")
                        .requestMatchers("/api/admin/**").access((authentication, context) -> {
                            var principal = authentication.get().getPrincipal();
                            boolean allowed = principal instanceof com.example.marketplace.identity.infrastructure.SessionMember member
                                    && access.allowed(java.util.UUID.fromString(member.getUsername()),
                                        com.example.marketplace.identity.api.AccessArea.forAdminPath(context.getRequest().getRequestURI()));
                            return new org.springframework.security.authorization.AuthorizationDecision(allowed);
                        })
                        .anyRequest().authenticated())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> AuthJson.error(request, response, 401))
                        .accessDeniedHandler((request, response, exception) -> AuthJson.error(request, response, 403)))
                .formLogin(login -> login.loginProcessingUrl("/api/auth/login").usernameParameter("email")
                        .successHandler((request, response, authentication) ->
                                AuthJson.write(response, 200, Map.of("status", "AUTHENTICATED")))
                        .failureHandler((request, response, exception) -> AuthJson.error(request, response, 401)))
                .logout(logout -> logout.logoutUrl("/api/auth/logout")
                        .logoutSuccessHandler((request, response, authentication) ->
                                AuthJson.write(response, 200, Map.of("status", "LOGGED_OUT"))))
                .addFilterAfter(new AttemptLimitFilter(), org.springframework.security.web.csrf.CsrfFilter.class)
                .addFilterBefore(new CurrentMemberFilter(members), AuthorizationFilter.class);
        return http.build();
    }
}
