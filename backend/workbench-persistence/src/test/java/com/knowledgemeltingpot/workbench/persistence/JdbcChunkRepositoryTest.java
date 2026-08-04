package com.knowledgemeltingpot.workbench.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgemeltingpot.workbench.domain.ChunkLocator;
import com.knowledgemeltingpot.workbench.domain.MaterialChunk;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class JdbcChunkRepositoryTest {
    private static final Instant NOW = Instant.parse("2026-08-03T00:00:00Z");

    @Test
    void commitAllUsesIdempotentInsertWithLocatorJsonAndContentHash() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.update()).thenReturn(1);
        JdbcClient.MappedQuerySpec<Integer> countQuery = queryMock(1);
        when(statement.query(Integer.class)).thenReturn(countQuery);
        JdbcChunkRepository repository = new JdbcChunkRepository(jdbc, new ObjectMapper());
        UUID blobId = UUID.randomUUID();
        MaterialChunk chunk = MaterialChunk.fromParsed(UUID.randomUUID(), blobId, 3, "SRC-blob-3",
                new ChunkLocator(ChunkLocator.LocatorType.XLSX_RANGE, null, null, null, "Policies", 0, 5, 0, 2,
                        null, null),
                "cell text", "1", NOW);

        int count = repository.commitAll(blobId, "1", List.of(chunk));

        assertThat(count).isEqualTo(1);
        verify(statement, org.mockito.Mockito.atLeastOnce()).param("blobId", blobId);
        verify(statement, org.mockito.Mockito.atLeastOnce()).param("parserVersion", "1");
        verify(statement).param("ordinal", 3);
        verify(statement).param("sourceRefCode", "SRC-blob-3");
        verify(statement).param("contentHash", chunk.contentHash());
        verify(statement).param("charCount", 9);
        verify(statement).param("locator",
                "{\"type\":\"XLSX_RANGE\",\"page\":null,\"paragraph\":null,\"table\":null,\"sheet\":\"Policies\","
                        + "\"rowStart\":0,\"rowEnd\":5,\"colStart\":0,\"colEnd\":2,\"lineStart\":null,\"lineEnd\":null}");
    }

    @Test
    void emptyFindForMaterialsSkipsTheDatabase() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcChunkRepository repository = new JdbcChunkRepository(jdbc, new ObjectMapper());

        Map<UUID, List<MaterialChunk>> result = repository.findForMaterials(List.of());

        assertThat(result).isEmpty();
        verify(jdbc, never()).sql(anyString());
    }

    @Test
    void existsForBlobQueriesChunkPresence() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        JdbcClient.MappedQuerySpec<Boolean> presenceQuery = queryMock(Boolean.TRUE);
        when(statement.query(Boolean.class)).thenReturn(presenceQuery);
        JdbcChunkRepository repository = new JdbcChunkRepository(jdbc, new ObjectMapper());
        UUID blobId = UUID.randomUUID();

        assertThat(repository.existsForBlob(blobId)).isTrue();

        verify(statement).param("blobId", blobId);
    }

    private static <T> JdbcClient.MappedQuerySpec<T> queryMock(T value) {
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<T> query = mock(JdbcClient.MappedQuerySpec.class);
        when(query.single()).thenReturn(value);
        return query;
    }
}
