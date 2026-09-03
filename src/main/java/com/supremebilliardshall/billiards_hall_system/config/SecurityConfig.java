package com.supremebilliardshall.billiards_hall_system.config;

import tools.jackson.databind.ObjectMapper;
import com.supremebilliardshall.billiards_hall_system.dto.APIResponse;
import com.supremebilliardshall.billiards_hall_system.security.AppUserDetailsService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.session.HttpSessionEventPublisher;

import java.io.IOException;

// Server-side session cookie, not JWT. CSRF is disabled because the client is a same-origin
// SPA sending JSON; if the API is ever exposed to a browser form post, this must come back.
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private final ObjectMapper objectMapper;

    public SecurityConfig(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   SessionRegistry sessionRegistry) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                // Tracks sessions in the registry (populated on login) so a password change can
                // expire a user's other sessions. maximumSessions(-1) leaves logins unlimited;
                // it is here only to install the filter that enforces expireNow() on the next
                // request. An expired session gets the same 401 envelope as any dead session.
                .sessionManagement(session -> session
                        .maximumSessions(-1)
                        .sessionRegistry(sessionRegistry)
                        .expiredSessionStrategy(event ->
                                write(event.getResponse(), HttpStatus.UNAUTHORIZED,
                                        APIResponse.failure("Your session was ended. Please sign in again."))))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/api/v1/auth/login").permitAll()
                        // Every controller in this application lives under /api/v1, so this is
                        // the whole API surface and it keeps exactly the protection it had.
                        .requestMatchers("/api/v1/**").authenticated()
                        // Everything else is the React build: index.html, the JS bundle, the
                        // stylesheet, and the SPA's own routes. It is public because it has to
                        // be — the login screen is part of it — and it carries no data. Every
                        // figure the app displays comes from /api/v1, which is still guarded
                        // above, so serving the shell to an anonymous browser reveals nothing.
                        .anyRequest().permitAll())
                .logout(logout -> logout
                        .logoutUrl("/api/v1/auth/logout")
                        .logoutSuccessHandler((request, response, authentication) ->
                                write(response, HttpStatus.OK,
                                        APIResponse.success(null, "Logged out successfully")))
                        .invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID"))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((request, response, exception) ->
                                write(response, HttpStatus.UNAUTHORIZED,
                                        APIResponse.failure("Authentication is required")))
                        .accessDeniedHandler((request, response, exception) ->
                                write(response, HttpStatus.FORBIDDEN,
                                        APIResponse.failure("You are not allowed to perform this action"))));

        return http.build();
    }

    @Bean
    public AuthenticationManager authenticationManager(AppUserDetailsService appUserDetailsService,
                                                       PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(appUserDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    // Sessions are keyed by principal here so a user's active sessions can be found and expired
    // when their password changes. Login registers each new session with this registry.
    @Bean
    public SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    // Publishes servlet session lifecycle events so the registry drops sessions as they end,
    // rather than holding them after logout or timeout.
    @Bean
    public HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }

    private void write(HttpServletResponse response, HttpStatus status, APIResponse<Object> body)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
