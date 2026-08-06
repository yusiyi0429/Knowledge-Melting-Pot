package com.knowledgemeltingpot.workbench.application.service;

import com.knowledgemeltingpot.workbench.application.port.AssetRepository;
import com.knowledgemeltingpot.workbench.application.port.ExplorationRepository;
import com.knowledgemeltingpot.workbench.application.port.MaterialSelectionPort;
import com.knowledgemeltingpot.workbench.application.port.SceneRepository;
import com.knowledgemeltingpot.workbench.application.service.AgentConfigurationService.EffectiveAgentConfiguration;
import com.knowledgemeltingpot.workbench.domain.AgentRole;
import com.knowledgemeltingpot.workbench.domain.Asset;
import com.knowledgemeltingpot.workbench.domain.AssetStatus;
import com.knowledgemeltingpot.workbench.domain.AssetType;
import com.knowledgemeltingpot.workbench.domain.DocumentRevision;
import com.knowledgemeltingpot.workbench.domain.MaterialStatus;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One authoritative preflight model shared by the workbench pages. It deliberately reports blockers instead of
 * starting work so a user can repair Agent mounts and business context before a Job is created.
 */
@Service
public class OperationReadinessService {
    private static final Map<Operation, List<AgentRole>> REQUIRED_ROLES = Map.of(
            Operation.SCENE_EXPLORE, List.of(AgentRole.SCENE_EXPLORER),
            Operation.EXTRACT, List.of(AgentRole.KNOWLEDGE_EXTRACTOR),
            Operation.ALIGN, List.of(AgentRole.ALIGNMENT_REVIEWER),
            Operation.GENERATE_ASSETS, List.of(AgentRole.RULE_CATALOG_GENERATOR,
                    AgentRole.DECISION_FLOW_GENERATOR, AgentRole.SKILL_PACKAGER, AgentRole.QA_EVALUATOR),
            Operation.RELEASE, List.of(),
            Operation.EVALUATE, List.of(AgentRole.QA_EVALUATOR));

    private final AgentConfigurationService agentConfigurations;
    private final ExplorationRepository explorations;
    private final SceneRepository scenes;
    private final MaterialSelectionPort materials;
    private final DocumentService documents;
    private final AssetRepository assets;

    public OperationReadinessService(AgentConfigurationService agentConfigurations,
            ExplorationRepository explorations, SceneRepository scenes, MaterialSelectionPort materials,
            DocumentService documents, AssetRepository assets) {
        this.agentConfigurations = agentConfigurations;
        this.explorations = explorations;
        this.scenes = scenes;
        this.materials = materials;
        this.documents = documents;
        this.assets = assets;
    }

    @Transactional(readOnly = true)
    public Report check(Operation operation, UUID explorationSessionId, UUID sceneId, UUID subSceneId,
            UUID roundId) {
        if (operation == null) {
            throw new IllegalArgumentException("operation is required");
        }
        List<Blocker> blockers = new ArrayList<>();
        List<AgentRequirement> requiredAgents = resolveAgents(operation, sceneId, subSceneId, blockers);
        validateContext(operation, explorationSessionId, sceneId, subSceneId, roundId, blockers);
        return new Report(operation, blockers.isEmpty(), requiredAgents, List.copyOf(blockers));
    }

    private List<AgentRequirement> resolveAgents(Operation operation, UUID sceneId, UUID subSceneId,
            List<Blocker> blockers) {
        List<AgentRole> roles = REQUIRED_ROLES.get(operation);
        if (roles.isEmpty()) {
            return List.of();
        }
        Map<AgentRole, EffectiveAgentConfiguration> resolved = new EnumMap<>(AgentRole.class);
        if (operation == Operation.SCENE_EXPLORE) {
            EffectiveAgentConfiguration value = agentConfigurations.resolveGlobal(AgentRole.SCENE_EXPLORER);
            resolved.put(value.role(), value);
        } else if (sceneId != null && validSceneContext(sceneId, subSceneId)) {
            agentConfigurations.resolve(sceneId, subSceneId).forEach(value -> resolved.put(value.role(), value));
        }
        List<AgentRequirement> requirements = new ArrayList<>();
        for (AgentRole role : roles) {
            EffectiveAgentConfiguration value = resolved.get(role);
            boolean configured = value != null && value.configured();
            requirements.add(new AgentRequirement(role, configured, value != null && value.enabled(),
                    value == null ? null : value.modelConfigVersionId(),
                    value == null ? null : value.skillVersionId(),
                    value == null ? null : value.effectiveHash()));
            if (!configured) {
                blockers.add(new Blocker("AGENT_CONFIGURATION_INCOMPLETE",
                        role.displayName() + "尚未启用或缺少模型/Skill 版本。", "/agents"));
            }
        }
        return List.copyOf(requirements);
    }

    private boolean validSceneContext(UUID sceneId, UUID subSceneId) {
        if (scenes.findScene(sceneId).isEmpty()) {
            return false;
        }
        return subSceneId == null || scenes.findSubScene(subSceneId)
                .map(value -> value.sceneId().equals(sceneId)).orElse(false);
    }

