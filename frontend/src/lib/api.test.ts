import { afterEach, describe, expect, it, vi } from "vitest";
import {
  abortUpload,
  appendAgentMount,
  applyConfigurationImport,
  adoptAlignmentProposal,
  ApiError,
  changePassword,
  completeUpload,
  createEmbeddingProfile,
  createExtractionRound,
  createModelConfigVersion,
  createModelConnection,
  createRelease,
  createScene,
  createSubScene,
  createUploadIntent,
  createUser,
  deleteModelConnection,
  generateAssets,
  getJob,
  getEvaluationRun,
  getKnowledgeDocument,
  getLatestRelease,
  getReleaseManifest,
  getScene,
  listDocumentRevisions,
  listEmbeddingProfiles,
  listExtractionRounds,
  listEvaluationRuns,
  listModelConfigVersions,
  listModelConnections,
  listAuditEvents,
  listAlignmentProposals,
  createSkill,
  createSkillVersion,
  forkSkillInstance,
  getSkill,
  listSkills,
  listScenes,
  listSubSceneAssets,
  listSubScenes,
  listUsers,
  getAgentScope,
  listWorkbenchMaterials,
  login,
  resetUserPassword,
  saveKnowledgeDocument,
  startAlignment,
  startExtraction,
  startReleaseEvaluation,
  testModelConnection,
  updateModelConnection,
  updateScene,
  updateUser,
  validateRelease,
  previewConfigurationImport,
  retrieveKnowledgeChunks,
} from "./api";
import type { CreateUploadIntentDraft } from "./api";

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("session API client", () => {
  it("logs in with a fresh CSRF token and then loads the session user", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(Response.json({
        headerName: "X-XSRF-TOKEN",
        parameterName: "_csrf",
        token: "csrf-token",
      }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
      .mockResolvedValueOnce(Response.json({
        id: "ecbbf3db-0497-4c9c-82b1-14765106ffc7",
        username: "admin",
        displayName: "Administrator",
        enabled: true,
        roles: ["ADMIN"],
        mustChangePassword: true,
      }));
    vi.stubGlobal("fetch", fetchMock);

    const user = await login("admin", "not-persisted");

    expect(user.mustChangePassword).toBe(true);
    expect(fetchMock).toHaveBeenCalledTimes(3);
    expect(fetchMock.mock.calls[1]?.[0]).toBe("/api/v1/auth/login");
    expect(fetchMock.mock.calls[1]?.[1]).toMatchObject({
      method: "POST",
      credentials: "same-origin",
      headers: {
        "Content-Type": "application/json",
        "X-XSRF-TOKEN": "csrf-token",
      },
    });
  });

  it("rejects an unsafe server-provided CSRF header name before mutation", async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(Response.json({
      headerName: "Injected\r\nHeader",
      parameterName: "_csrf",
      token: "csrf-token",
    }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(changePassword("current-password", "new-password-123"))
      .rejects.toBeInstanceOf(ApiError);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("surfaces the stable problem detail without exposing the submitted password", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(Response.json({
        headerName: "X-XSRF-TOKEN",
        parameterName: "_csrf",
        token: "csrf-token",
      }))
      .mockResolvedValueOnce(Response.json({
        detail: "Invalid username or password",
        code: "authentication-failed",
        traceId: "trace-123",
      }, { status: 401, headers: { "Content-Type": "application/problem+json" } }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(login("admin", "highly-sensitive-password"))
      .rejects.toMatchObject({
        message: "Invalid username or password",
        code: "authentication-failed",
        traceId: "trace-123",
      });
  });
});

describe("model connection API client", () => {
  const csrfBody = { headerName: "X-XSRF-TOKEN", parameterName: "_csrf", token: "csrf-token" };
  const connection = {
    id: "4f06e31c-4d52-4a0e-bb0f-1e0b0f1e0b0f",
    name: "DashScope 主网关",
    provider: "DASHSCOPE",
    baseUrl: "https://dashscope.aliyuncs.com/compatible-mode/v1",
    enabled: true,
    credentialConfigured: true,
    validationStatus: "CONNECTIVITY_VERIFIED",
    lastValidatedAt: "2026-08-03T08:00:00Z",
    createdAt: "2026-08-01T08:00:00Z",
    updatedAt: "2026-08-03T08:00:00Z",
  };

  it("lists connections and never receives a credential field", async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(Response.json([connection]));
    vi.stubGlobal("fetch", fetchMock);

    const connections = await listModelConnections();

    expect(connections).toHaveLength(1);
    expect(connections[0]).toMatchObject({ name: "DashScope 主网关", credentialConfigured: true });
    expect(fetchMock).toHaveBeenCalledWith("/api/v1/model-connections",
      expect.objectContaining({ credentials: "same-origin" }));
  });

  it("creates a connection with a fresh CSRF token and the write-only credential", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(Response.json(csrfBody))
      .mockResolvedValueOnce(Response.json({ ...connection, id: "new-connection-id" }, { status: 201 }));
    vi.stubGlobal("fetch", fetchMock);

    const result = await createModelConnection({
      name: "企业网关", provider: "OPENAI_COMPATIBLE",
      baseUrl: "https://api.example.com/v1", credential: "test-credential-not-a-real-key", enabled: true,
    });

    expect(result.id).toBe("new-connection-id");
    expect(fetchMock.mock.calls[1]?.[0]).toBe("/api/v1/model-connections");
    expect(fetchMock.mock.calls[1]?.[1]).toMatchObject({
      method: "POST",
      credentials: "same-origin",
      headers: { "Content-Type": "application/json", "X-XSRF-TOKEN": "csrf-token" },
    });
    expect(JSON.parse(String(fetchMock.mock.calls[1]?.[1]?.body))).toEqual({
      name: "企业网关", provider: "OPENAI_COMPATIBLE",
      baseUrl: "https://api.example.com/v1", credential: "test-credential-not-a-real-key", enabled: true,
    });
  });

  it("omits an empty credential from create and update payloads", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(Response.json(csrfBody))
      .mockResolvedValueOnce(Response.json(connection, { status: 201 }));
    vi.stubGlobal("fetch", fetchMock);

    await createModelConnection({ name: "网关", provider: "OPENAI_COMPATIBLE", baseUrl: "https://api.example.com/v1", credential: "   ", enabled: true });

    expect(JSON.parse(String(fetchMock.mock.calls[1]?.[1]?.body))).not.toHaveProperty("credential");
  });

  it("updates via PUT and sends clearCredential without re-echoing any credential", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(Response.json(csrfBody))
      .mockResolvedValueOnce(Response.json({ ...connection, name: "改名后的网关" }));
    vi.stubGlobal("fetch", fetchMock);

    const result = await updateModelConnection(connection.id, {
      name: "改名后的网关", provider: "DASHSCOPE",
      baseUrl: connection.baseUrl, enabled: false, clearCredential: true,
    });

    expect(result.name).toBe("改名后的网关");
    expect(fetchMock.mock.calls[1]?.[1]).toMatchObject({ method: "PUT" });
    const body = JSON.parse(String(fetchMock.mock.calls[1]?.[1]?.body));
    expect(body).toMatchObject({ name: "改名后的网关", clearCredential: true, enabled: false });
    expect(body).not.toHaveProperty("credential");
  });

  it("soft-deletes with a CSRF token and resolves on 204", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(Response.json(csrfBody))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    vi.stubGlobal("fetch", fetchMock);

    await deleteModelConnection(connection.id);

    expect(fetchMock.mock.calls[1]?.[1]).toMatchObject({ method: "DELETE" });
  });

  it("parses a real connectivity result from the connection-tests endpoint", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(Response.json(csrfBody))
      .mockResolvedValueOnce(Response.json({
        status: "CONNECTED",
        networkAttempted: true,
        connectivityVerified: true,
        credentialConfigured: true,
        messageCode: "model.connection.verified",
        testedAt: "2026-08-03T09:00:00Z",
      }));
    vi.stubGlobal("fetch", fetchMock);

    const result = await testModelConnection(connection.id);

    expect(result).toMatchObject({
      status: "CONNECTED",
      networkAttempted: true,
      connectivityVerified: true,
      messageCode: "model.connection.verified",
    });
    expect(fetchMock.mock.calls[1]?.[0]).toBe(`/api/v1/model-connections/${connection.id}/connection-tests`);
  });

  it("lists immutable config versions and appends a new one", async () => {
    const existing = {
      id: "version-2", modelConnectionId: connection.id, version: 2,
      modelId: "qwen-max", temperature: 0.4, maxOutputTokens: 4096,
      createdAt: "2026-08-03T10:00:00Z",
    };
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(Response.json([existing]))
      .mockResolvedValueOnce(Response.json(csrfBody))
      .mockResolvedValueOnce(Response.json({ ...existing, id: "version-3", version: 3, temperature: 0.2 }, { status: 201 }));
    vi.stubGlobal("fetch", fetchMock);

    const versions = await listModelConfigVersions(connection.id);
    expect(versions[0]).toMatchObject({ version: 2, modelId: "qwen-max", maxOutputTokens: 4096 });

    const created = await createModelConfigVersion(connection.id, { modelId: "qwen-max", temperature: 0.2, maxOutputTokens: 8192 });
    expect(created.version).toBe(3);
    expect(fetchMock.mock.calls[2]?.[0]).toBe(`/api/v1/model-connections/${connection.id}/config-versions`);
    expect(JSON.parse(String(fetchMock.mock.calls[2]?.[1]?.body))).toEqual({
      modelId: "qwen-max", temperature: 0.2, maxOutputTokens: 8192,
    });
  });

  it("surfaces a 403 permission problem from the list endpoint", async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(Response.json({
      type: "https://knowledge-melting-pot.local/problems/forbidden",
      title: "Forbidden",
      status: 403,
      detail: "ADMIN role required",
      code: "forbidden",
      traceId: "trace-403",
    }, { status: 403, headers: { "Content-Type": "application/problem+json" } }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(listModelConnections())
      .rejects.toMatchObject({ status: 403, message: "ADMIN role required", code: "forbidden", traceId: "trace-403" });
  });

  it("surfaces field-level validation errors from a rejected create", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(Response.json(csrfBody))
      .mockResolvedValueOnce(Response.json({
        type: "https://knowledge-melting-pot.local/problems/validation",
        title: "Validation failed",
        status: 400,
        detail: "One or more fields are invalid",
        code: "validation",
        traceId: "trace-400",
        errors: [{ field: "baseUrl", message: "must match the host whitelist" }],
      }, { status: 400, headers: { "Content-Type": "application/problem+json" } }));
    vi.stubGlobal("fetch", fetchMock);

    const failure = await createModelConnection({
      name: "x", provider: "OPENAI_COMPATIBLE", baseUrl: "https://denied.example.com/v1", enabled: true,
    }).catch((reason: unknown) => reason);

    expect(failure).toBeInstanceOf(ApiError);
    expect(failure).toMatchObject({
      status: 400,
      message: "One or more fields are invalid",
      errors: [{ field: "baseUrl", message: "must match the host whitelist" }],
    });
  });
});

