package com.knowledgemeltingpot.workbench.api.security;

import com.knowledgemeltingpot.workbench.application.port.UserRepository;
import com.knowledgemeltingpot.workbench.domain.UserAccount;
import com.knowledgemeltingpot.workbench.domain.UserStatus;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class DatabaseUserDetailsService implements UserDetailsService {
    private final UserRepository userRepository;

    public DatabaseUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        UserAccount account = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("invalid credentials"));
        String[] authorities = account.roles().stream()
                .map(role -> "ROLE_" + role.name())
                .toArray(String[]::new);
        return User.withUsername(account.username())
                .password(account.passwordHash())
                .authorities(authorities)
                .disabled(account.status() == UserStatus.DISABLED)
                .accountLocked(account.status() == UserStatus.LOCKED)
                .build();
    }
}
