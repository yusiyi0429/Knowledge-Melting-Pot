package com.knowledgemeltingpot.workbench.application.port;

import com.knowledgemeltingpot.workbench.domain.UserAccount;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository {
    Optional<UserAccount> findByUsername(String username);

    Optional<UserAccount> findById(UUID id);

    List<UserAccount> findAll();

    UserAccount save(UserAccount account);
}
