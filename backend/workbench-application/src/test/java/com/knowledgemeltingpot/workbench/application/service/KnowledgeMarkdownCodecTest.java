package com.knowledgemeltingpot.workbench.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgemeltingpot.workbench.application.error.UnprocessableEntityException;
import com.knowledgemeltingpot.workbench.domain.KnowledgeIr;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class KnowledgeMarkdownCodecTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    private final KnowledgeIrValidator validator = new KnowledgeIrValidator(mapper);
    private final KnowledgeMarkdownCodec codec = new KnowledgeMarkdownCodec(mapper, validator);

    @Test
    void renderAndParseAreReversibleAndStable() {
        KnowledgeIr ir = validIr();
        String markdown = codec.render(ir);

        KnowledgeIr reparsed = codec.parse(markdown);

        assertThat(reparsed).isEqualTo(ir);
        assertThat(codec.render(reparsed)).isEqualTo(markdown);
        assertThat(markdown).contains("```kmp-metadata", "```kmp-rule", "[SRC-CODEC-1]");
    }

    @Test
    void missingStructureOrUnknownSourceCannotCreateAProjection() {
        assertThatThrownBy(() -> codec.parse("# 普通 Markdown\n\n[SRC-CODEC-1]"))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("kmp-metadata");

        String tampered = codec.render(validIr()).replaceFirst("SRC-CODEC-1", "SRC-NOT-FOUND");
        assertThatThrownBy(() -> codec.parse(tampered))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("unknown source");
    }

    @Test
    void sourceLocatorStillRequiresCoordinatesForItsFormat() {
        KnowledgeIr valid = validIr();
        KnowledgeIr.SourceRef source = valid.sourceRefs().getFirst();
        KnowledgeIr.SourceRef missingLineEnd = new KnowledgeIr.SourceRef(source.code(), source.materialId(),
                source.materialSha256(), source.chunkId(), source.locatorType(), null, null, null, null,
                null, null, null, null, source.lineStart(), null, source.excerptHash());
        KnowledgeIr invalid = new KnowledgeIr(valid.schemaVersion(), valid.metadata(), valid.rules(), valid.flows(),
                valid.conflicts(), valid.gaps(), List.of(missingLineEnd));

        assertThatThrownBy(() -> validator.validate(invalid))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("missing coordinates for TXT_LINES");
    }

    private KnowledgeIr validIr() {
        KnowledgeIr.SourceRef ref = new KnowledgeIr.SourceRef("SRC-CODEC-1", UUID.randomUUID(), "b".repeat(64),
                UUID.randomUUID(), "TXT_LINES", null, null, null, null, null, null, null, null, 1, 2,
                "c".repeat(64));
        KnowledgeIr raw = new KnowledgeIr(KnowledgeIr.SCHEMA_VERSION,
                new KnowledgeIr.Metadata(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "a".repeat(64)),
                List.of(new KnowledgeIr.Rule("R-0000000000000000", "规则", "条件", "结论", 10, List.of(),
                        List.of(ref.code()))), List.of(), List.of(), List.of(), List.of(ref));
        return validator.validate(validator.assignStableRuleIds(raw));
    }
}