describe("embedding and dense retrieval API client", () => {
  const csrfBody = { headerName: "X-XSRF-TOKEN", parameterName: "_csrf", token: "csrf-token" };
  const profile = {
    id: "10000000-0000-4000-8000-000000000001",
    modelConnectionId: "20000000-0000-4000-8000-000000000001",
    provider: "DASHSCOPE",
    modelId: "text-embedding-v4",
    dimension: 1024,
    profileVersion: "2026-08",
    normalization: "L2",
    distanceFunction: "COSINE",
    active: true,
    createdAt: "2026-08-04T08:00:00Z",
  };

  it("lists immutable profiles and creates a new active version with CSRF", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(Response.json([profile]))
      .mockResolvedValueOnce(Response.json(csrfBody))
      .mockResolvedValueOnce(Response.json(profile, { status: 201 }));
    vi.stubGlobal("fetch", fetchMock);

    const profiles = await listEmbeddingProfiles();
    expect(profiles[0]).toMatchObject({ active: true, dimension: 1024 });
    const created = await createEmbeddingProfile({
      modelConnectionId: profile.modelConnectionId,
      modelId: profile.modelId,
      dimension: profile.dimension,
      profileVersion: profile.profileVersion,
      normalization: "L2",
      distanceFunction: "COSINE",
    });

    expect(created.id).toBe(profile.id);
    expect(fetchMock.mock.calls[2]?.[0]).toBe("/api/v1/embedding-profiles");
    expect(fetchMock.mock.calls[2]?.[1]).toMatchObject({
      method: "POST",
      headers: { "Content-Type": "application/json", "X-XSRF-TOKEN": "csrf-token" },
    });
  });

  it("encodes the Chinese query and retrieval scope without a mutation token", async () => {
    const result = {
      chunkId: "30000000-0000-4000-8000-000000000001",
      materialId: "40000000-0000-4000-8000-000000000001",
      sourceRefCode: "SRC-risk-0",
      locatorType: "TXT_LINES",
      page: null,
      paragraph: null,
      table: null,
      sheet: null,
      rowStart: null,
      rowEnd: null,
      colStart: null,
      colEnd: null,
      lineStart: 1,
      lineEnd: 3,
      excerpt: "逾期超过三十天时进入重点复核。",
      score: 0.91,
    };
    const fetchMock = vi.fn().mockResolvedValueOnce(Response.json([result]));
    vi.stubGlobal("fetch", fetchMock);

    const results = await retrieveKnowledgeChunks("round-1", "sub-1", "逾期 风险", 8);

    expect(results[0]).toMatchObject({ sourceRefCode: "SRC-risk-0", score: 0.91 });
    expect(fetchMock.mock.calls[0]?.[0]).toBe(
      "/api/v1/retrieval/chunks?roundId=round-1&subSceneId=sub-1&q=%E9%80%BE%E6%9C%9F+%E9%A3%8E%E9%99%A9&topK=8",
    );
    expect(fetchMock.mock.calls[0]?.[1]).toMatchObject({ credentials: "same-origin", cache: "no-store" });
  });
});

