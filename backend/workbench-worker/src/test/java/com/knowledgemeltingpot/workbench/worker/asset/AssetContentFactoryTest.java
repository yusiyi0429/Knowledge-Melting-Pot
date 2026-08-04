package com.knowledgemeltingpot.workbench.worker.asset;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgemeltingpot.workbench.application.port.MaterialSelection;
import com.knowledgemeltingpot.workbench.domain.AssetType;
import com.knowledgemeltingpot.workbench.domain.DocumentRevision;
import com.knowledgemeltingpot.workbench.domain.Material;
import com.knowledgemeltingpot.workbench.domain.MaterialFormat;
import com.knowledgemeltingpot.workbench.domain.MaterialPartition;
import com.knowledgemeltingpot.workbench.domain.MaterialShareScope;
import com.knowledgemeltingpot.workbench.domain.MaterialStatus;
import com.knowledgemeltingpot.workbench.domain.RoundMaterial;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AssetContentFactoryTest {
    private static final Instant NOW = Instant.parse("2026-08-03T00:00:00Z");
    private static final UUID ACTOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000203");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AssetContentFactory factory;
    private UUID subSceneId;
    private DocumentRevision revision;

    @BeforeEach
    void setUp() {
        factory = new AssetContentFactory(objectMapper);
        subSceneId = UUID.randomUUID();
        revision = new DocumentRevision(UUID.randomUUID(), subSceneId, subSceneId, 2, null,
                "# 逾期分类规则\n\n[SRC-001] 依据一\n\n[SRC-002] 依据二", "revision-hash-123", "note", true,
                ACTOR_ID, NOW, ACTOR_ID, NOW);
    }

    @Test
    void identicalInputsProduceByteStableBundlesIncludingXlsx() throws Exception {
        byte[] first = factory.build(subSceneId, AssetType.RULE_CATALOG, 1, revision, List.of());
        byte[] second = factory.build(subSceneId, AssetType.RULE_CATALOG, 1, revision, List.of());

        assertThat(sha256(first)).isEqualTo(sha256(second));
        assertThat(first).containsExactly(second);
        Map<String, byte[]> files = unzip(first);
        assertThat(files).containsKeys("rules.json", "rules.xlsx");
        String coreProperties = new String(unzip(files.get("rules.xlsx")).get("docProps/core.xml"),
                StandardCharsets.UTF_8);
        assertThat(coreProperties)
                .contains("Knowledge-Melting-Pot")
                .contains("2000-01-01T00:00:00Z");
    }

    @Test
    void everyContentDerivedBundleIsByteStable() throws Exception {
        for (AssetType type : List.of(AssetType.DECISION_FLOW, AssetType.SKILL_PACKAGE, AssetType.QA_PAIRS)) {
            byte[] first = factory.build(subSceneId, type, 3, revision, List.of());
            byte[] second = factory.build(subSceneId, type, 3, revision, List.of());
            assertThat(sha256(first)).as("stable %s", type).isEqualTo(sha256(second));
            assertThat(first).as("identical bytes %s", type).containsExactly(second);
        }
    }

    @Test
    void evaluationSetIsByteStableAndUsesOnlySafeMetadata() throws Exception {
        Material material = new Material(UUID.randomUUID(), "holdout.xlsx", MaterialFormat.XLSX,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "verified-holdout/" + UUID.randomUUID(), "c".repeat(64), 10, MaterialStatus.READY, NOW, NOW);
        RoundMaterial binding = new RoundMaterial(UUID.randomUUID(), material.id(), UUID.randomUUID(), subSceneId,
                MaterialPartition.LABELED_HOLDOUT, MaterialShareScope.ROUND, false, true, NOW);
        List<MaterialSelection> holdout = List.of(new MaterialSelection(material, binding));

        byte[] first = factory.buildEvaluationSet(subSceneId, 1, revision.revision(), revision.contentHash(), holdout);
        byte[] second = factory.buildEvaluationSet(subSceneId, 1, revision.revision(), revision.contentHash(), holdout);

        assertThat(sha256(first)).isEqualTo(sha256(second));
        String evaluation = new String(unzip(first).get("evaluation.json"), StandardCharsets.UTF_8);
        assertThat(evaluation)
                .contains("\"sourceRevision\" : 2")
                .contains("\"contentHash\" : \"revision-hash-123\"")
                .doesNotContain("依据一")
                .doesNotContain("objectKey");
    }

    @Test
    void qaReportRecordsSchemaDeduplicationAndAnchorChecks() throws Exception {
        Map<String, byte[]> files = unzip(factory.build(subSceneId, AssetType.QA_PAIRS, 1, revision, List.of()));
        String report = new String(files.get("report.json"), StandardCharsets.UTF_8);

        assertThat(report)
                .contains("\"schema\" : \"qa-pairs/v1\"")
                .contains("\"anchorCheck\"")
                .contains("\"paragraphsWithAnchors\" : 2")
                .contains("\"paragraphsWithoutAnchors\" : 0");
    }

    @Test
    void skillManifestListsPerFileHashes() throws Exception {
        Map<String, byte[]> files = unzip(factory.build(subSceneId, AssetType.SKILL_PACKAGE, 1, revision, List.of()));
        String manifest = new String(files.get("manifest.json"), StandardCharsets.UTF_8);

        assertThat(manifest)
                .contains("\"executable\" : false")
                .contains("\"SKILL.md\"")
                .contains("\"prompt.md\"")
                .contains("\"schema.json\"");
        assertThat(manifest).doesNotContain("manifest.json\": {");
        // The listed hashes must match the actual bundled bytes.
        String skillHash = HexFormat.of().formatHex(digest(files.get("SKILL.md")));
        assertThat(manifest).contains("\"sha256\" : \"" + skillHash + "\"");
    }

    private static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(digest(bytes));
    }

    private static byte[] digest(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Map<String, byte[]> unzip(byte[] bytes) throws IOException {
        Map<String, byte[]> files = new java.util.LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            var entry = zip.getNextEntry();
            while (entry != null) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                zip.transferTo(output);
                files.put(entry.getName(), output.toByteArray());
                entry = zip.getNextEntry();
            }
        }
        return files;
    }
}
