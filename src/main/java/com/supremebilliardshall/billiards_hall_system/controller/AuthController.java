package com.supremebilliardshall.billiards_hall_system.controller;

import com.supremebilliardshall.billiards_hall_system.dto.APIResponse;
import com.supremebilliardshall.billiards_hall_system.dto.auth.ChangePasswordRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.auth.CurrentUserResponseDTO;
import com.supremebilliardshall.billiards_hall_system.dto.auth.LoginRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.auth.SelectBranchRequestDTO;
import com.supremebilliardshall.billiards_hall_system.exception.TooManyLoginAttemptsException;
import com.supremebilliardshall.billiards_hall_system.security.AppUserDetails;
import com.supremebilliardshall.billiards_hall_system.security.LoginAttemptService;
import com.supremebilliardshall.billiards_hall_system.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.*;

// Logout is handled by the Spring Security logout filter at /api/v1/auth/logout.
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;
    private final AuthService authService;
    private final LoginAttemptService loginAttemptService;

    public AuthController(AuthenticationManager authenticationManager,
                          SecurityContextRepository securityContextRepository,
                          AuthService authService,
                          LoginAttemptService loginAttemptService) {
        this.authenticationManager = authenticationManager;
        this.securityContextRepository = securityContextRepository;
        this.authService = authService;
        this.loginAttemptService = loginAttemptService;
    }

    @PostMapping("/login")
    public ResponseEntity<APIResponse<CurrentUserResponseDTO>> login(@Valid @RequestBody LoginRequestDTO loginRequestDTO,
                                                                     HttpServletRequest request,
                                                                     HttpServletResponse response) {
        String username = loginRequestDTO.getUsername();
        String sourceAddress = request.getRemoteAddr();
        if (loginAttemptService.isBlocked(username, sourceAddress)) {
            throw new TooManyLoginAttemptsException(
                    "Too many failed attempts. Wait a few minutes and try again.");
        }

        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(username, loginRequestDTO.getPassword()));
        } catch (AuthenticationException ex) {
            loginAttemptService.recordFailure(username, sourceAddress);
            // The username and source, never the submitted password.
            log.warn("Failed login for username '{}' from {}", username, sourceAddress);
            throw ex;
        }

        // Rotate the session id on the way in so a pre-set (fixed) session cannot be reused as
        // an authenticated one. Only when a session already exists — otherwise saveContext
        // creates a fresh one anyway, with an id the client never chose.
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }

        // Persist the authentication into the session so the cookie carries it.
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);

        loginAttemptService.recordSuccess(username, sourceAddress);

        AppUserDetails principal = (AppUserDetails) authentication.getPrincipal();
        CurrentUserResponseDTO currentUser = authService.recordLogin(principal.getUserId());

        return ResponseEntity.
                ok(APIResponse.success(
                        currentUser,
                        "Logged in successfully"));
    }

    // The write half of the branch switcher. The UI is deferred; without this a global admin
    // in a two-branch world would have no way to resolve the ambiguity BranchContext raises.
    @PutMapping("/branch")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<APIResponse<CurrentUserResponseDTO>> selectBranch(@Valid @RequestBody SelectBranchRequestDTO selectBranchRequestDTO) {
        CurrentUserResponseDTO currentUser = authService.selectBranch(selectBranchRequestDTO.getBranchId());
        return ResponseEntity.
                ok(APIResponse.success(
                        currentUser,
                        "Active branch selected successfully"));
    }

    // The caller changes their own password. Any authenticated user; the current password is
    // the check, not the role.
    @PutMapping("/password")
    public ResponseEntity<APIResponse<Void>> changePassword(@Valid @RequestBody ChangePasswordRequestDTO changePasswordRequestDTO) {
        authService.changeOwnPassword(changePasswordRequestDTO);
        return ResponseEntity.
                ok(APIResponse.success(
                        null,
                        "Password changed successfully"));
    }

    @GetMapping("/me")
    public ResponseEntity<APIResponse<CurrentUserResponseDTO>> me() {
        CurrentUserResponseDTO currentUser = authService.getCurrentUser();
        return ResponseEntity.
                ok(APIResponse.success(
                        currentUser,
                        "Current user fetched successfully"));
    }
}
