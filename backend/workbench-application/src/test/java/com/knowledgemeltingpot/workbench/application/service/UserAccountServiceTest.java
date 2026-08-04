package com.knowledgemeltingpot.workbench.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.knowledgemeltingpot.workbench.application.error.ConflictException;
import com.knowledgemeltingpot.workbench.application.port.PasswordHasher;
import com.knowledgemeltingpot.workbench.application.port.UserRepository;
import com.knowledgemeltingpot.workbench.domain.UserAccount;
import com.knowledgemeltingpot.workbench.domain.UserRole;
import com.knowledgemeltingpot.workbench.domain.UserStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserAccountServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-03T00:00:00Z");
    private static final String INITIAL_PASSWORD = "correct horse battery staple";

    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordHasher passwordHasher;
    private UserAccountService service;

    @BeforeEach
    void setUp() {
        service = new UserAccountService(userRepository, passwordHasher, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void createdUserMustChangeTheirInitialPassword() {
        when(userRepository.findByUsername("new.operator")).thenReturn(Optional.empty());
        when(passwordHasher.hash(INITIAL_PASSWORD)).thenReturn("{bcrypt}initial");
        when(userRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        UserAccount created = service.createUser(" new.operator ", "New Operator", INITIAL_PASSWORD,
                Set.of(UserRole.OPERATOR));

        assertThat(created.username()).isEqualTo("new.operator");
        assertThat(created.mustChangePassword()).isTrue();
        assertThat(created.status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(created.passwordHash()).isEqualTo("{bcrypt}initial");
    }

    @Test
    void changingPasswordVerifiesCurrentPasswordAndClearsFlag() {
        UserAccount account = account(Set.of(UserRole.OPERATOR), true);
        when(userRepository.findById(account.id())).thenReturn(Optional.of(account));
        when(passwordHasher.matches("old password value", account.passwordHash())).thenReturn(true);
        when(passwordHasher.matches("new password value", account.passwordHash())).thenReturn(false);
        when(passwordHasher.hash("new password value")).thenReturn("{bcrypt}new");
        when(userRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        UserAccount changed = service.changePassword(account.id(), "old password value", "new password value");

        assertThat(changed.passwordHash()).isEqualTo("{bcrypt}new");
        assertThat(changed.mustChangePassword()).isFalse();
    }

    @Test
    void wrongCurrentPasswordDoesNotWriteAReplacement() {
        UserAccount account = account(Set.of(UserRole.OPERATOR), true);
        when(userRepository.findById(account.id())).thenReturn(Optional.of(account));
        when(passwordHasher.matches("wrong password", account.passwordHash())).thenReturn(false);

        assertThatThrownBy(() -> service.changePassword(account.id(), "wrong password", "new password value"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("current password is invalid");
    }

    @Test
    void administratorCannotDisableTheirOwnAccount() {
        UserAccount account = account(Set.of(UserRole.ADMIN), false);
        when(userRepository.findById(account.id())).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> service.updateUser(account.id(), account.id(), null, false, null))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void resetPasswordForcesFirstLoginChangeWithoutChangingRolesOrStatus() {
        UserAccount account = account(Set.of(UserRole.OPERATOR), false);
        UUID administratorId = UUID.randomUUID();
        when(userRepository.findById(account.id())).thenReturn(Optional.of(account));
        when(passwordHasher.hash("replacement password value")).thenReturn("{bcrypt}reset");
        when(userRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        UserAccount reset = service.resetPassword(administratorId, account.id(), "replacement password value");

        assertThat(reset.passwordHash()).isEqualTo("{bcrypt}reset");
        assertThat(reset.mustChangePassword()).isTrue();
        assertThat(reset.username()).isEqualTo(account.username());
        assertThat(reset.roles()).containsExactly(UserRole.OPERATOR);
        assertThat(reset.status()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void administratorCannotResetTheirOwnPassword() {
        UserAccount account = account(Set.of(UserRole.ADMIN), false);
        when(userRepository.findById(account.id())).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> service.resetPassword(account.id(), account.id(), "replacement password value"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("cannot reset their own password");
    }

    @Test
    void resetPasswordRejectsAWeakPasswordWithoutWriting() {
        UserAccount account = account(Set.of(UserRole.OPERATOR), false);
        UUID administratorId = UUID.randomUUID();
        when(userRepository.findById(account.id())).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> service.resetPassword(administratorId, account.id(), "too-short"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 12 and 128");
    }

    @Test
    void updatePersistsRolesAndActivationStateWithoutChangingPassword() {
        UserAccount account = account(Set.of(UserRole.OPERATOR), true);
        UUID administratorId = UUID.randomUUID();
        when(userRepository.findById(account.id())).thenReturn(Optional.of(account));
        when(userRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.updateUser(administratorId, account.id(), "Publisher", false, Set.of(UserRole.PUBLISHER));

        ArgumentCaptor<UserAccount> saved = ArgumentCaptor.forClass(UserAccount.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().status()).isEqualTo(UserStatus.DISABLED);
        assertThat(saved.getValue().roles()).containsExactly(UserRole.PUBLISHER);
        assertThat(saved.getValue().passwordHash()).isEqualTo(account.passwordHash());
    }

    private UserAccount account(Set<UserRole> roles, boolean mustChangePassword) {
        return new UserAccount(UUID.randomUUID(), "operator", "Operator", "{bcrypt}old", UserStatus.ACTIVE,
                roles, mustChangePassword, NOW.minusSeconds(60), NOW.minusSeconds(60));
    }
}