describe("Agent governance API client", () => {
  const csrfBody = { headerName: "X-XSRF-TOKEN", parameterName: "_csrf", token: "csrf-token" };
  const sceneId = "00000000-0000-4000-8000-000000000001";
  const scope = { scope: "SCENE", scopeId: sceneId, sceneId, etag: "a".repeat(64), mounts: [] };
  const draft = {
    role: "KNOWLEDGE_EXTRACTOR" as const,
    enabled: true,
    modelConfigVersionId: null,
    skillVersionId: null,
    options: null,
  };

  it("loads a scope and appends a version with If-Match and CSRF", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(Response.json(scope))
      .mockResolvedValueOnce(Response.json(csrfBody))
      .mockResolvedValueOnce(Response.json({ ...scope, etag: "b".repeat(64) }));
    vi.stubGlobal("fetch", fetchMock);

    const loaded = await getAgentScope("SCENE", sceneId);
    const saved = await appendAgentMount("SCENE", sceneId, loaded.etag, draft);

    expect(saved.etag).toBe("b".repeat(64));
    expect(fetchMock.mock.calls[2]?.[1]).toMatchObject({
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "X-XSRF-TOKEN": "csrf-token",
        "If-Match": "a".repeat(64),
      },
    });
    expect(JSON.parse(String(fetchMock.mock.calls[2]?.[1]?.body))).toMatchObject({
      scope: "SCENE", scopeId: sceneId, role: "KNOWLEDGE_EXTRACTOR", enabled: true,
    });
  });

  it("previews an import and applies exactly the frozen manifest hash", async () => {
    const preview = {
      id: "00000000-0000-4000-8000-000000000099",
      manifestHash: "c".repeat(64),
      scope: "SCENE",
      scopeId: sceneId,
    };
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(Response.json(csrfBody))
      .mockResolvedValueOnce(Response.json(preview))
      .mockResolvedValueOnce(Response.json(csrfBody))
      .mockResolvedValueOnce(Response.json(scope));
    vi.stubGlobal("fetch", fetchMock);

    const frozen = await previewConfigurationImport("SCENE", sceneId, [draft]);
    await applyConfigurationImport(frozen.id, frozen.manifestHash);

    expect(fetchMock.mock.calls[3]?.[0]).toBe(`/api/v1/configuration-imports/${preview.id}/apply`);
    expect(fetchMock.mock.calls[3]?.[1]).toMatchObject({
      headers: expect.objectContaining({ "If-Match": "c".repeat(64) }),
    });
  });
});

describe("user management API client", () => {
  const csrfBody = { headerName: "X-XSRF-TOKEN", parameterName: "_csrf", token: "csrf-token" };
  const operator = {
    id: "e1b7a1f2-2222-4000-8000-000000000002",
    username: "linan",
    displayName: "李楠",
    roles: ["OPERATOR"],
    enabled: true,
    mustChangePassword: true,
  };

  it("lists accounts without any credential material", async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(Response.json([operator]));
    vi.stubGlobal("fetch", fetchMock);

    const users = await listUsers();

    expect(users).toHaveLength(1);
    expect(users[0]).toMatchObject({ username: "linan", displayName: "李楠", mustChangePassword: true });
    expect(users[0]).not.toHaveProperty("initialPassword");
    expect(users[0]).not.toHaveProperty("passwordHash");
    expect(fetchMock).toHaveBeenCalledWith("/api/v1/users",
      expect.objectContaining({ credentials: "same-origin" }));
  });

  it("creates a user with a fresh CSRF token and a write-only initial password", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(Response.json(csrfBody))
      .mockResolvedValueOnce(Response.json(operator, { status: 201 }));
    vi.stubGlobal("fetch", fetchMock);

    const created = await createUser({
      username: "linan", displayName: "李楠",
      initialPassword: "test-initial-password-not-real", roles: ["OPERATOR"],
    });

    expect(created.id).toBe(operator.id);
    expect(fetchMock.mock.calls[1]?.[0]).toBe("/api/v1/users");
    expect(fetchMock.mock.calls[1]?.[1]).toMatchObject({
      method: "POST",
      headers: { "Content-Type": "application/json", "X-XSRF-TOKEN": "csrf-token" },
    });
    expect(JSON.parse(String(fetchMock.mock.calls[1]?.[1]?.body))).toEqual({
      username: "linan", displayName: "李楠",
      initialPassword: "test-initial-password-not-real", roles: ["OPERATOR"],
    });
  });

  it("patches only the supplied fields via PATCH with CSRF", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(Response.json(csrfBody))
      .mockResolvedValueOnce(Response.json({ ...operator, displayName: "李楠（发布）", roles: ["OPERATOR", "PUBLISHER"] }));
    vi.stubGlobal("fetch", fetchMock);

    const updated = await updateUser(operator.id, { displayName: "李楠（发布）", roles: ["OPERATOR", "PUBLISHER"] });

    expect(updated.displayName).toBe("李楠（发布）");
    expect(fetchMock.mock.calls[1]?.[1]).toMatchObject({ method: "PATCH" });
    const body = JSON.parse(String(fetchMock.mock.calls[1]?.[1]?.body));
    expect(body).toEqual({ displayName: "李楠（发布）", roles: ["OPERATOR", "PUBLISHER"] });
    expect(body).not.toHaveProperty("enabled");
  });

  it("resets a password via POST and resolves on 204", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(Response.json(csrfBody))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    vi.stubGlobal("fetch", fetchMock);

    await resetUserPassword(operator.id, "replacement-password-not-real");

    expect(fetchMock.mock.calls[1]?.[0]).toBe(`/api/v1/users/${operator.id}/password-reset`);
    expect(JSON.parse(String(fetchMock.mock.calls[1]?.[1]?.body)))
      .toEqual({ newPassword: "replacement-password-not-real" });
  });

  it("surfaces the 409 self-reset protection problem", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(Response.json(csrfBody))
      .mockResolvedValueOnce(Response.json({
        detail: "an administrator cannot reset their own password; use /api/v1/auth/password",
        code: "conflict",
        traceId: "trace-409",
      }, { status: 409, headers: { "Content-Type": "application/problem+json" } }));
    vi.stubGlobal("fetch", fetchMock);

    const failure = await resetUserPassword(operator.id, "replacement-password-not-real")
      .catch((reason: unknown) => reason);

    expect(failure).toBeInstanceOf(ApiError);
    expect(failure).toMatchObject({
      status: 409,
      code: "conflict",
      message: expect.stringContaining("cannot reset their own password"),
    });
  });

  it("surfaces a 403 permission problem from the list endpoint", async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(Response.json({
      detail: "ADMIN role required", code: "forbidden", traceId: "trace-403",
    }, { status: 403, headers: { "Content-Type": "application/problem+json" } }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(listUsers())
      .rejects.toMatchObject({ status: 403, message: "ADMIN role required", code: "forbidden" });
  });
});

