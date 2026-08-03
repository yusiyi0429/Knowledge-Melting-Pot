package com.knowledgemeltingpot.workbench.api.security;

import com.knowledgemeltingpot.workbench.application.port.UserRepository;
import com.knowledgemeltingpot.workbench.domain.UserAccount;
import com.knowledgemeltingpot.workbench.domain.UserRole;
import com.knowledgemeltingpot.workbench.domain.UserStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class AdminBootstrap {
    private static final Logger LOGGER = LoggerFactory.getLogger(AdminBootstrap.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final String username;
    private final String password;

    public AdminBootstrap(UserRepository userRepository, PasswordEncoder passwordEncoder, Clock clock,
            @Value("${workbench.bootstrap-admin.username:admin}") String username,
            @Value("${workbench.bootstrap-admin.password:}") String password) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.username = username;
        this.password = password;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void createInitialAdmin() {
        if (userRepository.findByUsername(username).isPresent()) {
            return;
        }
        if (password == null || password.isBlank()) {
            LOGGER.warn("No local administrator exists. Set KMP_BOOTSTRAP_ADMIN_PASSWORD to bootstrap one.");
            return;
        }
        if (password.length() < 12 || password.length() > 128) {
            throw new IllegalStateException("bootstrap administrator password length must be between 12 and 128 characters");
        }
        if (username == null || !username.trim().matches("[A-Za-z0-9][A-Za-z0-9._-]{2,99}")) {
            throw new IllegalStateException("bootstrap administrator username has an invalid format");
        }
        Instant now = Instant.now(clock);
        String normalizedUsername = username.trim();
        userRepository.save(new UserAccount(UUID.randomUUID(), normalizedUsername, normalizedUsername,
                passwordEncoder.encode(password),
                UserStatus.ACTIVE, Set.of(UserRole.ADMIN, UserRole.PUBLISHER, UserRole.OPERATOR), true, now, now));
        LOGGER.info("Bootstrapped local administrator account '{}'.", username);
    }
}
