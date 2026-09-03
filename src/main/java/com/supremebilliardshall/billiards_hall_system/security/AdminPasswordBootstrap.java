package com.supremebilliardshall.billiards_hall_system.security;

import com.supremebilliardshall.billiards_hall_system.entity.AppUser;
import com.supremebilliardshall.billiards_hall_system.repository.AppUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// Makes V2's original promise ("the application sets real bcrypt hashes on first run")
// finally true. V9 disabled the seeded logins by writing an un-loginnable sentinel; this
// is the one supported way back in without committing a real password anywhere.
//
// Runs once on boot. It acts only when SUPREME_BOOTSTRAP_ADMIN_PASSWORD is set AND the
// owner still carries the sentinel — so it initialises the first admin password exactly
// once and can never overwrite a password that has already been set. The password value
// is never logged.
@Component
public class AdminPasswordBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminPasswordBootstrap.class);

    // The value V9 writes into a disabled account. Kept in step with
    // db/migration/V9__disable_seed_credentials.sql; not a valid bcrypt hash, so no
    // password can authenticate against it.
    static final String DISABLED_SENTINEL = "DISABLED-NO-LOGIN";

    // The seeded global admin. Absent means nothing to initialise.
    private static final String BOOTSTRAP_USERNAME = "owner";

    private final AppUserRepository appUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final String bootstrapPassword;

    public AdminPasswordBootstrap(AppUserRepository appUserRepository,
                                  PasswordEncoder passwordEncoder,
                                  @Value("${SUPREME_BOOTSTRAP_ADMIN_PASSWORD:}") String bootstrapPassword) {
        this.appUserRepository = appUserRepository;
        this.passwordEncoder = passwordEncoder;
        this.bootstrapPassword = bootstrapPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        // No env var: ordinary run. Say nothing — its absence is the normal steady state,
        // and logging it would only teach operators to ignore this line.
        if (bootstrapPassword == null || bootstrapPassword.isBlank()) {
            return;
        }

        AppUser owner = appUserRepository
                .findByUsernameIgnoreCaseAndArchivedAtIsNull(BOOTSTRAP_USERNAME)
                .orElse(null);
        if (owner == null) {
            log.warn("SUPREME_BOOTSTRAP_ADMIN_PASSWORD is set but no '{}' user exists; nothing initialised.",
                    BOOTSTRAP_USERNAME);
            return;
        }

        // Only ever acts on a still-disabled account. Once a real hash is present the
        // account is considered set up, and a lingering env var must not reset it on the
        // next restart.
        if (!DISABLED_SENTINEL.equals(owner.getPasswordHash())) {
            log.info("SUPREME_BOOTSTRAP_ADMIN_PASSWORD is set but '{}' already has a password; leaving it unchanged.",
                    BOOTSTRAP_USERNAME);
            return;
        }

        owner.setPasswordHash(passwordEncoder.encode(bootstrapPassword));
        appUserRepository.save(owner);
        // The value is never logged — only the fact that it happened.
        log.info("Initialised the admin password for '{}' from SUPREME_BOOTSTRAP_ADMIN_PASSWORD. "
                + "Log in, change it immediately, then unset the variable.", BOOTSTRAP_USERNAME);
    }
}
