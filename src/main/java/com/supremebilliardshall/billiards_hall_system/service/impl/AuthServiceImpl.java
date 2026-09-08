package com.supremebilliardshall.billiards_hall_system.service.impl;

import com.supremebilliardshall.billiards_hall_system.dto.auth.ChangePasswordRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.auth.CurrentUserResponseDTO;
import com.supremebilliardshall.billiards_hall_system.dto.auth.ResetPasswordRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.user.CreateUserRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.user.UpdateUserRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.user.UserResponseDTO;
import com.supremebilliardshall.billiards_hall_system.entity.AppUser;
import com.supremebilliardshall.billiards_hall_system.entity.Branch;
import com.supremebilliardshall.billiards_hall_system.entity.UserRole;
import com.supremebilliardshall.billiards_hall_system.exception.BusinessRuleException;
import com.supremebilliardshall.billiards_hall_system.exception.ResourceInUseException;
import com.supremebilliardshall.billiards_hall_system.exception.ResourceNotFoundException;
import com.supremebilliardshall.billiards_hall_system.repository.AppUserRepository;
import com.supremebilliardshall.billiards_hall_system.repository.BranchRepository;
import com.supremebilliardshall.billiards_hall_system.repository.BranchSettingRepository;
import com.supremebilliardshall.billiards_hall_system.security.BranchContext;
import com.supremebilliardshall.billiards_hall_system.service.AuditService;
import com.supremebilliardshall.billiards_hall_system.service.AuthService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AuthServiceImpl implements AuthService {

    private final AppUserRepository appUserRepository;
    private final BranchRepository branchRepository;
    private final BranchSettingRepository branchSettingRepository;
    private final BranchContext branchContext;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    public AuthServiceImpl(AppUserRepository appUserRepository,
                           BranchRepository branchRepository,
                           BranchSettingRepository branchSettingRepository,
                           BranchContext branchContext,
                           PasswordEncoder passwordEncoder,
                           AuditService auditService) {
        this.appUserRepository = appUserRepository;
        this.branchRepository = branchRepository;
        this.branchSettingRepository = branchSettingRepository;
        this.branchContext = branchContext;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
    }

    @Override
    @Transactional
    public CurrentUserResponseDTO recordLogin(UUID userId) {
        AppUser user = appUserRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
        user.setLastLoginAt(OffsetDateTime.now());
        appUserRepository.save(user);
        return toResponseDto(user);
    }

    @Override
    @Transactional(readOnly = true)
    public CurrentUserResponseDTO getCurrentUser() {
        AppUser user = appUserRepository.findById(branchContext.getCurrentUserId())
                .orElseThrow(() -> new ResourceNotFoundException("User", branchContext.getCurrentUserId()));
        return toResponseDto(user);
    }

    @Override
    @Transactional
    public CurrentUserResponseDTO selectBranch(UUID branchId) {
        if (branchContext.isBranchBound()) {
            throw new ResourceInUseException("Your account is bound to one branch and cannot switch.");
        }

        Branch branch = branchRepository.findById(branchId)
                .orElseThrow(() -> new ResourceNotFoundException("Branch", branchId));
        if (!Boolean.TRUE.equals(branch.getIsActive())) {
            throw new ResourceInUseException("Branch '" + branch.getName() + "' is not active.");
        }

        branchContext.setActiveBranchId(branch.getId());
        return getCurrentUser();
    }

    @Override
    @Transactional
    public void changeOwnPassword(ChangePasswordRequestDTO request) {
        AppUser user = appUserRepository.findById(branchContext.getCurrentUserId())
                .orElseThrow(() -> new ResourceNotFoundException("User", branchContext.getCurrentUserId()));

        // The current password must match the stored hash. A wrong one is a plain refusal,
        // not a hint about which half was wrong.
        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPasswordHash())) {
            throw new BusinessRuleException("The current password is incorrect.");
        }

        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        // Clears the forced-change gate in the same transaction as the hash write, so the
        // default password is never live without the gate and never gated after it is replaced.
        user.setMustChangePassword(false);
        appUserRepository.save(user);
    }

    @Override
    @Transactional
    public void resetPassword(UUID userId, ResetPasswordRequestDTO request) {
        AppUser user = appUserRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));

        // Deliberate ruling: no admin resets another admin. An admin owns their own password
        // and changes it themselves; one admin resetting another is a takeover path. A
        // forgotten owner password is recovered by the break-glass procedure in HELP.md
        // ("Recovering a forgotten owner password"), which needs database access on purpose —
        // the bootstrap variable alone is not enough, as it only acts while the account still
        // carries the disabled sentinel. This check runs before the branch check so the owner
        // (a global admin with no branch) gets this clear message rather than a not-found.
        if (user.getRole() == UserRole.ADMIN) {
            throw new BusinessRuleException(
                    "An administrator's password cannot be reset here; they change it themselves.");
        }
        // Scoped like every other {id} load: an admin acts within the branch it is acting for,
        // and a user in another branch is reported as not found rather than revealed. A global
        // admin's current branch resolves the same way it does for every other operation.
        if (!branchContext.getCurrentBranchId().equals(user.getBranchId())) {
            throw new ResourceNotFoundException("User", userId);
        }
        // An archived user has no live login to reset.
        if (user.getArchivedAt() != null) {
            throw new BusinessRuleException(
                    "That user is archived; restore the account before resetting its password.");
        }

        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        // An admin-set password is always temporary: the owner hands it over and the user
        // replaces it on next login, so the owner never holds a working credential for someone
        // else's account. Set in the same transaction as the hash, so there is no window where
        // the admin-set password is usable without the gate. Every override that user then makes
        // is attributable to a password only they know.
        user.setMustChangePassword(true);
        appUserRepository.save(user);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserResponseDTO> listBranchUsers() {
        return appUserRepository.findBranchStaffAndGlobalAdmins(branchContext.getCurrentBranchId())
                .stream()
                .map(this::toUserResponseDto)
                .toList();
    }


    @Override
    @Transactional
    public UserResponseDTO createUser(CreateUserRequestDTO request) {
        String username = request.getUsername().trim();

        /*
         * Pre-checked so the refusal names the fix. app_user_username_key is partial on
         * archived_at IS NULL, so the raw violation would surface through the generic
         * constraint handler as "That change conflicts with an existing record" -- true, and
         * useless to someone who just needs a different name.
         */
        appUserRepository.findByUsernameIgnoreCaseAndArchivedAtIsNull(username).ifPresent(existing -> {
            throw new BusinessRuleException("The username '" + existing.getUsername()
                    + "' is already taken. Pick another, or archive that account first.");
        });

        AppUser user = new AppUser();
        user.setUsername(username);
        user.setFullName(request.getFullName().trim());
        user.setRole(request.getRole());
        /*
         * Where the person belongs, decided by app_user_branch_required_for_employee: an
         * EMPLOYEE must have a branch and an ADMIN may be global. Following the seed -- the
         * counter is branch-bound, the owner is not -- rather than hedging: one branch exists,
         * and a branch-bound admin is a deliberate later change with its own reasoning.
         */
        user.setBranchId(request.getRole() == UserRole.ADMIN
                ? null
                : branchContext.getCurrentBranchId());
        user.setIsActive(true);
        user.setPasswordHash(passwordEncoder.encode(request.getTemporaryPassword()));
        /*
         * The admin types a password and hands it over out loud, so it must not survive the
         * first sign-in. PasswordChangeGateFilter then refuses everything except reading who
         * they are, replacing the password, and logging out -- which is what makes every later
         * override attributable to a password only that person knows.
         */
        user.setMustChangePassword(true);
        AppUser saved = appUserRepository.saveAndFlush(user);

        auditService.record("USER_CREATED", "app_user", saved.getId(),
                null, snapshot(saved), "Added by " + currentUsername());
        return toUserResponseDto(saved);
    }

    @Override
    @Transactional
    public UserResponseDTO updateUser(UUID userId, UpdateUserRequestDTO request) {
        AppUser user = requireManageableUser(userId);
        UserRole previousRole = user.getRole();
        boolean wasActive = Boolean.TRUE.equals(user.getIsActive());
        boolean nowActive = Boolean.TRUE.equals(request.getIsActive());
        boolean demoting = previousRole == UserRole.ADMIN && request.getRole() != UserRole.ADMIN;
        boolean deactivating = wasActive && !nowActive;

        if (isSelf(user) && demoting) {
            throw new BusinessRuleException(
                    "You cannot remove your own administrator role. Ask another administrator to do it.");
        }
        if (isSelf(user) && deactivating) {
            throw new BusinessRuleException(
                    "You cannot deactivate your own account.");
        }
        // Losing the role or the login both take an admin out of the count, so both ask the
        // same question of the set.
        if (demoting || deactivating) {
            requireAnotherAdminRemains(user, demoting ? "demoted" : "deactivated");
        }

        Map<String, Object> before = snapshot(user);
        user.setFullName(request.getFullName().trim());
        user.setRole(request.getRole());
        user.setIsActive(nowActive);
        /*
         * A demoted admin keeps their session until it is replaced, and with it the ADMIN
         * authorities it was built with. Ending their sessions is SessionInvalidator's job and
         * the controller does it, the same way the password reset does -- see UserController.
         */
        AppUser saved = appUserRepository.saveAndFlush(user);

        auditService.record("USER_UPDATED", "app_user", saved.getId(),
                before, snapshot(saved), null);
        /*
         * A role change gets its own line rather than hiding inside USER_UPDATED. Making
         * somebody an ADMIN hands them the dashboard, the settings, every giveaway control and
         * the power to create more admins; it is the most consequential thing anyone can do in
         * this system, and the owner should not have to read a diff to notice it happened.
         */
        if (previousRole != saved.getRole()) {
            auditService.record("USER_ROLE_CHANGED", "app_user", saved.getId(),
                    Map.of("role", previousRole.name()),
                    Map.of("role", saved.getRole().name()),
                    saved.getUsername() + ": " + previousRole + " to " + saved.getRole());
        }
        return toUserResponseDto(saved);
    }

    @Override
    @Transactional
    public UserResponseDTO archiveUser(UUID userId) {
        AppUser user = requireManageableUser(userId);

        if (isSelf(user)) {
            throw new BusinessRuleException(
                    "You cannot archive your own account. Ask another administrator to do it.");
        }
        if (user.getRole() == UserRole.ADMIN) {
            requireAnotherAdminRemains(user, "archived");
        }

        Map<String, Object> before = snapshot(user);
        // Archived, never deleted: audit_log rows, bill_line.created_by and every payment this
        // person took point at them for ever. The partial unique index frees the username the
        // moment archived_at is set, which is deliberate -- a replacement can take the name.
        user.setArchivedAt(OffsetDateTime.now());
        user.setIsActive(false);
        AppUser saved = appUserRepository.saveAndFlush(user);

        auditService.record("USER_ARCHIVED", "app_user", saved.getId(),
                before, snapshot(saved), "Archived by " + currentUsername());
        return toUserResponseDto(saved);
    }

    /*
     * The invariant this whole feature exists to protect: at least one admin can always sign in.
     *
     * SERVICE-ONLY, AND IT HAS TO BE. A CHECK constraint is evaluated against one row and can
     * say nothing about how many other rows exist, so "at least one row must survive" is not
     * expressible there -- unlike every other rule in this schema, the database genuinely
     * cannot help. That is why this is enforced here and why the count is taken under a lock
     * rather than read and trusted.
     *
     * The lock is what makes it hold under a race: two admins retiring each other at the same
     * moment would both read two admins and both proceed, and the hall would be left with no
     * admin, no way to clear a lockout, and break-glass as the only way back in.
     */
    private void requireAnotherAdminRemains(AppUser losingAdmin, String verb) {
        List<AppUser> admins = appUserRepository.findActiveAdminsForUpdate(UserRole.ADMIN);
        boolean anotherRemains = admins.stream()
                .anyMatch(admin -> !admin.getId().equals(losingAdmin.getId()));
        if (!anotherRemains) {
            throw new BusinessRuleException("There would be no administrator left. "
                    + "Add another administrator before " + losingAdmin.getUsername()
                    + " is " + verb + ".");
        }
    }

    /*
     * Manageable is exactly what the Staff screen shows: this branch's people, plus the global
     * admins. Keeping the two in step means a row that appears on the page can always be acted
     * on, and anything else is reported as not found rather than revealed -- the same posture
     * every other {id} load in this codebase takes.
     */
    private AppUser requireManageableUser(UUID userId) {
        AppUser user = appUserRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
        if (user.getArchivedAt() != null) {
            throw new BusinessRuleException("That user is already archived.");
        }
        boolean inThisBranch = branchContext.getCurrentBranchId().equals(user.getBranchId());
        boolean globalAdmin = user.getBranchId() == null && user.getRole() == UserRole.ADMIN;
        if (!inThisBranch && !globalAdmin) {
            throw new ResourceNotFoundException("User", userId);
        }
        return user;
    }

    private boolean isSelf(AppUser user) {
        return user.getId().equals(branchContext.getCurrentUserId());
    }

    private String currentUsername() {
        return appUserRepository.findById(branchContext.getCurrentUserId())
                .map(AppUser::getUsername)
                .orElse("someone");
    }

    /*
     * What goes in the audit row. NEVER the password hash, and never the password: an audit log
     * is append-only and never pruned, so anything put here is readable by every future admin
     * for the life of the hall. The fields below are the ones that decide what a person can do.
     */
    private Map<String, Object> snapshot(AppUser user) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("username", user.getUsername());
        values.put("fullName", user.getFullName());
        values.put("role", user.getRole().name());
        values.put("isActive", Boolean.TRUE.equals(user.getIsActive()));
        values.put("archivedAt", user.getArchivedAt() == null ? null : user.getArchivedAt().toString());
        return values;
    }

    private UserResponseDTO toUserResponseDto(AppUser user) {
        return new UserResponseDTO(
                user.getId(),
                user.getUsername(),
                user.getFullName(),
                user.getRole(),
                Boolean.TRUE.equals(user.getIsActive()),
                Boolean.TRUE.equals(user.getMustChangePassword()));
    }

    // Hand-mapped rather than through MapStruct: the effective branch comes from the session,
    // not from the user row, because a global admin has none. Reported as null rather than
    // throwing so an admin with no branch selected can still be told who they are.
    private CurrentUserResponseDTO toResponseDto(AppUser user) {
        UUID branchId = branchContext.findCurrentBranchId().orElse(null);
        boolean checkoutAnimation = branchId != null
                && branchSettingRepository.findValueByKey(SettingsServiceImpl.CHECKOUT_ANIMATION_KEY)
                        .map(Boolean::parseBoolean)
                        .orElse(false);
        String branchName = branchId == null ? null : branchRepository.findById(branchId)
                .map(Branch::getName)
                .orElse(null);

        return new CurrentUserResponseDTO(
                user.getId(),
                user.getUsername(),
                user.getFullName(),
                user.getRole(),
                branchId,
                branchName,
                checkoutAnimation,
                Boolean.TRUE.equals(user.getMustChangePassword()));
    }
}
