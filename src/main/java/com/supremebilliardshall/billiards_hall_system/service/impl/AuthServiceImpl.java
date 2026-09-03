package com.supremebilliardshall.billiards_hall_system.service.impl;

import com.supremebilliardshall.billiards_hall_system.dto.auth.ChangePasswordRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.auth.CurrentUserResponseDTO;
import com.supremebilliardshall.billiards_hall_system.dto.auth.ResetPasswordRequestDTO;
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
import com.supremebilliardshall.billiards_hall_system.service.AuthService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class AuthServiceImpl implements AuthService {

    private final AppUserRepository appUserRepository;
    private final BranchRepository branchRepository;
    private final BranchSettingRepository branchSettingRepository;
    private final BranchContext branchContext;
    private final PasswordEncoder passwordEncoder;

    public AuthServiceImpl(AppUserRepository appUserRepository,
                           BranchRepository branchRepository,
                           BranchSettingRepository branchSettingRepository,
                           BranchContext branchContext,
                           PasswordEncoder passwordEncoder) {
        this.appUserRepository = appUserRepository;
        this.branchRepository = branchRepository;
        this.branchSettingRepository = branchSettingRepository;
        this.branchContext = branchContext;
        this.passwordEncoder = passwordEncoder;
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
        return appUserRepository.findByBranchIdAndArchivedAtIsNullOrderByUsername(
                        branchContext.getCurrentBranchId())
                .stream()
                .map(user -> new UserResponseDTO(
                        user.getId(),
                        user.getUsername(),
                        user.getFullName(),
                        user.getRole(),
                        Boolean.TRUE.equals(user.getIsActive()),
                        Boolean.TRUE.equals(user.getMustChangePassword())))
                .toList();
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
