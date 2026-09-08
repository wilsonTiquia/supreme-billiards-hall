package com.supremebilliardshall.billiards_hall_system.security;

import com.supremebilliardshall.billiards_hall_system.dto.APIResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
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
// permission denial. The SPA shell and its assets are still served, so the change-password
// screen can load.
//
// Two things about how this decides, both of which are the fix for a real bypass:
//
//   1. Paths are matched with the framework's own PathPattern machinery — the same matching
//      authorizeHttpRequests and the DispatcherServlet use, against the same decoded and
//      normalized path. The previous version compared request.getRequestURI() by hand, and
//      that string is the RAW request target: a request for /%61pi/v1/tables reaches
//      UserController exactly as /api/v1/tables, because Tomcat and Spring both route on the
//      decoded path, while a startsWith("/api/v1/") on the raw target sees no API call at all
//      and let it through. Deciding with the same resolution the router uses is what makes it
//      impossible for this filter and the router to disagree about which endpoint a request
//      is for; hand-rolled normalization is how that class of bug comes back.
//
//   2. The decision defaults to DENY. Anything under /api/v1 that is not one of the three
//      escape routes is refused, and anything outside it is served only for GET and HEAD.
//      A path shape nobody anticipated is therefore refused rather than waved through, which
//      is the opposite of how the bypass above behaved.
@Component
public class PasswordChangeGateFilter extends OncePerRequestFilter {

    public static final String CODE = "PASSWORD_CHANGE_REQUIRED";

    private static final PathPatternRequestMatcher.Builder PATHS =
            PathPatternRequestMatcher.withDefaults();

    // The whole API surface, written exactly as SecurityConfig writes it.
    private static final RequestMatcher API = PATHS.matcher("/api/v1/**");

    // The only three calls a gated user may make: read who they are, replace the password,
    // and log out.
    private static final RequestMatcher ESCAPE_ROUTES = new OrRequestMatcher(
            PATHS.matcher(HttpMethod.GET, "/api/v1/auth/me"),
            PATHS.matcher(HttpMethod.PUT, "/api/v1/auth/password"),
            PATHS.matcher(HttpMethod.POST, "/api/v1/auth/logout"));

    // The React build: index.html, the bundle, the stylesheet, and the SPA's own routes.
    // Reads only — a gated user has no business writing anywhere outside the three above,
    // and naming the methods keeps this an allowlist rather than "everything that is not
    // the API".
    private static final RequestMatcher SHELL = new OrRequestMatcher(
            PATHS.matcher(HttpMethod.GET, "/**"),
            PATHS.matcher(HttpMethod.HEAD, "/**"));

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

    // Allow only what is named. Every branch ends in an allowlist, so an unrecognised request
    // falls through to a refusal instead of to the API.
    private boolean isAllowed(HttpServletRequest request) {
        if (ESCAPE_ROUTES.matches(request)) {
            return true;
        }
        if (API.matches(request)) {
            return false;
        }
        return SHELL.matches(request);
    }
}