    private void validateContext(Operation operation, UUID explorationSessionId, UUID sceneId, UUID subSceneId,
            UUID roundId, List<Blocker> blockers) {
        if (operation == Operation.SCENE_EXPLORE) {
            validateExploration(explorationSessionId, blockers);
            return;
        }
        if (!validateSceneIdentifiers(sceneId, subSceneId, roundId, blockers)) {
            return;
        }
        switch (operation) {
            case EXTRACT -> {
                if (materials.findForExtraction(roundId, subSceneId).isEmpty()) {
                    blockers.add(new Blocker("EXTRACTION_MATERIALS_REQUIRED",
                            "当前轮次没有可用于萃取的 READY 业务素材。", "#materials"));
                }
            }
            case ALIGN -> {
                if (documents.find(subSceneId).isEmpty()) {
                    blockers.add(new Blocker("DOCUMENT_REVISION_REQUIRED",
                            "请先完成知识萃取并保存 Revision。", "#extraction"));
                }
            }
            case GENERATE_ASSETS -> documents.find(subSceneId).filter(DocumentRevision::finalized)
                    .orElseGet(() -> {
                        blockers.add(new Blocker("FINALIZED_REVISION_REQUIRED",
                                "五类资产只能基于已定稿 Revision 生成。", "#document"));
                        return null;
                    });
            case RELEASE -> validateReleaseAssets(subSceneId, blockers);
            case EVALUATE -> {
                if (materials.findForEvaluation(roundId, subSceneId).isEmpty()) {
                    blockers.add(new Blocker("HOLDOUT_MATERIALS_REQUIRED",
                            "评测需要至少一份 READY 的 LABELED_HOLDOUT 素材。", "#materials"));
                }
            }
            case SCENE_EXPLORE -> { /* handled above */ }
        }
    }

    private void validateExploration(UUID sessionId, List<Blocker> blockers) {
        if (sessionId == null || explorations.find(sessionId).isEmpty()) {
            blockers.add(new Blocker("EXPLORATION_SESSION_REQUIRED", "请先创建场景探索记录。", "/explore"));
            return;
        }
        var selected = explorations.findMaterials(sessionId);
        if (selected.isEmpty()) {
            blockers.add(new Blocker("EXPLORATION_MATERIALS_REQUIRED", "请先上传至少一份探索素材。", "#materials"));
        } else if (selected.stream().anyMatch(material -> material.status() != MaterialStatus.READY)) {
            blockers.add(new Blocker("EXPLORATION_MATERIALS_NOT_READY", "探索素材仍在处理或校验失败。", "#materials"));
        }
    }

    private boolean validateSceneIdentifiers(UUID sceneId, UUID subSceneId, UUID roundId,
            List<Blocker> blockers) {
        if (sceneId == null || scenes.findScene(sceneId).isEmpty()) {
            blockers.add(new Blocker("SCENE_REQUIRED", "请选择有效场景。", "/"));
            return false;
        }
        if (subSceneId == null || scenes.findSubScene(subSceneId)
                .filter(value -> value.sceneId().equals(sceneId)).isEmpty()) {
            blockers.add(new Blocker("SUB_SCENE_REQUIRED", "请选择属于当前场景的子场景。", "#subscenes"));
            return false;
        }
        if (roundId == null || scenes.findRound(roundId)
                .filter(value -> value.subSceneId().equals(subSceneId)).isEmpty()) {
            blockers.add(new Blocker("ROUND_REQUIRED", "请先创建当前子场景的萃取轮次。", "#rounds"));
            return false;
        }
        return true;
    }

    private void validateReleaseAssets(UUID subSceneId, List<Blocker> blockers) {
        List<Asset> latest = assets.findLatestBySubScene(subSceneId);
        Map<AssetType, Asset> byType = new EnumMap<>(AssetType.class);
        latest.forEach(asset -> byType.put(asset.type(), asset));
        List<Asset> ready = new ArrayList<>();
        for (AssetType type : AssetType.values()) {
            Asset asset = byType.get(type);
            if (asset == null || asset.status() != AssetStatus.READY) {
                blockers.add(new Blocker("ASSET_NOT_READY", type + " 尚未就绪。", "#assets"));
            } else {
                ready.add(asset);
            }
        }
        if (ready.size() == AssetType.values().length
                && ready.stream().map(Asset::documentRevisionId).distinct().count() != 1) {
            blockers.add(new Blocker("ASSET_REVISION_MISMATCH", "五类资产没有绑定同一文档 Revision。", "#assets"));
        }
    }

    public enum Operation {
        SCENE_EXPLORE,
        EXTRACT,
        ALIGN,
        GENERATE_ASSETS,
        RELEASE,
        EVALUATE
    }

    public record Report(Operation operation, boolean ready, List<AgentRequirement> agents, List<Blocker> blockers) {
        public Report {
            agents = List.copyOf(agents);
            blockers = List.copyOf(blockers);
        }
    }

    public record AgentRequirement(AgentRole role, boolean configured, boolean enabled,
            UUID modelConfigVersionId, UUID skillVersionId, String effectiveConfigHash) { }

    public record Blocker(String code, String message, String actionHref) { }
}
