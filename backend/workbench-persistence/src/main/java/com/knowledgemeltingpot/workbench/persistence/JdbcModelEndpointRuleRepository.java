package com.knowledgemeltingpot.workbench.persistence;

import com.knowledgemeltingpot.workbench.application.port.ModelEndpointRuleRepository;
import com.knowledgemeltingpot.workbench.domain.ModelEndpointRule;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcModelEndpointRuleRepository implements ModelEndpointRuleRepository {
    private static final String COLUMNS = """
            SELECT id, host, allowed_ports, allow_http, allow_private_addresses,
                   created_by, updated_by, created_at, updated_at
            FROM model_endpoint_rule
            """;

    private final JdbcClient jdbc;

    public JdbcModelEndpointRuleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ModelEndpointRule save(ModelEndpointRule rule) {
        return jdbc.sql("""
                INSERT INTO model_endpoint_rule (
                    id, host, allowed_ports, allow_http, allow_private_addresses,
                    created_by, updated_by, created_at, updated_at)
                VALUES (
                    :id, :host, :allowedPorts, :allowHttp, :allowPrivateAddresses,
                    :createdBy, :updatedBy, :createdAt, :updatedAt)
                ON CONFLICT (id) DO UPDATE SET
                    host = EXCLUDED.host,
                    allowed_ports = EXCLUDED.allowed_ports,
                    allow_http = EXCLUDED.allow_http,
                    allow_private_addresses = EXCLUDED.allow_private_addresses,
                    updated_by = EXCLUDED.updated_by,
                    updated_at = EXCLUDED.updated_at
                RETURNING id, host, allowed_ports, allow_http, allow_private_addresses,
                          created_by, updated_by, created_at, updated_at
                """)
                .param("id", rule.id())
                .param("host", rule.host())
                .param("allowedPorts", formatPorts(rule.allowedPorts()))
                .param("allowHttp", rule.allowHttp())
                .param("allowPrivateAddresses", rule.allowPrivateAddresses())
                .param("createdBy", rule.createdBy())
                .param("updatedBy", rule.updatedBy())
                .param("createdAt", JdbcTimes.toJdbc(rule.createdAt()))
                .param("updatedAt", JdbcTimes.toJdbc(rule.updatedAt()))
                .query(JdbcModelEndpointRuleRepository::mapRule)
                .single();
    }

    @Override
    public Optional<ModelEndpointRule> findById(UUID id) {
        return jdbc.sql(COLUMNS + " WHERE id = :id")
                .param("id", id)
                .query(JdbcModelEndpointRuleRepository::mapRule)
                .optional();
    }

    @Override
    public Optional<ModelEndpointRule> findByNormalizedHost(String host) {
        return jdbc.sql(COLUMNS + " WHERE host = :host")
                .param("host", host)
                .query(JdbcModelEndpointRuleRepository::mapRule)
                .optional();
    }

    @Override
    public List<ModelEndpointRule> findAll() {
        return jdbc.sql(COLUMNS + " ORDER BY host")
                .query(JdbcModelEndpointRuleRepository::mapRule)
                .list();
    }

    @Override
    public boolean delete(UUID id) {
        return jdbc.sql("DELETE FROM model_endpoint_rule WHERE id = :id")
                .param("id", id)
                .update() == 1;
    }

    private static ModelEndpointRule mapRule(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ModelEndpointRule(resultSet.getObject("id", UUID.class), resultSet.getString("host"),
                parsePorts(resultSet.getString("allowed_ports")), resultSet.getBoolean("allow_http"),
                resultSet.getBoolean("allow_private_addresses"),
                resultSet.getObject("created_by", UUID.class), resultSet.getObject("updated_by", UUID.class),
                resultSet.getTimestamp("created_at").toInstant(), resultSet.getTimestamp("updated_at").toInstant());
    }

    private static String formatPorts(Set<Integer> ports) {
        return ports.stream().sorted().map(String::valueOf).collect(Collectors.joining(","));
    }

    private static Set<Integer> parsePorts(String value) {
        return Arrays.stream(value.split(","))
                .map(Integer::valueOf)
                .collect(Collectors.toUnmodifiableSet());
    }
}
