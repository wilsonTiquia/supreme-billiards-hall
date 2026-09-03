package com.supremebilliardshall.billiards_hall_system.security;

import com.supremebilliardshall.billiards_hall_system.entity.Branch;
import com.supremebilliardshall.billiards_hall_system.exception.BranchNotSelectedException;
import com.supremebilliardshall.billiards_hall_system.repository.BranchRepository;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

// The branch every query is scoped to. Referenced by name from the @Query predicates in
// BranchScopedRepository, which is why the bean name is fixed here rather than derived.
//
// An employee is pinned to their own branch and cannot change it. A global admin
// (app_user.branch_id IS NULL) carries the choice in their session: defaulted when there is
// exactly one active branch, and otherwise required explicitly. It is never inferred.
@Component("branchContext")
public class BranchContext {

    static final String ACTIVE_BRANCH_ATTRIBUTE =
            BranchContext.class.getName() + ".ACTIVE_BRANCH";

    private final BranchRepository branchRepository;

    public BranchContext(BranchRepository branchRepository) {
        this.branchRepository = branchRepository;
    }

    public UUID getCurrentBranchId() {
        return findCurrentBranchId()
                .orElseThrow(() -> new BranchNotSelectedException(
                        branchRepository.findByIsActiveTrue().size()));
    }

    // Empty rather than throwing, for the one caller that must still answer while no branch
    // is selected: /auth/me has to be able to tell the admin what to do about it.
    public Optional<UUID> findCurrentBranchId() {
        AppUserDetails user = currentUser();
        if (user.getBranchId() != null) {
            return Optional.of(user.getBranchId());
        }

        HttpSession session = currentSession();
        UUID selected = (UUID) session.getAttribute(ACTIVE_BRANCH_ATTRIBUTE);
        if (selected != null) {
            return Optional.of(selected);
        }

        // One hall, no ambiguity: default it and remember the choice for this session.
        List<Branch> active = branchRepository.findByIsActiveTrue();
        if (active.size() == 1) {
            UUID only = active.get(0).getId();
            session.setAttribute(ACTIVE_BRANCH_ATTRIBUTE, only);
            return Optional.of(only);
        }

        return Optional.empty();
    }

    public void setActiveBranchId(UUID branchId) {
        currentSession().setAttribute(ACTIVE_BRANCH_ATTRIBUTE, branchId);
    }

    public UUID getCurrentUserId() {
        return currentUser().getUserId();
    }

    // A user with their own branch cannot switch; only a global admin chooses.
    public boolean isBranchBound() {
        return currentUser().getBranchId() != null;
    }

    public boolean isAdmin() {
        return currentUser().isAdmin();
    }

    // Runs work with a system principal bound to one branch, for scheduled jobs that have no
    // request and no logged-in user. The previous context is restored afterwards.
    public <T> T runAsSystem(UUID branchId, java.util.function.Supplier<T> work) {
        org.springframework.security.core.context.SecurityContext previous =
                SecurityContextHolder.getContext();
        try {
            org.springframework.security.core.context.SecurityContext context =
                    SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                    AppUserDetails.system(branchId), "system", java.util.List.of()));
            SecurityContextHolder.setContext(context);
            return work.get();
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    private AppUserDetails currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AppUserDetails principal)) {
            throw new IllegalStateException("No authenticated user; every query must be branch-scoped.");
        }
        return principal;
    }

    private HttpSession currentSession() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            throw new IllegalStateException("No request bound; the active branch lives in the session.");
        }
        return attributes.getRequest().getSession();
    }
}