describe("scene API client", () => {
  const csrfBody = { headerName: "X-XSRF-TOKEN", parameterName: "_csrf", token: "csrf-token" };
  const scene = {
    id: "3f7a1c2e-0000-4000-8000-000000000001",
    name: "对公贷款五级分类",
    description: "从监管制度、行内细则和标注案例中萃取可追溯的分类规则。",
    createdAt: "2026-08-01T08:00:00Z",
    updatedAt: "2026-08-03T10:00:00Z",
  };

  it("lists scenes with page and size query parameters", async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(Response.json({
      items: [scene], page: 0, size: 20, total: 1,
    }));
    vi.stubGlobal("fetch", fetchMock);

    const page = await listScenes(0, 20);

    expect(page.items).toHaveLength(1);
    expect(page.total).toBe(1);
    expect(page.items[0]).toMatchObject({ name: "对公贷款五级分类" });
    expect(fetchMock).toHaveBeenCalledWith("/api/v1/scenes?page=0&size=20",
      expect.objectContaining({ credentials: "same-origin" }));
  });

  it("requests the next page offset and returns the server page number", async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(Response.json({
      items: [scene], page: 1, size: 20, total: 21,
    }));
    vi.stubGlobal("fetch", fetchMock);

    const page = await listScenes(1, 20);

    expect(page.page).toBe(1);
    expect(page.total).toBe(21);
    expect(fetchMock).toHaveBeenCalledWith("/api/v1/scenes?page=1&size=20",
      expect.objectContaining({ credentials: "same-origin" }));
  });

  it("creates a scene with CSRF and an optional description", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(Response.json(csrfBody))
      .mockResolvedValueOnce(Response.json(scene, { status: 201 }));
    vi.stubGlobal("fetch", fetchMock);

    const created = await createScene({ name: "对公贷款五级分类", description: "目标说明" });

    expect(created.id).toBe(scene.id);
    expect(fetchMock.mock.calls[1]?.[0]).toBe("/api/v1/scenes");
    expect(fetchMock.mock.calls[1]?.[1]).toMatchObject({
      method: "POST",
      headers: { "Content-Type": "application/json", "X-XSRF-TOKEN": "csrf-token" },
    });
    expect(JSON.parse(String(fetchMock.mock.calls[1]?.[1]?.body)))
      .toEqual({ name: "对公贷款五级分类", description: "目标说明" });
  });

  it("omits the description from the create body when it is empty", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(Response.json(csrfBody))
      .mockResolvedValueOnce(Response.json(scene, { status: 201 }));
    vi.stubGlobal("fetch", fetchMock);

    await createScene({ name: "对公贷款五级分类" });

    expect(JSON.parse(String(fetchMock.mock.calls[1]?.[1]?.body)))
      .toEqual({ name: "对公贷款五级分类" });
  });

  it("surfaces a 400 validation problem from a rejected create", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(Response.json(csrfBody))
      .mockResolvedValueOnce(Response.json({
        detail: "One or more fields are invalid", code: "validation", traceId: "trace-400",
        errors: [{ field: "name", message: "must not be blank" }],
      }, { status: 400, headers: { "Content-Type": "application/problem+json" } }));
    vi.stubGlobal("fetch", fetchMock);

    const failure = await createScene({ name: "  " }).catch((reason: unknown) => reason);

    expect(failure).toBeInstanceOf(ApiError);
    expect(failure).toMatchObject({
      status: 400,
      message: "One or more fields are invalid",
      errors: [{ field: "name", message: "must not be blank" }],
    });
  });

  it("gets a scene and surfaces a 404 problem detail", async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(Response.json({
      detail: "scene does not exist", code: "not-found", traceId: "trace-404",
    }, { status: 404, headers: { "Content-Type": "application/problem+json" } }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(getScene("missing-scene"))
      .rejects.toMatchObject({ status: 404, message: "scene does not exist", code: "not-found" });
    expect(fetchMock).toHaveBeenCalledWith("/api/v1/scenes/missing-scene",
      expect.objectContaining({ credentials: "same-origin" }));
  });

  it("updates a scene via PUT with CSRF", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(Response.json(csrfBody))
      .mockResolvedValueOnce(Response.json({ ...scene, name: "改名后的场景" }));
    vi.stubGlobal("fetch", fetchMock);

    const updated = await updateScene(scene.id, { name: "改名后的场景", description: "新描述" });

    expect(updated.name).toBe("改名后的场景");
    expect(fetchMock.mock.calls[1]?.[1]).toMatchObject({ method: "PUT" });
    expect(JSON.parse(String(fetchMock.mock.calls[1]?.[1]?.body)))
      .toEqual({ name: "改名后的场景", description: "新描述" });
  });

  it("lists and creates subscenes", async () => {
    const subScene = {
      id: "sub-1", sceneId: scene.id, name: "逾期天数与分类下迁",
      description: "按逾期天数判断最低分类", createdAt: "2026-08-01T08:00:00Z", updatedAt: "2026-08-01T08:00:00Z",
    };
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(Response.json([subScene]))
      .mockResolvedValueOnce(Response.json(csrfBody))
      .mockResolvedValueOnce(Response.json({ ...subScene, id: "sub-2", name: "偿债能力研判" }, { status: 201 }));
    vi.stubGlobal("fetch", fetchMock);

    const list = await listSubScenes(scene.id);
    expect(list[0]).toMatchObject({ name: "逾期天数与分类下迁" });
    expect(fetchMock).toHaveBeenCalledWith(`/api/v1/scenes/${scene.id}/subscenes`,
      expect.objectContaining({ credentials: "same-origin" }));

    const created = await createSubScene(scene.id, { name: "偿债能力研判" });
    expect(created.id).toBe("sub-2");
    expect(JSON.parse(String(fetchMock.mock.calls[2]?.[1]?.body))).toEqual({ name: "偿债能力研判" });
  });

  it("lists and creates extraction rounds with a subSceneId body", async () => {
    const round = {
      id: "round-1", subSceneId: "sub-1", roundNumber: 1, status: "DRAFT",
      createdAt: "2026-08-01T08:00:00Z", updatedAt: "2026-08-01T08:00:00Z",
    };
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(Response.json([round]))
      .mockResolvedValueOnce(Response.json(csrfBody))
      .mockResolvedValueOnce(Response.json({ ...round, id: "round-2", roundNumber: 2 }, { status: 201 }));
    vi.stubGlobal("fetch", fetchMock);

    const rounds = await listExtractionRounds(scene.id);
    expect(rounds[0]).toMatchObject({ roundNumber: 1, status: "DRAFT" });

    const created = await createExtractionRound(scene.id, "sub-1");
    expect(created.roundNumber).toBe(2);
    expect(fetchMock.mock.calls[2]?.[0]).toBe(`/api/v1/scenes/${scene.id}/rounds`);
    expect(JSON.parse(String(fetchMock.mock.calls[2]?.[1]?.body))).toEqual({ subSceneId: "sub-1" });
  });
});

