package com.supremebilliardshall.billiards_hall_system.security;

import com.supremebilliardshall.billiards_hall_system.entity.AppUser;
import com.supremebilliardshall.billiards_hall_system.repository.AppUserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


@Service
public class AppUserDetailsService implements UserDetailsService {

    private final AppUserRepository appUserRepository;

    public AppUserDetailsService(AppUserRepository appUserRepository) {
        this.appUserRepository = appUserRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        AppUser user = appUserRepository.findByUsernameIgnoreCaseAndArchivedAtIsNull(username)
                .orElseThrow(() -> new UsernameNotFoundException("Unknown user: " + username));

        return new AppUserDetails(
                user.getId(),
                user.getBranchId(),
                user.getUsername(),
                user.getPasswordHash(),
                user.getFullName(),
                user.getRole(),
                Boolean.TRUE.equals(user.getIsActive()));
    }

}
