package com.knowledgemeltingpot.workbench.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgemeltingpot.workbench.application.error.UnprocessableEntityException;
import com.knowledgemeltingpot.workbench.application.port.ChunkRepository;
import com.knowledgemeltingpot.workbench.application.port.DocumentRepository;
import com.knowledgemeltingpot.workbench.application.port.KnowledgeProjectionRepository;
import com.knowledgemeltingpot.workbench.application.port.MaterialSourceRef;
import com.knowledgemeltingpot.workbench.application.port.SceneRepository;
import com.knowledgemeltingpot.workbench.domain.DocumentRevision;
import com.knowledgemeltingpot.workbench.domain.KnowledgeIr;
import com.knowledgemeltingpot.workbench.domain.SubScene;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DocumentServiceFinalizationTest {
    private static final Instant NOW = Instant.parse("2026-08-03T00:00:00Z");

    @Test
    void validatedMarkdownCreatesProjectionAndFinalizedRevisionAtomically() {
        UUID documentId = UUID.randomUUID();
        UUID subSceneId = UUID.randomUUID();
        UUID roundId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID materialId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        KnowledgeIrValidator validator = new KnowledgeIrValidator(mapper);
        KnowledgeMarkdownCodec codec = new KnowledgeMarkdownCodec(mapper, validator);
        KnowledgeIr.SourceRef ref = source(materialId, chunkId);
        KnowledgeIr ir = validIr(validator, documentId, subSceneId, roundId, ref);
        String content = codec.render(ir);

        DocumentRepository documents = mock(DocumentRepository.class);
        SceneRepository scenes = mock(SceneRepository.class);
        KnowledgeProjectionRepository projections = mock(KnowledgeProjectionRepository.class);
        ChunkRepository chunks = mock(ChunkRepository.class);
        when(documents.findLatest(documentId)).thenReturn(Optional.empty());
        when(scenes.findSubScene(subSceneId)).thenReturn(Optional.of(
                new SubScene(subSceneId, UUID.randomUUID(), "开户", "", NOW, NOW)));
        when(chunks.findTrustedSourceRefs(roundId, subSceneId, List.of(ref.code())))
                .thenReturn(List.of(materialSource(ref)));
        DocumentRevision finalized = new DocumentRevision(UUID.randomUUID(), documentId, subSceneId, 1,
                null, content, Hashes.sha256(content), "业务复核通过", true, actorId, NOW, actorId, NOW);
        when(documents.saveNextRevision(eq(documentId), eq(subSceneId), eq(null), any(), eq(content),
                eq(Hashes.sha256(content)), eq("业务复核通过"), eq(true), eq(actorId), eq(NOW)))
                .thenReturn(finalized);
        DocumentService service = new DocumentService(documents, scenes, projections, chunks, codec,
                mock(AuditService.class), mapper, Clock.fixed(NOW, ZoneOffset.UTC));

        DocumentRevision saved = service.save(documentId, subSceneId, content, "业务复核通过", true,
                "*", actorId, "trace-finalize");

        assertThat(saved.finalized()).isTrue();
        verify(projections).insert(eq(finalized.id()), eq(ir), any(), eq(NOW));
    }

    @Test
    void malformedOrSourceFreeFinalMarkdownIsRejectedBeforePersistence() {
        UUID documentId = UUID.randomUUID();
        UUID subSceneId = UUID.randomUUID();
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        KnowledgeIrValidator validator = new KnowledgeIrValidator(mapper);
        KnowledgeMarkdownCodec codec = new KnowledgeMarkdownCodec(mapper, validator);
        DocumentRepository documents = mock(DocumentRepository.class);
        DocumentService service = new DocumentService(documents, mock(SceneRepository.class),
                mock(KnowledgeProjectionRepository.class), mock(ChunkRepository.class), codec,
                mock(AuditService.class), mapper, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.save(documentId, subSceneId, "# 普通 Markdown", "", true,
                "*", UUID.randomUUID(), "trace-finalize"))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("kmp-metadata");
    }

    private KnowledgeIr validIr(KnowledgeIrValidator validator, UUID documentId, UUID subSceneId,
            UUID roundId, KnowledgeIr.SourceRef ref) {
        KnowledgeIr ir = new KnowledgeIr(KnowledgeIr.SCHEMA_VERSION,
                new KnowledgeIr.Metadata(documentId, subSceneId, roundId, "a".repeat(64)),
                List.of(new KnowledgeIr.Rule("R-0000000000000000", "规则", "满足条件", "执行结论", 10,
                        List.of(), List.of(ref.code()))), List.of(), List.of(), List.of(), List.of(ref));
        return validator.validate(validator.assignStableRuleIds(ir));
    }

    private KnowledgeIr.SourceRef source(UUID materialId, UUID chunkId) {
        return new KnowledgeIr.SourceRef("SRC-TEST-1", materialId, "b".repeat(64), chunkId,
                "TXT_LINES", null, null, null, null, null, null, null, null, 1, 2, "c".repeat(64));
    }

    private MaterialSourceRef materialSource(KnowledgeIr.SourceRef ref) {
        return new MaterialSourceRef(ref.code(), ref.materialId(), ref.materialSha256(), ref.chunkId(),
                ref.locatorType(), ref.page(), ref.paragraph(), ref.table(), ref.sheet(), ref.rowStart(),
                ref.rowEnd(), ref.colStart(), ref.colEnd(), ref.lineStart(), ref.lineEnd(), ref.excerptHash(),
                20, NOW);
    }
}