describe("material upload API client", () => {
  const csrfBody = { headerName: "X-XSRF-TOKEN", parameterName: "_csrf", token: "csrf-token" };
  const draft: CreateUploadIntentDraft = {
    fileName: "rules.pdf", sizeBytes: 10, mediaType: "application/pdf",
    sha256: "a".repeat(64), roundId: "round-1", subSceneIds: ["sub-1"],
    partition: "SOURCE", shareScope: "ROUND", regulatorySource: false,
  };

  it("lists workbench materials for a round and sub-scene", async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(Response.json([{
      id: "mat-1", fileName: "rules.pdf", format: "PDF", mediaType: "application/pdf",
      sizeBytes: 10, status: "SCANNING", createdAt: "2026-08-01T08:00:00Z", updatedAt: "2026-08-01T08:00:00Z",
      binding: { id: "b-1", roundId: "round-1", subSceneId: "sub-1", partition: "SOURCE",
        shareScope: "ROUND", regulatorySource: false, active: true },
    }]));
    vi.stubGlobal("fetch", fetchMock);

    const items = await listWorkbenchMaterials("round-1", "sub-1");

    expect(items[0]).toMatchObject({ fileName: "rules.pdf", status: "SCANNING" });
    expect(fetchMock).toHaveBeenCalledWith("/api/v1/materials?roundId=round-1&subSceneId=sub-1",
      expect.objectContaining({ credentials: "same-origin" }));
  });

  it("creates an upload intent with CSRF and the Idempotency-Key header", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(Response.json(csrfBody))
      .mockResolvedValueOnce(Response.json({
        id: "intent-1", materialId: "mat-1", objectKey: "quarantine/mat-1",
        materialStatus: "PENDING_UPLOAD", uploadMode: "MULTIPART_PRESIGNED",
        capabilityStatus: "MULTIPART_PRESIGNED", uploadUrlAvailable: true, maxBytes: 209715200,
        supportedFormats: ["pdf", "docx", "xlsx", "txt"], completionBehavior: "QUEUES_VALIDATION",
        messageCode: "material.upload.multipart-presigned",
        parts: [{ partNumber: 1, url: "https://minio.example/part/1", headers: { "Content-Type": "application/pdf" } }],
        partSize: 10485760, partCount: 1, presignedUrls: ["https://minio.example/part/1"],
      }, { status: 201 }));
    vi.stubGlobal("fetch", fetchMock);

    const intent = await createUploadIntent(draft, "intent-key-001");

    expect(intent.partCount).toBe(1);
    expect(intent.parts[0]).toMatchObject({ partNumber: 1 });
    const call = fetchMock.mock.calls[1]!;
    expect(call[0]).toBe("/api/v1/materials/upload-intents");
    expect(call[1]).toMatchObject({
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Idempotency-Key": "intent-key-001",
        "X-XSRF-TOKEN": "csrf-token",
      },
    });
    expect(JSON.parse(String(call[1]?.body))).toEqual(draft);
  });

  it("completes the upload with the collected part ETags", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(Response.json(csrfBody))
      .mockResolvedValueOnce(Response.json({
        jobId: "job-1", status: "QUEUED",
        statusUrl: "/api/v1/jobs/job-1", eventsUrl: "/api/v1/jobs/job-1/events",
      }, { status: 202 }));
    vi.stubGlobal("fetch", fetchMock);

    const accepted = await completeUpload("intent-1", [{ partNumber: 1, etag: "\"etag-1\"" }]);

    expect(accepted.jobId).toBe("job-1");
    const call = fetchMock.mock.calls[1]!;
    expect(call[0]).toBe("/api/v1/materials/upload-intents/intent-1/complete");
    expect(JSON.parse(String(call[1]?.body))).toEqual({ parts: [{ partNumber: 1, etag: "\"etag-1\"" }] });
  });

  it("aborts an upload intent via DELETE with CSRF", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(Response.json(csrfBody))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    vi.stubGlobal("fetch", fetchMock);

    await abortUpload("intent-1");

    expect(fetchMock.mock.calls[1]?.[1]).toMatchObject({ method: "DELETE" });
  });

  it("reads a job status without credential material", async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(Response.json({
      id: "job-1", type: "INGEST", status: "RUNNING", stage: "scan", percent: 40,
      attempt: 0, errorCode: null, createdAt: "2026-08-01T08:00:00Z", updatedAt: "2026-08-01T08:00:00Z",
    }));
    vi.stubGlobal("fetch", fetchMock);

    const job = await getJob("job-1");

    expect(job).toMatchObject({ status: "RUNNING", percent: 40 });
    expect(fetchMock).toHaveBeenCalledWith("/api/v1/jobs/job-1",
      expect.objectContaining({ credentials: "same-origin" }));
  });
});

