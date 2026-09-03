package com.supremebilliardshall.billiards_hall_system.security;

import com.supremebilliardshall.billiards_hall_system.dto.APIResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

// The one place the forced password change is enforced. A per-controller check would be missed
// by the next endpoint someone adds; this sits in the filter chain and covers everything.
//
// While the authenticated user carries must_change_password, every /api/v1 request is refused
// except the three that let them get out of the state: read who they are, change the password,
// and log out. The refusal carries a distinct code so the SPA can tell it from an ordinary
// permission denial. Non-/api/v1 requests (the SPA shell and its assets) are never gated, so the
// change-password screen can load.
@Component
public class PasswordChangeGateFilter extends OncePerRequestFilter {

    public static final String CODE = "PASSWORD_CHANGE_REQUIRED";

    private final ObjectMapper objectMapper;

    public PasswordChangeGateFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.getPrincipal() instanceof AppUserDetails principal
                && principal.isMustChangePassword()
                && !isAllowed(request)) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getOutputStream(),
                    APIResponse.failure("You must change your password before continuing.", CODE));
            return;
        }
        filterChain.doFilter(request, response);
    }

    // Everything the SPA shell needs, plus exactly the three API calls that resolve the state.
    private boolean isAllowed(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (!uri.startsWith("/api/v1/")) {
            return true;
        }
        String method = request.getMethod();
        return ("GET".equals(method) && uri.equals("/api/v1/auth/me"))
                || ("PUT".equals(method) && uri.equals("/api/v1/auth/password"))
                || ("POST".equals(method) && uri.equals("/api/v1/auth/logout"));
    }
}
