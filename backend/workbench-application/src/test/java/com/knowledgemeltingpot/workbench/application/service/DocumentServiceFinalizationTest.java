package com.knowledgemeltingpot.workbench.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.knowledgemeltingpot.workbench.application.port.DocumentRepository;
import com.knowledgemeltingpot.workbench.application.port.SceneRepository;
import com.knowledgemeltingpot.workbench.application.error.UnprocessableEntityException;
import com.knowledgemeltingpot.workbench.domain.DocumentRevision;
import com.knowledgemeltingpot.workbench.domain.SubScene;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DocumentServiceFinalizationTest {

    @Test
    void finalizeCreatesANewImmutableRevisionWithNoteAndFinalizationActor() {
        Instant now = Instant.parse("2026-08-03T00:00:00Z");
        UUID documentId = UUID.randomUUID();
        UUID subSceneId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID currentRevisionId = UUID.randomUUID();
        UUID savedRevisionId = UUID.randomUUID();
        DocumentRevision current = new DocumentRevision(currentRevisionId, documentId, subSceneId, 4, null,
                "draft", Hashes.sha256("draft"), "草稿", false, null, null, actorId, now.minusSeconds(60));
        String approvedContent = "# 已批准知识\n\n结论 [SRC-001]";
        DocumentRevision finalized = new DocumentRevision(savedRevisionId, documentId, subSceneId, 5,
                currentRevisionId, approvedContent, Hashes.sha256(approvedContent), "业务复核通过", true, actorId, now,
                actorId, now);
        DocumentRepository documents = mock(DocumentRepository.class);
        SceneRepository scenes = mock(SceneRepository.class);
        when(documents.findLatest(documentId)).thenReturn(Optional.of(current));
        when(scenes.findSubScene(subSceneId)).thenReturn(Optional.of(
                new SubScene(subSceneId, UUID.randomUUID(), "开户", "", now, now)));
        when(documents.saveNextRevision(eq(documentId), eq(subSceneId), eq(4L), any(), eq(approvedContent),
                eq(Hashes.sha256(approvedContent)), eq("业务复核通过"), eq(true), eq(actorId), eq(now)))
                .thenReturn(finalized);
        DocumentService service = new DocumentService(documents, scenes, mock(AuditService.class),
                Clock.fixed(now, ZoneOffset.UTC));

        DocumentRevision saved = service.save(documentId, null, approvedContent, "业务复核通过", true,
                current.etag(), actorId, "trace-finalize");

        assertThat(saved.finalized()).isTrue();
        assertThat(saved.finalizedBy()).isEqualTo(actorId);
        assertThat(saved.finalizedAt()).isEqualTo(now);
        assertThat(saved.revisionNote()).isEqualTo("业务复核通过");
        assertThat(current.finalized()).isFalse();
        verify(documents).saveNextRevision(eq(documentId), eq(subSceneId), eq(4L), any(), eq(approvedContent),
                eq(Hashes.sha256(approvedContent)), eq("业务复核通过"), eq(true), eq(actorId), eq(now));
    }

    @Test
    void finalizedRevisionRequiresStructureAndASourceAnchor() {
        Instant now = Instant.parse("2026-08-03T00:00:00Z");
        UUID documentId = UUID.randomUUID();
        UUID subSceneId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        DocumentRevision current = new DocumentRevision(UUID.randomUUID(), documentId, subSceneId, 1, null,
                "draft", Hashes.sha256("draft"), "", false, null, null, actorId, now);
        DocumentRepository documents = mock(DocumentRepository.class);
        SceneRepository scenes = mock(SceneRepository.class);
        when(documents.findLatest(documentId)).thenReturn(Optional.of(current));
        DocumentService service = new DocumentService(documents, scenes, mock(AuditService.class),
                Clock.fixed(now, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.save(documentId, null, "# 已批准知识\n\n没有来源", "", true,
                current.etag(), actorId, "trace-finalize"))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("[SRC-*]");
    }
}