describe("knowledge document API client", () => {
  const csrfBody = { headerName: "X-XSRF-TOKEN", parameterName: "_csrf", token: "csrf-token" };
  const documentId = "sub-1";
  const revision = {
    id: "rev-1", subSceneId: documentId, revisionId: "rev-1", revisionNumber: 1,
    contentMd: "# 标题\n[SRC-001]", contentHash: "abc123", finalized: false,
    sourceRefs: [], etag: "\"etag-1\"",
  };

  it("gets the current knowledge document for a sub-scene", async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(Response.json(revision));
    vi.stubGlobal("fetch", fetchMock);

    const loaded = await getKnowledgeDocument(documentId);

    expect(loaded).toMatchObject({ revisionNumber: 1, etag: "\"etag-1\"" });
    expect(fetchMock).toHaveBeenCalledWith(`/api/v1/knowledge-documents/${documentId}`,
      expect.objectContaining({ credentials: "same-origin" }));
  });

  it("saves a revision with CSRF and the If-Match header", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(Response.json(csrfBody))
      .mockResolvedValueOnce(Response.json({ ...revision, revisionNumber: 2, revisionId: "rev-2" }));
    vi.stubGlobal("fetch", fetchMock);

    const saved = await saveKnowledgeDocument(documentId, {
      subSceneId: documentId, contentMd: "# 标题\n[SRC-001] 更新", finalize: false,
    }, "\"etag-1\"");

    expect(saved.revisionNumber).toBe(2);
    const call = fetchMock.mock.calls[1]!;
    expect(call[0]).toBe(`/api/v1/knowledge-documents/${documentId}`);
    expect(call[1]).toMatchObject({
      method: "PUT",
      headers: { "Content-Type": "application/json", "If-Match": "\"etag-1\"", "X-XSRF-TOKEN": "csrf-token" },
    });
    expect(JSON.parse(String(call[1]?.body))).toEqual({
      subSceneId: documentId, contentMd: "# 标题\n[SRC-001] 更新", finalize: false,
    });
  });

  it("supports the wildcard If-Match when creating the first revision", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(Response.json(csrfBody))
      .mockResolvedValueOnce(Response.json(revision, { status: 200 }));
    vi.stubGlobal("fetch", fetchMock);

    await saveKnowledgeDocument(documentId, { subSceneId: documentId, contentMd: "# 标题", finalize: false }, "*");

    expect(fetchMock.mock.calls[1]?.[1]).toMatchObject({ headers: { "If-Match": "*" } });
  });

  it("surfaces a 412 precondition-failed conflict", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(Response.json(csrfBody))
      .mockResolvedValueOnce(Response.json({
        detail: "knowledge document has a newer revision", code: "precondition-failed", traceId: "trace-412",
      }, { status: 412, headers: { "Content-Type": "application/problem+json" } }));
    vi.stubGlobal("fetch", fetchMock);

    const failure = await saveKnowledgeDocument(documentId, {
      subSceneId: documentId, contentMd: "# 标题", finalize: false,
    }, "\"stale-etag\"").catch((reason: unknown) => reason);

    expect(failure).toBeInstanceOf(ApiError);
    expect(failure).toMatchObject({ status: 412, code: "precondition-failed" });
  });

  it("lists immutable revision summaries", async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(Response.json([
      { id: "rev-2", revisionNumber: 2, contentHash: "abc", note: "补充锚点", createdBy: "u-1", finalized: true, createdAt: "2026-08-03T08:00:00Z" },
    ]));
    vi.stubGlobal("fetch", fetchMock);

    const revisions = await listDocumentRevisions(documentId);

    expect(revisions[0]).toMatchObject({ revisionNumber: 2, finalized: true, note: "补充锚点" });
    expect(fetchMock).toHaveBeenCalledWith(`/api/v1/knowledge-documents/${documentId}/revisions`,
      expect.objectContaining({ credentials: "same-origin" }));
  });

  it("starts a frozen extraction run with versioned model and skill inputs", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(Response.json(csrfBody))
      .mockResolvedValueOnce(Response.json({
        jobId: "job-extract-1", status: "QUEUED",
        statusUrl: "/api/v1/jobs/job-extract-1", eventsUrl: "/api/v1/jobs/job-extract-1/events",
      }, { status: 202 }));
    vi.stubGlobal("fetch", fetchMock);

    const accepted = await startExtraction("sub-1", "round-1", "model-v1", "skill-v1", "extract-key-1");

    expect(accepted.jobId).toBe("job-extract-1");
    const call = fetchMock.mock.calls[1]!;
    expect(call[0]).toBe("/api/v1/subscenes/sub-1/extraction-jobs");
    expect(call[1]).toMatchObject({
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Idempotency-Key": "extract-key-1",
        "X-XSRF-TOKEN": "csrf-token",
      },
    });
    expect(JSON.parse(String(call[1]?.body))).toEqual({
      roundId: "round-1", modelConfigVersionId: "model-v1", skillVersionId: "skill-v1",
    });
  });

  it("creates, lists, and adopts a structured alignment proposal with its base ETag", async () => {
    const proposal = {
      id: "proposal-1", documentId, baseRevisionId: "rev-1", baseEtag: "\"etag-1\"",
      action: "REGULATORY", status: "READY",
      structuredPatch: {
        operation: "replaceKnowledgeIr", replacement: {},
        diff: {
          addedRuleIds: ["rule-2"], removedRuleIds: [], changedRuleIds: [],
          addedFlowIds: [], removedFlowIds: [], changedFlowIds: [], sourceRefDelta: 0,
        },
      },
      reason: "补充监管要求", sourceRefs: [], regulatoryMaterialIds: ["material-1"],
      createdBy: "u-1", createdAt: "2026-08-04T08:00:00Z",
      adoptedRevisionId: null, adoptedBy: null, adoptedAt: null,
    };
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(Response.json(csrfBody))
      .mockResolvedValueOnce(Response.json({
        jobId: "job-align-1", status: "QUEUED",
        statusUrl: "/api/v1/jobs/job-align-1", eventsUrl: "/api/v1/jobs/job-align-1/events",
      }, { status: 202 }))
      .mockResolvedValueOnce(Response.json([proposal]))
      .mockResolvedValueOnce(Response.json(csrfBody))
      .mockResolvedValueOnce(Response.json({ ...revision, revisionId: "rev-2", revisionNumber: 2 }));
    vi.stubGlobal("fetch", fetchMock);

    await startAlignment(documentId, "rev-1", "REGULATORY", ["material-1"], "align-key-1");
    const proposals = await listAlignmentProposals(documentId);
    const adopted = await adoptAlignmentProposal("proposal-1", "\"etag-1\"");

    expect(proposals[0]?.structuredPatch.diff.addedRuleIds).toEqual(["rule-2"]);
    expect(adopted.revisionNumber).toBe(2);
    expect(fetchMock.mock.calls[1]?.[0]).toBe(`/api/v1/knowledge-documents/${documentId}/alignment-jobs`);
    expect(fetchMock.mock.calls[2]?.[0]).toBe(`/api/v1/knowledge-documents/${documentId}/alignment-proposals`);
    expect(fetchMock.mock.calls[4]).toEqual([
      "/api/v1/alignment-proposals/proposal-1/adopt",
      expect.objectContaining({ method: "POST", headers: expect.objectContaining({ "If-Match": "\"etag-1\"" }) }),
    ]);
  });
});

