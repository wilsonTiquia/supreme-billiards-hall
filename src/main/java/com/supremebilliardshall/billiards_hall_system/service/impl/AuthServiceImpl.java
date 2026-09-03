package com.supremebilliardshall.billiards_hall_system.service.impl;

import com.supremebilliardshall.billiards_hall_system.dto.auth.ChangePasswordRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.auth.CurrentUserResponseDTO;
import com.supremebilliardshall.billiards_hall_system.dto.auth.ResetPasswordRequestDTO;
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
        appUserRepository.save(user);
    }

    @Override
    @Transactional
    public void resetPassword(UUID userId, ResetPasswordRequestDTO request) {
        AppUser user = appUserRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));

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
        // Deliberate ruling: no admin resets another admin. An admin owns their own password
        // and changes it themselves; one admin resetting another is a takeover path. The owner
        // recovers a forgotten password through the bootstrap variable, not through here.
        if (user.getRole() == UserRole.ADMIN) {
            throw new BusinessRuleException(
                    "An administrator's password cannot be reset here; they change it themselves.");
        }

        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        appUserRepository.save(user);
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
                checkoutAnimation);
    }
}
