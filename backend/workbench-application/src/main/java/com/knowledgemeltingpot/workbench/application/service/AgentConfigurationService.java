package com.knowledgemeltingpot.workbench.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.knowledgemeltingpot.workbench.application.error.ConflictException;
import com.knowledgemeltingpot.workbench.application.error.NotFoundException;
import com.knowledgemeltingpot.workbench.application.error.PreconditionFailedException;
import com.knowledgemeltingpot.workbench.application.error.PreconditionRequiredException;
import com.knowledgemeltingpot.workbench.application.port.AgentMountRepository;
import com.knowledgemeltingpot.workbench.application.port.ModelConnectionRepository;
import com.knowledgemeltingpot.workbench.application.port.SceneRepository;
import com.knowledgemeltingpot.workbench.application.port.SkillRepository;
import com.knowledgemeltingpot.workbench.domain.AgentMountScope;
import com.knowledgemeltingpot.workbench.domain.AgentMountVersion;
import com.knowledgemeltingpot.workbench.domain.AgentRole;
import com.knowledgemeltingpot.workbench.domain.AgentRoleTemplateVersion;
import com.knowledgemeltingpot.workbench.domain.ConfigurationImportPreview;
import com.knowledgemeltingpot.workbench.domain.ModelConfigVersion;
import com.knowledgemeltingpot.workbench.domain.ModelConnection;
import com.knowledgemeltingpot.workbench.domain.Skill;
import com.knowledgemeltingpot.workbench.domain.SkillKind;
import com.knowledgemeltingpot.workbench.domain.SkillVersion;
import com.knowledgemeltingpot.workbench.domain.SubScene;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentConfigurationService {
    public static final String IMPORT_SCHEMA_VERSION = "1.0";
    private static final int MAX_OPTIONS_BYTES = 8_192;
    private static final Set<String> FORBIDDEN_OPTION_KEYS = Set.of(
            "credential", "credentials", "apikey", "api_key", "secret", "password", "token",
            "script", "shell", "python");

    private final AgentMountRepository mountRepository;
    private final SceneRepository sceneRepository;
    private final ModelConnectionRepository modelRepository;
    private final SkillRepository skillRepository;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AgentConfigurationService(AgentMountRepository mountRepository, SceneRepository sceneRepository,
            ModelConnectionRepository modelRepository, SkillRepository skillRepository,
            AuditService auditService, ObjectMapper objectMapper, Clock clock) {
        this.mountRepository = mountRepository;
        this.sceneRepository = sceneRepository;
        this.modelRepository = modelRepository;
        this.skillRepository = skillRepository;
        this.auditService = auditService;
        this.objectMapper = objectMapper.copy()
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        this.clock = clock;
    }

    public List<RoleDefinition> roles() {
        return Arrays.stream(AgentRole.values())
                .map(role -> new RoleDefinition(role, role.displayName(), role.stage(), role.description()))
                .toList();
    }

    @Transactional(readOnly = true)
    public ScopeConfiguration getScope(AgentMountScope scope, UUID scopeId) {
        ScopeTarget target = requireTarget(scope, scopeId);
        List<AgentMountVersion> mounts = sorted(mountRepository.findLatest(scope, scopeId));
        return new ScopeConfiguration(scope, scopeId, target.sceneId(), scopeEtag(mounts), mounts);
    }

    @Transactional(readOnly = true)
    public List<EffectiveAgentConfiguration> resolve(UUID sceneId, UUID subSceneId) {
        requireResolutionTarget(sceneId, subSceneId);
        Map<AgentRole, AgentRoleTemplateVersion> templates = latestTemplates();
        Map<AgentRole, AgentMountVersion> global = index(mountRepository.findLatest(AgentMountScope.GLOBAL, null));
        Map<AgentRole, AgentMountVersion> scene = index(mountRepository.findLatest(AgentMountScope.SCENE, sceneId));
        Map<AgentRole, AgentMountVersion> subScene = subSceneId == null ? Map.of()
                : index(mountRepository.findLatest(AgentMountScope.SUB_SCENE, subSceneId));
        List<EffectiveAgentConfiguration> resolved = new ArrayList<>();
        for (AgentRole role : AgentRole.values()) {
            resolved.add(resolveRole(role, templates.get(role), global.get(role), scene.get(role), subScene.get(role)));
        }
        return List.copyOf(resolved);
    }

    @Transactional(readOnly = true)
    public EffectiveAgentConfiguration resolveGlobal(AgentRole role) {
        if (role == null) throw new IllegalArgumentException("Agent role is required");
        Map<AgentRole, AgentRoleTemplateVersion> templates = latestTemplates();
        Map<AgentRole, AgentMountVersion> global = index(mountRepository.findLatest(AgentMountScope.GLOBAL, null));
        return resolveRole(role, templates.get(role), global.get(role), null, null);
    }

    @Transactional
    public ScopeConfiguration append(AgentMountScope scope, UUID scopeId, AgentMountDraft draft,
            String ifMatch, UUID actorId, String traceId) {
        ScopeTarget target = requireTarget(scope, scopeId);
        requireIfMatch(ifMatch);
        mountRepository.lockScope(scope, scopeId);
        List<AgentMountVersion> before = sorted(mountRepository.findLatest(scope, scopeId));
        assertScopeEtag(before, ifMatch);
        AgentMountVersion saved = appendLocked(scope, scopeId, target.sceneId(), draft, actorId);
        auditService.record(actorId, "AGENT_MOUNT_VERSION_CREATED", "AGENT_MOUNT_VERSION", saved.id(),
                auditDetails(scope, scopeId, saved), traceId);
        List<AgentMountVersion> after = sorted(mountRepository.findLatest(scope, scopeId));
        return new ScopeConfiguration(scope, scopeId, target.sceneId(), scopeEtag(after), after);
    }

    @Transactional
    public ConfigurationImportPreview previewImport(AgentMountScope scope, UUID scopeId,
            List<AgentMountDraft> drafts, UUID actorId, String traceId) {
        ScopeTarget target = requireTarget(scope, scopeId);
        List<AgentMountDraft> normalized = normalizeDrafts(drafts, target.sceneId(), scope);
        List<AgentMountVersion> current = sorted(mountRepository.findLatest(scope, scopeId));
        String baseEtag = scopeEtag(current);
        ImportManifest manifest = new ImportManifest(IMPORT_SCHEMA_VERSION, scope, scopeId, normalized);
        String manifestJson = toJson(manifest);
        String manifestHash = Hashes.sha256(manifestJson);
        String diffJson = toJson(buildDiff(current, normalized));
        Instant now = Instant.now(clock);
        ConfigurationImportPreview preview = new ConfigurationImportPreview(UUID.randomUUID(),
                IMPORT_SCHEMA_VERSION, scope, scopeId, target.sceneId(), baseEtag, manifestJson, manifestHash,
                diffJson, actorId, now, null, null);
        mountRepository.insertImport(preview);
        auditService.record(actorId, "CONFIGURATION_IMPORT_PREVIEWED", "CONFIGURATION_IMPORT", preview.id(),
                Map.of("scope", scope, "manifestHash", manifestHash, "roleCount", normalized.size()), traceId);
        return preview;
    }

    @Transactional(readOnly = true)
    public ConfigurationImportPreview getImport(UUID importId) {
        return mountRepository.findImport(importId)
                .orElseThrow(() -> new NotFoundException("configuration import not found: " + importId));
    }

    @Transactional
    public ScopeConfiguration applyImport(UUID importId, String ifMatch, UUID actorId, String traceId) {
        requireIfMatch(ifMatch);
        ConfigurationImportPreview preview = getImport(importId);
        if (preview.applied()) {
            throw new ConflictException("configuration import was already applied");
        }
        if (!preview.manifestHash().equals(normalizeEtag(ifMatch))) {
            throw new PreconditionFailedException("If-Match does not match the import manifest");
        }
        ImportManifest manifest = fromJson(preview.manifestJson(), ImportManifest.class);
        if (!IMPORT_SCHEMA_VERSION.equals(manifest.schemaVersion()) || manifest.scope() != preview.scope()
                || !Objects.equals(manifest.scopeId(), preview.scopeId())) {
            throw new IllegalStateException("persisted configuration import metadata is inconsistent");
        }
        ScopeTarget target = requireTarget(preview.scope(), preview.scopeId());
        List<AgentMountDraft> drafts = normalizeDrafts(manifest.roles(), target.sceneId(), preview.scope());
        mountRepository.lockScope(preview.scope(), preview.scopeId());
        List<AgentMountVersion> before = sorted(mountRepository.findLatest(preview.scope(), preview.scopeId()));
        if (!preview.baseEtag().equals(scopeEtag(before))) {
            throw new PreconditionFailedException("Agent configuration changed after the import preview");
        }
        for (AgentMountDraft draft : drafts) {
            appendLocked(preview.scope(), preview.scopeId(), target.sceneId(), draft, actorId);
        }
        Instant appliedAt = Instant.now(clock);
        if (!mountRepository.markImportApplied(importId, actorId, appliedAt)) {
            throw new ConflictException("configuration import was applied concurrently");
        }
        List<AgentMountVersion> after = sorted(mountRepository.findLatest(preview.scope(), preview.scopeId()));
        String etag = scopeEtag(after);
        auditService.record(actorId, "CONFIGURATION_IMPORT_APPLIED", "CONFIGURATION_IMPORT", importId,
                Map.of("scope", preview.scope(), "manifestHash", preview.manifestHash(), "resultEtag", etag),
                traceId);
        return new ScopeConfiguration(preview.scope(), preview.scopeId(), target.sceneId(), etag, after);
    }

    @Transactional(readOnly = true)
    public AgentConfigurationCatalog catalog() {
        List<ModelCatalogEntry> models = modelRepository.findConnections().stream()
                .filter(ModelConnection::enabled)
                .flatMap(connection -> modelRepository.findConfigVersions(connection.id()).stream()
                        .map(version -> new ModelCatalogEntry(version.id(), connection.id(), connection.name(),
                                connection.provider().name(), version.version(), version.modelId(),
                                version.temperature(), version.maxOutputTokens())))
                .sorted(Comparator.comparing(ModelCatalogEntry::connectionName)
                        .thenComparing(ModelCatalogEntry::version).reversed())
                .toList();
        List<SkillCatalogEntry> skills = skillRepository.findSkills(null, null).stream()
                .flatMap(skill -> skillRepository.findVersions(skill.id()).stream()
                        .map(version -> new SkillCatalogEntry(version.id(), skill.id(), skill.name(),
                                skill.kind(), skill.sceneId(), version.version(), version.packageHash())))
                .sorted(Comparator.comparing(SkillCatalogEntry::name)
                        .thenComparing(SkillCatalogEntry::version).reversed())
                .toList();
        return new AgentConfigurationCatalog(models, skills);
    }

    private EffectiveAgentConfiguration resolveRole(AgentRole role, AgentRoleTemplateVersion template,
            AgentMountVersion global, AgentMountVersion scene, AgentMountVersion subScene) {
        Boolean enabled = null;
        UUID model = null;
        UUID skill = null;
        String options = template == null ? "{}" : template.defaultOptionsJson();
        AgentMountScope enabledSource = null;
        AgentMountScope modelSource = null;
        AgentMountScope skillSource = null;
        String optionsSource = template == null ? null : "TEMPLATE";
        List<AgentMountVersion> lineage = new ArrayList<>();
        for (AgentMountVersion mount : new AgentMountVersion[] {global, scene, subScene}) {
            if (mount == null) {
                continue;
            }
            lineage.add(mount);
            if (mount.enabled() != null) {
                enabled = mount.enabled();
                enabledSource = mount.scope();
            }
            if (mount.modelConfigVersionId() != null) {
                model = mount.modelConfigVersionId();
                modelSource = mount.scope();
            }
            if (mount.skillVersionId() != null) {
                skill = mount.skillVersionId();
                skillSource = mount.scope();
            }
            if (mount.optionsJson() != null) {
                options = mount.optionsJson();
                optionsSource = mount.scope().name();
            }
        }
        UUID leafVersionId = lineage.isEmpty() ? null : lineage.getLast().id();
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("role", role);
        canonical.put("templateHash", template == null ? null : template.configHash());
        canonical.put("enabled", Boolean.TRUE.equals(enabled));
        canonical.put("modelConfigVersionId", model);
        canonical.put("skillVersionId", skill);
        canonical.put("options", parseJson(options));
        canonical.put("lineageHashes", lineage.stream().map(AgentMountVersion::configHash).toList());
        String effectiveHash = Hashes.sha256(toJson(canonical));
        return new EffectiveAgentConfiguration(role, role.displayName(), role.stage(), Boolean.TRUE.equals(enabled),
                model, skill, options, effectiveHash, leafVersionId,
                enabledSource, modelSource, skillSource, optionsSource, List.copyOf(lineage));
    }

    private AgentMountVersion appendLocked(AgentMountScope scope, UUID scopeId, UUID sceneId,
            AgentMountDraft draft, UUID actorId) {
        AgentMountDraft normalized = normalizeDraft(draft, sceneId, scope);
        int nextVersion = mountRepository.findLatest(scope, scopeId, normalized.role())
                .map(value -> value.version() + 1).orElse(1);
        AgentRoleTemplateVersion template = latestTemplates().get(normalized.role());
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("role", normalized.role());
        canonical.put("scope", scope);
        canonical.put("scopeId", scopeId);
        canonical.put("version", nextVersion);
        canonical.put("templateVersionId", template == null ? null : template.id());
        canonical.put("enabled", normalized.enabled());
        canonical.put("modelConfigVersionId", normalized.modelConfigVersionId());
        canonical.put("skillVersionId", normalized.skillVersionId());
        canonical.put("options", normalized.options());
        String hash = Hashes.sha256(toJson(canonical));
        AgentMountVersion version = new AgentMountVersion(UUID.randomUUID(), normalized.role(), scope, scopeId,
                nextVersion, template == null ? null : template.id(), normalized.enabled(),
                normalized.modelConfigVersionId(), normalized.skillVersionId(),
                normalized.options() == null ? null : toJson(normalized.options()), hash, actorId, Instant.now(clock));
        return mountRepository.insert(version, sceneId);
    }

    private AgentMountDraft normalizeDraft(AgentMountDraft draft, UUID sceneId, AgentMountScope scope) {
        if (draft == null || draft.role() == null) {
            throw new IllegalArgumentException("Agent role is required");
        }
        if (draft.enabled() == null && draft.modelConfigVersionId() == null && draft.skillVersionId() == null
                && draft.options() == null) {
            throw new IllegalArgumentException("Agent mount must override at least one field");
        }
        if (draft.modelConfigVersionId() != null) {
            ModelConfigVersion model = modelRepository.findConfigVersion(draft.modelConfigVersionId())
                    .orElseThrow(() -> new NotFoundException("model configuration version not found: "
                            + draft.modelConfigVersionId()));
            ModelConnection connection = modelRepository.findConnection(model.modelConnectionId())
                    .orElseThrow(() -> new NotFoundException("model connection not found: "
                            + model.modelConnectionId()));
            if (!connection.enabled()) {
                throw new ConflictException("model connection is disabled: " + connection.id());
            }
        }
        if (draft.skillVersionId() != null) {
            SkillVersion version = skillRepository.findVersion(draft.skillVersionId())
                    .orElseThrow(() -> new NotFoundException("Skill version not found: " + draft.skillVersionId()));
            Skill skill = skillRepository.findById(version.skillId())
                    .orElseThrow(() -> new NotFoundException("Skill not found: " + version.skillId()));
            if (scope == AgentMountScope.GLOBAL && skill.kind() != SkillKind.TEMPLATE) {
                throw new IllegalArgumentException("GLOBAL Agent mounts may only use template Skills");
            }
            if (skill.kind() == SkillKind.INSTANCE && !Objects.equals(skill.sceneId(), sceneId)) {
                throw new IllegalArgumentException("Skill instance does not belong to the Agent mount scene");
            }
        }
        Map<String, Object> options = normalizeOptions(draft.options());
        return new AgentMountDraft(draft.role(), draft.enabled(), draft.modelConfigVersionId(),
                draft.skillVersionId(), options);
    }

    private List<AgentMountDraft> normalizeDrafts(List<AgentMountDraft> drafts, UUID sceneId, AgentMountScope scope) {
        if (drafts == null || drafts.isEmpty()) {
            throw new IllegalArgumentException("configuration import requires at least one role");
        }
        if (drafts.size() > AgentRole.values().length) {
            throw new IllegalArgumentException("configuration import contains too many roles");
        }
        List<AgentMountDraft> normalized = drafts.stream()
                .map(draft -> normalizeDraft(draft, sceneId, scope))
                .sorted(Comparator.comparing(item -> item.role().name()))
                .toList();
        if (new HashSet<>(normalized.stream().map(AgentMountDraft::role).toList()).size() != normalized.size()) {
            throw new IllegalArgumentException("configuration import contains duplicate roles");
        }
        return normalized;
    }

    private Map<String, Object> normalizeOptions(Map<String, Object> options) {
        if (options == null) {
            return null;
        }
        JsonNode node = objectMapper.valueToTree(options);
        rejectForbiddenKeys(node);
        String json = toJson(node);
        if (json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_OPTIONS_BYTES) {
            throw new IllegalArgumentException("Agent options exceed " + MAX_OPTIONS_BYTES + " bytes");
        }
        return fromJson(json, new com.fasterxml.jackson.core.type.TypeReference<>() { });
    }

    private void rejectForbiddenKeys(JsonNode node) {
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                if (FORBIDDEN_OPTION_KEYS.contains(entry.getKey().toLowerCase(java.util.Locale.ROOT))) {
                    throw new IllegalArgumentException("Agent options may not contain secrets or executable content");
                }
                rejectForbiddenKeys(entry.getValue());
            });
        } else if (node.isArray()) {
            node.forEach(this::rejectForbiddenKeys);
        }
    }

    private List<ConfigurationDiff> buildDiff(List<AgentMountVersion> current, List<AgentMountDraft> drafts) {
        Map<AgentRole, AgentMountVersion> before = index(current);
        return drafts.stream().map(draft -> {
            AgentMountVersion old = before.get(draft.role());
            List<String> changed = new ArrayList<>();
            if (!Objects.equals(old == null ? null : old.enabled(), draft.enabled())) changed.add("enabled");
            if (!Objects.equals(old == null ? null : old.modelConfigVersionId(), draft.modelConfigVersionId())) {
                changed.add("modelConfigVersionId");
            }
            if (!Objects.equals(old == null ? null : old.skillVersionId(), draft.skillVersionId())) {
                changed.add("skillVersionId");
            }
            JsonNode oldOptions = old == null || old.optionsJson() == null ? null : parseJson(old.optionsJson());
            if (!Objects.equals(oldOptions, objectMapper.valueToTree(draft.options()))) changed.add("options");
            return new ConfigurationDiff(draft.role(), old, draft, List.copyOf(changed));
        }).toList();
    }

    private ScopeTarget requireTarget(AgentMountScope scope, UUID scopeId) {
        if (scope == null) throw new IllegalArgumentException("Agent mount scope is required");
        return switch (scope) {
            case GLOBAL -> {
                if (scopeId != null) throw new IllegalArgumentException("GLOBAL scope must not include scopeId");
                yield new ScopeTarget(null);
            }
            case SCENE -> {
                if (scopeId == null) throw new IllegalArgumentException("SCENE scope requires scopeId");
                sceneRepository.findScene(scopeId)
                        .orElseThrow(() -> new NotFoundException("scene not found: " + scopeId));
                yield new ScopeTarget(scopeId);
            }
            case SUB_SCENE -> {
                if (scopeId == null) throw new IllegalArgumentException("SUB_SCENE scope requires scopeId");
                SubScene subScene = sceneRepository.findSubScene(scopeId)
                        .orElseThrow(() -> new NotFoundException("sub-scene not found: " + scopeId));
                yield new ScopeTarget(subScene.sceneId());
            }
        };
    }

    private void requireResolutionTarget(UUID sceneId, UUID subSceneId) {
        if (sceneId == null) throw new IllegalArgumentException("sceneId is required");
        sceneRepository.findScene(sceneId).orElseThrow(() -> new NotFoundException("scene not found: " + sceneId));
        if (subSceneId != null) {
            SubScene subScene = sceneRepository.findSubScene(subSceneId)
                    .orElseThrow(() -> new NotFoundException("sub-scene not found: " + subSceneId));
            if (!subScene.sceneId().equals(sceneId)) {
                throw new IllegalArgumentException("sub-scene does not belong to the requested scene");
            }
        }
    }

    private void assertScopeEtag(List<AgentMountVersion> mounts, String ifMatch) {
        String current = scopeEtag(mounts);
        String expected = normalizeEtag(ifMatch);
        if ("*".equals(expected)) {
            if (!mounts.isEmpty()) {
                throw new PreconditionFailedException("Agent configuration already has a version");
            }
        } else if (!current.equals(expected)) {
            throw new PreconditionFailedException("Agent configuration changed; refresh before saving");
        }
    }

    private void requireIfMatch(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new PreconditionRequiredException("If-Match is required for Agent configuration changes");
        }
    }

    private String normalizeEtag(String value) {
        String normalized = value.trim();
        if (normalized.startsWith("W/")) normalized = normalized.substring(2).trim();
        if (normalized.length() >= 2 && normalized.startsWith("\"") && normalized.endsWith("\"")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return normalized;
    }

    private String scopeEtag(List<AgentMountVersion> mounts) {
        return Hashes.sha256(toJson(sorted(mounts).stream().map(AgentMountVersion::configHash).toList()));
    }

    private Map<AgentRole, AgentRoleTemplateVersion> latestTemplates() {
        Map<AgentRole, AgentRoleTemplateVersion> result = new EnumMap<>(AgentRole.class);
        mountRepository.findLatestTemplates().forEach(template -> result.put(template.role(), template));
        return result;
    }

    private Map<AgentRole, AgentMountVersion> index(List<AgentMountVersion> mounts) {
        Map<AgentRole, AgentMountVersion> result = new EnumMap<>(AgentRole.class);
        mounts.forEach(mount -> result.put(mount.role(), mount));
        return result;
    }

    private List<AgentMountVersion> sorted(List<AgentMountVersion> mounts) {
        return mounts.stream().sorted(Comparator.comparing(value -> value.role().name())).toList();
    }

    private Map<String, Object> auditDetails(AgentMountScope scope, UUID scopeId, AgentMountVersion version) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("scope", scope);
        if (scopeId != null) details.put("scopeId", scopeId);
        details.put("role", version.role());
        details.put("version", version.version());
        details.put("configHash", version.configHash());
        return details;
    }

    private JsonNode parseJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("persisted Agent configuration JSON is invalid", exception);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Agent configuration is not serializable", exception);
        }
    }

    private <T> T fromJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("persisted Agent configuration is invalid", exception);
        }
    }

    private <T> T fromJson(String json, com.fasterxml.jackson.core.type.TypeReference<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Agent options are invalid", exception);
        }
    }

    public record RoleDefinition(AgentRole role, String displayName, String stage, String description) { }

    public record AgentMountDraft(AgentRole role, Boolean enabled, UUID modelConfigVersionId,
            UUID skillVersionId, Map<String, Object> options) { }

    public record ScopeConfiguration(AgentMountScope scope, UUID scopeId, UUID sceneId, String etag,
            List<AgentMountVersion> mounts) {
        public ScopeConfiguration { mounts = List.copyOf(mounts); }
    }

    public record EffectiveAgentConfiguration(AgentRole role, String displayName, String stage, boolean enabled,
            UUID modelConfigVersionId, UUID skillVersionId, String optionsJson, String effectiveHash,
            UUID effectiveMountVersionId, AgentMountScope enabledSource, AgentMountScope modelSource,
            AgentMountScope skillSource, String optionsSource, List<AgentMountVersion> lineage) {
        public EffectiveAgentConfiguration { lineage = List.copyOf(lineage); }
        public boolean configured() { return enabled && modelConfigVersionId != null && skillVersionId != null; }
        public boolean isConfigured() { return configured(); }
    }

    public record ImportManifest(String schemaVersion, AgentMountScope scope, UUID scopeId,
            List<AgentMountDraft> roles) {
        public ImportManifest { roles = List.copyOf(roles); }
    }

    public record ConfigurationDiff(AgentRole role, AgentMountVersion before, AgentMountDraft after,
            List<String> changedFields) { }

    public record ModelCatalogEntry(UUID versionId, UUID connectionId, String connectionName, String provider,
            int version, String modelId, java.math.BigDecimal temperature, int maxOutputTokens) { }

    public record SkillCatalogEntry(UUID versionId, UUID skillId, String name, SkillKind kind, UUID sceneId,
            int version, String packageHash) { }

    public record AgentConfigurationCatalog(List<ModelCatalogEntry> models, List<SkillCatalogEntry> skills) { }

    private record ScopeTarget(UUID sceneId) { }
}
