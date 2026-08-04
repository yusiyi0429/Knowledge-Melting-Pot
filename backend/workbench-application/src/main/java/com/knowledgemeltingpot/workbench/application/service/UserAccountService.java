package com.knowledgemeltingpot.workbench.application.service;

import com.knowledgemeltingpot.workbench.application.error.ConflictException;
import com.knowledgemeltingpot.workbench.application.error.NotFoundException;
import com.knowledgemeltingpot.workbench.application.port.PasswordHasher;
import com.knowledgemeltingpot.workbench.application.port.UserRepository;
import com.knowledgemeltingpot.workbench.domain.UserAccount;
import com.knowledgemeltingpot.workbench.domain.UserRole;
import com.knowledgemeltingpot.workbench.domain.UserStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

public class UserAccountService {
    private static final int MINIMUM_PASSWORD_LENGTH = 12;
    private static final int MAXIMUM_PASSWORD_LENGTH = 128;
    private static final String USERNAME_PATTERN = "[A-Za-z0-9][A-Za-z0-9._-]{2,99}";

    private final UserRepository userRepository;
    private final PasswordHasher passwordHasher;
    private final Clock clock;

    public UserAccountService(UserRepository userRepository, PasswordHasher passwordHasher, Clock clock) {
        this.userRepository = userRepository;
        this.passwordHasher = passwordHasher;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<UserAccount> listUsers() {
        return userRepository.findAll();
    }

    @Transactional
    public UserAccount createUser(String username, String displayName, String initialPassword, Set<UserRole> roles) {
        String normalizedUsername = requireText(username, "username");
        String normalizedDisplayName = requireText(displayName, "displayName");
        if (!normalizedUsername.matches(USERNAME_PATTERN)) {
            throw new IllegalArgumentException(
                    "username must be 3 to 100 ASCII letters, digits, dots, underscores, or hyphens");
        }
        validateDisplayName(normalizedDisplayName);
        validatePassword(initialPassword);
        if (userRepository.findByUsername(normalizedUsername).isPresent()) {
            throw new ConflictException("username is already in use");
        }
        Instant now = Instant.now(clock);
        return userRepository.save(new UserAccount(UUID.randomUUID(), normalizedUsername, normalizedDisplayName,
                passwordHasher.hash(initialPassword), UserStatus.ACTIVE, requireRoles(roles), true, now, now));
    }

    @Transactional
    public UserAccount updateUser(UUID actorId, UUID userId, String displayName, Boolean enabled,
            Set<UserRole> roles) {
        UserAccount current = requireUser(userId);
        String nextDisplayName = displayName == null ? current.displayName() : requireText(displayName, "displayName");
        validateDisplayName(nextDisplayName);
        UserStatus nextStatus = enabled == null ? current.status() : enabled ? UserStatus.ACTIVE : UserStatus.DISABLED;
        Set<UserRole> nextRoles = roles == null ? current.roles() : requireRoles(roles);
        if (actorId.equals(userId) && (nextStatus != UserStatus.ACTIVE || !nextRoles.contains(UserRole.ADMIN))) {
            throw new ConflictException("an administrator cannot disable their own account or remove their ADMIN role");
        }
        return userRepository.save(current.withAdministration(nextDisplayName, nextStatus, nextRoles,
                Instant.now(clock)));
    }

    @Transactional
    public UserAccount changePassword(UUID userId, String currentPassword, String newPassword) {
        UserAccount current = requireUser(userId);
        validatePassword(newPassword);
        if (!passwordHasher.matches(currentPassword, current.passwordHash())) {
            throw new IllegalArgumentException("current password is invalid");
        }
        if (passwordHasher.matches(newPassword, current.passwordHash())) {
            throw new IllegalArgumentException("new password must differ from the current password");
        }
        return userRepository.save(current.withPasswordHash(passwordHasher.hash(newPassword), false,
                Instant.now(clock)));
    }

    @Transactional
    public UserAccount resetPassword(UUID actorId, UUID userId, String newPassword) {
        UserAccount current = requireUser(userId);
        if (actorId.equals(userId)) {
            throw new ConflictException(
                    "an administrator cannot reset their own password; use /api/v1/auth/password");
        }
        validatePassword(newPassword);
        return userRepository.save(current.withPasswordHash(passwordHasher.hash(newPassword), true,
                Instant.now(clock)));
    }

    private UserAccount requireUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("user does not exist"));
    }

    private static Set<UserRole> requireRoles(Set<UserRole> roles) {
        if (roles == null || roles.isEmpty()) {
            throw new IllegalArgumentException("at least one role is required");
        }
        return Set.copyOf(roles);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static void validatePassword(String password) {
        if (password == null || password.length() < MINIMUM_PASSWORD_LENGTH
                || password.length() > MAXIMUM_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("password length must be between 12 and 128 characters");
        }
    }

    private static void validateDisplayName(String displayName) {
        if (displayName.length() > 200) {
            throw new IllegalArgumentException("displayName must not exceed 200 characters");
        }
    }
}
