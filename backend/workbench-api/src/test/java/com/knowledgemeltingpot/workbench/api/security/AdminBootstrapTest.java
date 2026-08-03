package com.knowledgemeltingpot.workbench.api.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.knowledgemeltingpot.workbench.application.port.UserRepository;
import com.knowledgemeltingpot.workbench.domain.UserAccount;
import com.knowledgemeltingpot.workbench.domain.UserRole;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AdminBootstrapTest {
    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordEncoder passwordEncoder;

    @Test
    void bootstrappedAdministratorMustReplaceDeploymentSecret() {
        when(userRepository.findByUsername("admin")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("deployment-only-password")).thenReturn("{bcrypt}hash");
        when(userRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        AdminBootstrap bootstrap = new AdminBootstrap(userRepository, passwordEncoder,
                Clock.fixed(Instant.parse("2026-08-03T00:00:00Z"), ZoneOffset.UTC),
                "admin", "deployment-only-password");

        bootstrap.createInitialAdmin();

        ArgumentCaptor<UserAccount> account = ArgumentCaptor.forClass(UserAccount.class);
        verify(userRepository).save(account.capture());
        assertThat(account.getValue().mustChangePassword()).isTrue();
        assertThat(account.getValue().roles())
                .containsExactlyInAnyOrder(UserRole.ADMIN, UserRole.PUBLISHER, UserRole.OPERATOR);
        assertThat(account.getValue().passwordHash()).isEqualTo("{bcrypt}hash");
    }

    @Test
    void explicitlyConfiguredWeakBootstrapPasswordFailsClosed() {
        when(userRepository.findByUsername("admin")).thenReturn(Optional.empty());
        AdminBootstrap bootstrap = new AdminBootstrap(userRepository, passwordEncoder,
                Clock.fixed(Instant.parse("2026-08-03T00:00:00Z"), ZoneOffset.UTC), "admin", "too-short");

        assertThatThrownBy(bootstrap::createInitialAdmin)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("between 12 and 128");
        verify(passwordEncoder, never()).encode(any());
    }
}