describe("asset and release API client", () => {
  const csrfBody = { headerName: "X-XSRF-TOKEN", parameterName: "_csrf", token: "csrf-token" };

  it("lists sub-scene assets with their real statuses", async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(Response.json([
      { id: "a-1", subSceneId: "sub-1", type: "RULE_CATALOG", version: 2, status: "READY",
        documentRevisionId: "rev-1", objectKey: "assets/x", checksum: "abc", failureReason: "",
        createdAt: "2026-08-03T08:00:00Z", updatedAt: "2026-08-03T08:00:00Z" },
      { id: "a-5", subSceneId: "sub-1", type: "EVALUATION_SET", version: 1, status: "BLOCKED",
        documentRevisionId: "rev-1", objectKey: "", checksum: "", failureReason: "no READY LABELED_HOLDOUT binding",
        createdAt: "2026-08-03T08:00:00Z", updatedAt: "2026-08-03T08:00:00Z" },
    ]));
    vi.stubGlobal("fetch", fetchMock);

    const assets = await listSubSceneAssets("sub-1");

    expect(assets).toHaveLength(2);
    expect(assets[0]).toMatchObject({ status: "READY", type: "RULE_CATALOG" });
    expect(assets[1].status).toBe("BLOCKED");
    expect(fetchMock).toHaveBeenCalledWith("/api/v1/subscenes/sub-1/assets",
      expect.objectContaining({ credentials: "same-origin" }));
  });

  it("starts asset generation with CSRF and an Idempotency-Key", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(Response.json(csrfBody))
      .mockResolvedValueOnce(Response.json({ jobId: "job-9", statusUrl: "/api/v1/jobs/job-9", eventsUrl: "/api/v1/jobs/job-9/events", status: "QUEUED" }, { status: 202 }));
    vi.stubGlobal("fetch", fetchMock);

    const accepted = await generateAssets("sub-1", "rev-1", ["RULE_CATALOG", "EVALUATION_SET"], "gen-key-001");

    expect(accepted.jobId).toBe("job-9");
    const call = fetchMock.mock.calls[1]!;
    expect(call[0]).toBe("/api/v1/subscenes/sub-1/asset-generation-jobs");
    expect(call[1]).toMatchObject({
      method: "POST",
      headers: { "Content-Type": "application/json", "Idempotency-Key": "gen-key-001", "X-XSRF-TOKEN": "csrf-token" },
    });
    expect(JSON.parse(String(call[1]?.body))).toEqual({ documentRevisionId: "rev-1", types: ["RULE_CATALOG", "EVALUATION_SET"] });
  });

  it("runs a release preflight with confirmed semantics", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(Response.json(csrfBody))
      .mockResolvedValueOnce(Response.json({
        ready: false, coverage: "PARTIAL", baseReleaseId: null,
        selected: ["sub-1"], carriedForward: [], missing: ["sub-2"],
        blockers: ["latest asset is not READY: sub-1:EVALUATION_SET (BLOCKED)"], warnings: [],
      }));
    vi.stubGlobal("fetch", fetchMock);

    const validation = await validateRelease("scene-1", {
      tag: "v1.0", selectedSubSceneIds: ["sub-1"], note: "首次发布", confirmed: true, expectedBaseReleaseId: null,
    });

    expect(validation.ready).toBe(false);
    expect(validation.blockers[0]).toContain("BLOCKED");
    expect(JSON.parse(String(fetchMock.mock.calls[1]?.[1]?.body))).toEqual({
      tag: "v1.0", selectedSubSceneIds: ["sub-1"], note: "首次发布", confirmed: true, expectedBaseReleaseId: null,
    });
  });

  it("publishes a release carrying the preflight baseReleaseId", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(Response.json(csrfBody))
      .mockResolvedValueOnce(Response.json({
        id: "rel-1", sceneId: "scene-1", tag: "v1.0", coverage: "PARTIAL", note: "首次发布",
        previousReleaseId: null, manifestSha256: "m-123", createdAt: "2026-08-03T08:00:00Z",
      }, { status: 201 }));
    vi.stubGlobal("fetch", fetchMock);

    const release = await createRelease("scene-1", {
      tag: "v1.0", selectedSubSceneIds: ["sub-1"], note: "首次发布", confirmed: true, expectedBaseReleaseId: "base-1",
    }, "release-key-001");

    expect(release.tag).toBe("v1.0");
    const call = fetchMock.mock.calls[1]!;
    expect(call[1]).toMatchObject({ method: "POST", headers: { "Idempotency-Key": "release-key-001" } });
    expect(JSON.parse(String(call[1]?.body))).toMatchObject({ expectedBaseReleaseId: "base-1", confirmed: true });
  });

  it("reads the immutable release manifest as text", async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(new Response("{\"schemaVersion\":\"1.0\"}", {
      status: 200, headers: { "Content-Type": "application/json" },
    }));
    vi.stubGlobal("fetch", fetchMock);

    const manifest = await getReleaseManifest("rel-1");

    expect(manifest).toContain("schemaVersion");
    expect(fetchMock).toHaveBeenCalledWith("/api/v1/releases/rel-1/manifest",
      expect.objectContaining({ credentials: "same-origin" }));
  });
});

describe("release baseline API client", () => {
  it("loads the latest published release as the baseline", async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(Response.json({
      id: "base-1", sceneId: "scene-1", tag: "v0.9", coverage: "PARTIAL", note: "上次发布",
      previousReleaseId: null, manifestSha256: "m-1", createdAt: "2026-08-02T08:00:00Z",
    }));
    vi.stubGlobal("fetch", fetchMock);

    const baseline = await getLatestRelease("scene-1");

    expect(baseline).toMatchObject({ id: "base-1", tag: "v0.9" });
    expect(fetchMock).toHaveBeenCalledWith("/api/v1/scenes/scene-1/releases/latest",
      expect.objectContaining({ credentials: "same-origin" }));
  });

  it("returns null when the scene has no published release yet", async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(Response.json({
      detail: "scene has no published release", code: "not-found", traceId: "t",
    }, { status: 404, headers: { "Content-Type": "application/problem+json" } }));
    vi.stubGlobal("fetch", fetchMock);

    const baseline = await getLatestRelease("scene-1");

    expect(baseline).toBeNull();
  });
});

