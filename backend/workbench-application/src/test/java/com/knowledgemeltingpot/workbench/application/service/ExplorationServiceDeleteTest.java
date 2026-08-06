package com.knowledgemeltingpot.workbench.application.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.knowledgemeltingpot.workbench.application.error.ConflictException;
import com.knowledgemeltingpot.workbench.application.port.AssetRepository;
import com.knowledgemeltingpot.workbench.application.port.ExplorationRepository;
import com.knowledgemeltingpot.workbench.application.port.MaterialRepository;
import com.knowledgemeltingpot.workbench.application.port.SceneRepository;
import com.knowledgemeltingpot.workbench.domain.ExplorationSession;
import com.knowledgemeltingpot.workbench.domain.ExplorationStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ExplorationServiceDeleteTest {
    private static final Instant NOW = Instant.parse("2026-08-06T01:00:00Z");

    @Test
    void archivesAFailedExplorationWithoutDestroyingItsLineage() {
        UUID sessionId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        ExplorationRepository explorations = mock(ExplorationRepository.class);
        AuditService audit = mock(AuditService.class);
        when(explorations.lock(sessionId)).thenReturn(Optional.of(session(sessionId, actorId,
                ExplorationStatus.FAILED)));
        when(explorations.archive(sessionId, 3, actorId, NOW)).thenReturn(true);

        service(explorations, audit).delete(sessionId, actorId, "trace-delete");

        verify(explorations).archive(sessionId, 3, actorId, NOW);
        verify(audit).record(eq(actorId), eq("EXPLORATION_ARCHIVED"), eq("EXPLORATION"), eq(sessionId),
                any(), eq("trace-delete"));
    }

    @Test
    void rejectsDeletionWhileTheWorkerIsAnalyzing() {
        UUID sessionId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        ExplorationRepository explorations = mock(ExplorationRepository.class);
        when(explorations.lock(sessionId)).thenReturn(Optional.of(session(sessionId, actorId,
                ExplorationStatus.ANALYZING)));

        assertThatThrownBy(() -> service(explorations, mock(AuditService.class))
                .delete(sessionId, actorId, "trace-delete"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("while analysis is running");

        verify(explorations, never()).archive(any(), eq(3), any(), any());
    }

    private ExplorationService service(ExplorationRepository explorations, AuditService audit) {
        return new ExplorationService(explorations, mock(AgentConfigurationService.class), mock(JobService.class),
                mock(SceneRepository.class), mock(MaterialRepository.class), mock(AssetRepository.class), audit,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private ExplorationSession session(UUID id, UUID actorId, ExplorationStatus status) {
        return new ExplorationSession(id, "对公贷款分类探索", status, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), "a".repeat(64), 3, actorId, NOW.minusSeconds(60), NOW);
    }
}
