package com.knowledgemeltingpot.workbench.persistence;

import com.knowledgemeltingpot.workbench.application.port.UserRepository;
import com.knowledgemeltingpot.workbench.domain.UserAccount;
import com.knowledgemeltingpot.workbench.domain.UserRole;
import com.knowledgemeltingpot.workbench.domain.UserStatus;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcUserRepository implements UserRepository {
    private final JdbcClient jdbc;

    public JdbcUserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<UserAccount> findByUsername(String username) {
        return find("LOWER(username) = LOWER(:value)", username);
    }

    @Override
    public Optional<UserAccount> findById(UUID id) {
        return find("id = CAST(:value AS uuid)", id.toString());
    }

    @Override
    public List<UserAccount> findAll() {
        return jdbc.sql("""
                SELECT id, username, display_name, password_hash, status, must_change_password,
                       created_at, updated_at
                FROM app_user
                ORDER BY LOWER(username), id
                """)
                .query(this::mapRow)
                .list().stream()
                .map(this::toAccount)
                .toList();
    }

    @Override
    public UserAccount save(UserAccount account) {
        jdbc.sql("""
                INSERT INTO app_user (
                    id, username, display_name, password_hash, status, must_change_password, created_at, updated_at
                )
                VALUES (
                    :id, :username, :displayName, :passwordHash, :status, :mustChangePassword, :createdAt, :updatedAt
                )
                ON CONFLICT (id) DO UPDATE SET
                    username = EXCLUDED.username,
                    display_name = EXCLUDED.display_name,
                    password_hash = EXCLUDED.password_hash,
                    status = EXCLUDED.status,
                    must_change_password = EXCLUDED.must_change_password,
                    updated_at = EXCLUDED.updated_at
                """)
                .param("id", account.id())
                .param("username", account.username())
                .param("displayName", account.displayName())
                .param("passwordHash", account.passwordHash())
                .param("status", account.status().name())
                .param("mustChangePassword", account.mustChangePassword())
                .param("createdAt", JdbcTimes.toJdbc(account.createdAt()))
                .param("updatedAt", JdbcTimes.toJdbc(account.updatedAt()))
                .update();
        jdbc.sql("DELETE FROM app_user_role WHERE user_id = :userId")
                .param("userId", account.id())
                .update();
        for (UserRole role : account.roles()) {
            jdbc.sql("INSERT INTO app_user_role (user_id, role) VALUES (:userId, :role)")
                    .param("userId", account.id())
                    .param("role", role.name())
                    .update();
        }
        return account;
    }

    private Optional<UserAccount> find(String predicate, String value) {
        Optional<UserRow> row = jdbc.sql("""
                SELECT id, username, display_name, password_hash, status, must_change_password,
                       created_at, updated_at
                FROM app_user WHERE %s
                """.formatted(predicate))
                .param("value", value)
                .query(this::mapRow)
                .optional();
        return row.map(this::toAccount);
    }

    private UserRow mapRow(java.sql.ResultSet resultSet, int rowNumber) throws java.sql.SQLException {
        return new UserRow(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("username"),
                resultSet.getString("display_name"),
                resultSet.getString("password_hash"),
                UserStatus.valueOf(resultSet.getString("status")),
                resultSet.getBoolean("must_change_password"),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant());
    }

    private UserAccount toAccount(UserRow user) {
        return new UserAccount(user.id(), user.username(), user.displayName(), user.passwordHash(), user.status(),
                findRoles(user.id()), user.mustChangePassword(), user.createdAt(), user.updatedAt());
    }

    private Set<UserRole> findRoles(UUID userId) {
        return new HashSet<>(jdbc.sql("SELECT role FROM app_user_role WHERE user_id = :userId ORDER BY role")
                .param("userId", userId)
                .query(String.class)
                .list().stream().map(UserRole::valueOf).toList());
    }

    private record UserRow(UUID id, String username, String displayName, String passwordHash, UserStatus status,
                           boolean mustChangePassword, Instant createdAt, Instant updatedAt) {
    }
}