describe("release evaluation API client", () => {
  const csrfBody = { headerName: "X-XSRF-TOKEN", parameterName: "_csrf", token: "csrf-token" };
  const run = {
    id: "eval-1", releaseId: "rel-1", subSceneId: "sub-1", roundId: "round-1",
    documentRevisionId: "rev-1", evaluationAssetId: "asset-eval", skillAssetId: "asset-skill",
    modelConfigVersionId: "model-v1", skillVersionId: "skill-v1", jobId: "job-eval-1",
    caseSetHash: "a".repeat(64), status: "SUCCEEDED", totalCases: 3, passedCases: 3,
    failedCases: 0, errorCases: 0, accuracy: 1, failureCode: "",
    createdAt: "2026-08-04T08:00:00Z", startedAt: "2026-08-04T08:00:01Z",
    completedAt: "2026-08-04T08:00:03Z", updatedAt: "2026-08-04T08:00:03Z",
  };

  it("starts an idempotent release-bound evaluation", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(Response.json(csrfBody))
      .mockResolvedValueOnce(Response.json({ evaluationRunId: "eval-1", jobId: "job-eval-1",
        status: "QUEUED", statusUrl: "/api/v1/jobs/job-eval-1",
        eventsUrl: "/api/v1/jobs/job-eval-1/events" }, { status: 202 }));
    vi.stubGlobal("fetch", fetchMock);

    const accepted = await startReleaseEvaluation("rel-1", "sub-1", "round-1", "evaluation-key-1");

    expect(accepted.evaluationRunId).toBe("eval-1");
    expect(fetchMock.mock.calls[1]?.[0]).toBe("/api/v1/releases/rel-1/subscenes/sub-1/evaluation-jobs");
    expect(fetchMock.mock.calls[1]?.[1]).toMatchObject({
      method: "POST", headers: { "Idempotency-Key": "evaluation-key-1" },
    });
    expect(JSON.parse(String(fetchMock.mock.calls[1]?.[1]?.body))).toEqual({ roundId: "round-1" });
  });

  it("loads runs and immutable per-case evidence without inventing metrics", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(Response.json([run]))
      .mockResolvedValueOnce(Response.json({ run, cases: [{ id: "case-1", ordinal: 0,
        caseKey: "late-120", input: "客户逾期120天", expected: "次级", materialId: "mat-1",
        chunkId: "chunk-1", sourceRefCode: "SRC-HOLDOUT-1", tags: ["风险"], prediction: "次级",
        outcome: "PASSED", errorCode: "", latencyMillis: 12 }] }));
    vi.stubGlobal("fetch", fetchMock);

    const runs = await listEvaluationRuns("rel-1", "sub-1");
    const detail = await getEvaluationRun("eval-1");

    expect(runs[0]).toMatchObject({ status: "SUCCEEDED", accuracy: 1, totalCases: 3 });
    expect(detail.cases[0]).toMatchObject({ sourceRefCode: "SRC-HOLDOUT-1", outcome: "PASSED" });
  });
});

describe("audit API client", () => {
  it("lists audit events with page and size parameters", async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(Response.json([{
      id: "aud-1", actorId: "00000000-0000-0000-0000-000000000001",
      action: "RELEASE_PUBLISHED", targetType: "RELEASE", targetId: "rel-1",
      detailsJson: "{\"tag\":\"v1.0\",\"manifestHash\":\"m123\"}", traceId: "tr-1",
      occurredAt: "2026-08-03T08:00:00Z",
    }]));
    vi.stubGlobal("fetch", fetchMock);

    const events = await listAuditEvents(0, 50);

    expect(events[0]).toMatchObject({ action: "RELEASE_PUBLISHED", traceId: "tr-1" });
    expect(fetchMock).toHaveBeenCalledWith("/api/v1/audit-events?page=0&size=50",
      expect.objectContaining({ credentials: "same-origin" }));
  });

  it("surfaces a 403 problem for non-admin viewers", async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(Response.json({
      detail: "Access Denied", code: "forbidden", traceId: "t-403",
    }, { status: 403, headers: { "Content-Type": "application/problem+json" } }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(listAuditEvents(0, 50))
      .rejects.toMatchObject({ status: 403, code: "forbidden" });
  });
});

describe("skill API client", () => {
  const csrfBody = { headerName: "X-XSRF-TOKEN", parameterName: "_csrf", token: "csrf-token" };
  const manifest = '{"schemaVersion":"1.0","executionMode":"RESOURCE_ONLY","resources":["rules.json"]}';
  const skill = {
    id: "sk-1", name: "规则萃取", kind: "TEMPLATE", description: "", sceneId: null,
    sourceSkillId: null, sourceSkillVersionId: null, version: 1, packageHash: "a".repeat(64),
    manifestJson: manifest, createdAt: "2026-08-03T08:00:00Z",
  };

  it("lists skills with optional kind and sceneId filters", async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(Response.json([skill]));
    vi.stubGlobal("fetch", fetchMock);

    const result = await listSkills("INSTANCE", "scene-1");

    expect(result[0]).toMatchObject({ kind: "TEMPLATE", version: 1 });
    expect(fetchMock).toHaveBeenCalledWith("/api/v1/skills?kind=INSTANCE&sceneId=scene-1",
      expect.objectContaining({ credentials: "same-origin" }));
  });

  it("creates a template with CSRF and Idempotency-Key", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(Response.json(csrfBody))
      .mockResolvedValueOnce(Response.json(skill, { status: 201 }));
    vi.stubGlobal("fetch", fetchMock);

    const created = await createSkill({ name: "规则萃取", manifest, packageHash: "a".repeat(64) }, "skill-key-001");

    expect(created.id).toBe("sk-1");
    const call = fetchMock.mock.calls[1]!;
    expect(call[0]).toBe("/api/v1/skills");
    expect(call[1]).toMatchObject({ method: "POST", headers: { "Idempotency-Key": "skill-key-001" } });
    expect(JSON.parse(String(call[1]?.body))).toEqual({ name: "规则萃取", manifest, packageHash: "a".repeat(64) });
  });

  it("forks a scene instance with the target sceneId", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(Response.json(csrfBody))
      .mockResolvedValueOnce(Response.json({ ...skill, kind: "INSTANCE", sceneId: "scene-1", sourceSkillId: "sk-1" }, { status: 201 }));
    vi.stubGlobal("fetch", fetchMock);

    const instance = await forkSkillInstance("sk-1", "scene-1", "fork-key-001");

    expect(instance.kind).toBe("INSTANCE");
    const call = fetchMock.mock.calls[1]!;
    expect(call[0]).toBe("/api/v1/skills/sk-1/instances");
    expect(JSON.parse(String(call[1]?.body))).toEqual({ sceneId: "scene-1" });
  });

  it("appends an immutable version to an instance", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(Response.json(csrfBody))
      .mockResolvedValueOnce(Response.json({ id: "ver-2", skillId: "sk-1", version: 2, manifestJson: manifest, packageHash: "b".repeat(64), createdBy: "u-1", createdAt: "2026-08-03T09:00:00Z" }, { status: 201 }));
    vi.stubGlobal("fetch", fetchMock);

    const version = await createSkillVersion("sk-1", manifest, "b".repeat(64), "ver-key-001");

    expect(version.version).toBe(2);
    expect(fetchMock.mock.calls[1]?.[0]).toBe("/api/v1/skills/sk-1/versions");
  });

  it("reads skill detail with version history", async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(Response.json({
      id: "sk-1", name: "规则萃取", kind: "INSTANCE", description: "", sceneId: "scene-1",
      sourceSkillId: "src-1", sourceSkillVersionId: "sv-1", createdAt: "2026-08-03T08:00:00Z",
      versions: [{ id: "ver-1", skillId: "sk-1", version: 1, manifestJson: manifest, packageHash: "a".repeat(64), createdBy: "u-1", createdAt: "2026-08-03T08:00:00Z" }],
    }));
    vi.stubGlobal("fetch", fetchMock);

    const detail = await getSkill("sk-1");

    expect(detail.kind).toBe("INSTANCE");
    expect(detail.versions).toHaveLength(1);
  });
});
