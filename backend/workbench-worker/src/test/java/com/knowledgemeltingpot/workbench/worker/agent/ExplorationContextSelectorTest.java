package com.knowledgemeltingpot.workbench.worker.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.knowledgemeltingpot.workbench.domain.ChunkLocator;
import com.knowledgemeltingpot.workbench.domain.Material;
import com.knowledgemeltingpot.workbench.domain.MaterialChunk;
import com.knowledgemeltingpot.workbench.domain.MaterialFormat;
import com.knowledgemeltingpot.workbench.domain.MaterialStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ExplorationContextSelectorTest {
    private static final Instant NOW = Instant.parse("2026-08-06T04:00:00Z");

    @Test
    void selectsInformativeChunksAcrossTheDocumentAndRestoresSourceOrder() {
        Material material = material("制度.docx");
        List<MaterialChunk> chunks = List.of(
                chunk(0, "是"),
                chunk(1, "表头"),
                chunk(2, "本段说明逾期天数、分类标签及相应的认定条件。"),
                chunk(3, "关注类贷款应结合担保变化和还款能力进行综合判断。"),
                chunk(4, "规则"),
                chunk(5, "发生重大风险信号时应按规定执行人工复核。"));

        ExplorationContextSelector.Selection selection = ExplorationContextSelector.select(
                List.of(material), Map.of(material.id(), chunks), 2, 10_000);

        assertThat(selection.chunkCount()).isEqualTo(2);
        assertThat(selection.materials().getFirst().chunks())
                .extracting(value -> value.chunk().ordinal())
                .containsExactly(2, 3);
        assertThat(selection.materials().getFirst().chunks())
                .extracting(ExplorationContextSelector.SelectedChunk::content)
                .allMatch(value -> value.length() >= 6);
    }

    @Test
    void givesEachMaterialAChanceBeforeTakingMoreFromOneMaterial() {
        Material first = material("第一份.txt");
        Material second = material("第二份.txt");

        ExplorationContextSelector.Selection selection = ExplorationContextSelector.select(
                List.of(first, second),
                Map.of(first.id(), List.of(chunk(0, "第一份素材中的完整业务规则。"),
                                chunk(1, "第一份素材中的补充说明。")),
                        second.id(), List.of(chunk(0, "第二份素材中的完整业务规则。"))),
                2, 10_000);

        assertThat(selection.materials()).extracting(value -> value.material().id())
                .containsExactly(first.id(), second.id());
        assertThat(selection.materials()).allMatch(value -> value.chunks().size() == 1);
    }

    @Test
    void fallsBackToShortChunksWhenThatIsAllTheMaterialContains() {
        Material material = material("标签.xlsx");

        ExplorationContextSelector.Selection selection = ExplorationContextSelector.select(
                List.of(material), Map.of(material.id(), List.of(chunk(0, "正常"), chunk(1, "关注"))),
                2, 10_000);

        assertThat(selection.chunkCount()).isEqualTo(2);
        assertThat(selection.materials().getFirst().chunks()).hasSize(2);
    }

    private Material material(String name) {
        return new Material(UUID.randomUUID(), name, MaterialFormat.TXT, "text/plain", "materials/test",
                "a".repeat(64), 100, MaterialStatus.READY, NOW, NOW);
    }

    private MaterialChunk chunk(int ordinal, String content) {
        return MaterialChunk.fromParsed(UUID.randomUUID(), UUID.randomUUID(), ordinal, "SRC-" + ordinal,
                new ChunkLocator(ChunkLocator.LocatorType.TXT_LINES, null, null, null, null,
                        null, null, null, null, ordinal, ordinal),
                content, "test-v1", NOW);
    }
}
