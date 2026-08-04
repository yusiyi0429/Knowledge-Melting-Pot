import { expect, test } from "@playwright/test";

test.beforeEach(async ({ page }) => {
  await page.route("**/api/v1/notifications?*", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ unreadCount: 0, items: [] }),
    });
  });
});

test("walks through the auditable extraction and partial-release prototype", async ({ page }) => {
  const consoleErrors: string[] = [];
  page.on("console", (message) => {
    if (message.type() === "error") consoleErrors.push(message.text());
  });

  await page.route("**/api/v1/scenes*", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        items: [{
          id: "corporate-loan-classification",
          name: "对公贷款五级分类",
          description: "从监管制度、行内细则和标注案例中，萃取可追溯的风险分类规则与研判流程。",
          createdAt: "2026-08-01T08:00:00Z",
          updatedAt: "2026-08-03T10:00:00Z",
        }],
        page: 0, size: 20, total: 1,
      }),
    });
  });
  await page.route("**/api/v1/scenes/corporate-loan-classification", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        id: "corporate-loan-classification",
        name: "对公贷款五级分类",
        description: "从监管制度、行内细则和标注案例中，萃取可追溯的风险分类规则与研判流程。",
        createdAt: "2026-08-01T08:00:00Z",
        updatedAt: "2026-08-03T10:00:00Z",
      }),
    });
  });
  await page.route("**/api/v1/scenes/corporate-loan-classification/subscenes", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify([{
        id: "sub-1",
        sceneId: "corporate-loan-classification",
        name: "逾期天数与分类下迁",
        description: "按逾期天数、重组状态和例外条件判断最低分类级别。",
        createdAt: "2026-08-01T08:00:00Z",
        updatedAt: "2026-08-01T08:00:00Z",
      }]),
    });
  });
  await page.route("**/api/v1/scenes/corporate-loan-classification/rounds", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify([{
        id: "round-1",
        subSceneId: "sub-1",
        roundNumber: 1,
        status: "DRAFT",
        createdAt: "2026-08-01T08:00:00Z",
        updatedAt: "2026-08-01T08:00:00Z",
      }]),
    });
  });
  await page.route("**/api/v1/materials?*", async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify([]) });
  });
  await page.route("**/api/v1/model-connections", async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify([]) });
  });
  await page.route("**/api/v1/skills", async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify([]) });
  });
  await page.route("**/api/v1/knowledge-documents/sub-1/alignment-proposals", async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify([]) });
  });
  await page.route("**/api/v1/knowledge-documents/*", async (route) => {
    if (route.request().method() === "GET") {
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({
          id: "sub-1", subSceneId: "sub-1", revisionId: "rev-1", revisionNumber: 1,
          contentMd: "# 逾期分类规则\n\n[SRC-001] 说明", contentHash: "hash", finalized: false,
          sourceRefs: [], etag: '"etag-1"',
        }),
      });
      return;
    }
    await route.continue();
  });

  await page.route("**/api/v1/auth/csrf", async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ headerName: "X-XSRF-TOKEN", parameterName: "_csrf", token: "e2e-csrf" }) });
  });
  await page.route("**/api/v1/auth/me", async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({
      id: "u-1", username: "operator", displayName: "运营", enabled: true,
      roles: ["OPERATOR"], mustChangePassword: false,
    }) });
  });

  await page.goto("/login");
  await expect(page).toHaveTitle("知识萃取智能体工作台");
  await page.getByRole("button", { name: "进入离线演示" }).click();

  await expect(page.getByRole("heading", { name: "知识正在形成可追溯的资产" })).toBeVisible();
  await page.getByRole("button", { name: "打开对公贷款五级分类" }).click();
  await expect(page).toHaveURL(/\/scenes\/corporate-loan-classification$/);

  // The real scene, its subscenes, and its rounds render from the API.
  await expect(page.getByRole("heading", { name: "对公贷款五级分类" })).toBeVisible();
  await expect(page.getByText("逾期天数与分类下迁").first()).toBeVisible();
  await expect(page.getByRole("button", { name: /v1 · 草稿/ })).toBeVisible();
  await expect(page.getByText("还没有素材")).toBeVisible();

  // Step 2 is a real document editor seeded from the API — no simulated SSE.
  await page.getByRole("button", { name: "进入知识萃取" }).click();
  await expect(page.getByRole("textbox", { name: "知识文档 Markdown" })).toBeVisible();
  await expect(page.getByText("v1", { exact: true }).first()).toBeVisible();
  await expect(page.getByText("演示萃取 Job")).toHaveCount(0);
  expect(consoleErrors).toEqual([]);
});

test("serves application deep links and governance routes", async ({ page }) => {
  const routes = [
    ["/agents", "七种角色，一条可追溯配置链"],
    ["/skills", "模板是起点，版本才是交付物"],
    ["/models", "模型连接与生成参数分开版本化"],
    ["/users", "用户与可组合角色"],
    ["/audit", "从操作到内容版本的证据链"],
  ] as const;

  for (const [path, heading] of routes) {
    await page.goto(path);
    await expect(page.getByRole("heading", { name: heading })).toBeVisible();
  }
});

test("explores staged evidence, searches globally, and reads task notifications", async ({ page }) => {
  const explorationId = "3f7a1c2e-0000-4000-8000-000000000051";
  const candidateId = "3f7a1c2e-0000-4000-8000-000000000052";
  const materialId = "3f7a1c2e-0000-4000-8000-000000000053";
  const etag = `"${"a".repeat(64)}"`;
  let notificationRead = false;
  let acceptanceHeaders: { ifMatch: string | null; csrf: string | null } | null = null;

  await page.unroute("**/api/v1/notifications?*");
  await page.route("**/api/v1/auth/csrf", async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ headerName: "X-XSRF-TOKEN", parameterName: "_csrf", token: "exploration-csrf" }) });
  });
  await page.route("**/api/v1/auth/me", async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ id: "operator-1", username: "operator", displayName: "运营", enabled: true, roles: ["OPERATOR"], mustChangePassword: false }) });
  });
  await page.route("**/api/v1/notifications?*", async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({
      unreadCount: notificationRead ? 0 : 1,
      items: [{ id: "notification-1", type: "JOB_SUCCEEDED", title: "场景探索已完成", message: "候选场景已就绪。", resourceType: "JOB", resourceId: "job-1", createdAt: "2026-08-04T08:00:00Z", readAt: notificationRead ? "2026-08-04T08:01:00Z" : null, read: notificationRead }],
    }) });
  });
  await page.route("**/api/v1/notifications/notification-1/read", async (route) => {
    notificationRead = true;
    await route.fulfill({ status: 204 });
  });
  await page.route("**/api/v1/search?*", async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify([{ type: "RULE", sceneId: "scene-existing", subSceneId: "sub-existing", resourceId: "rule-1", title: "逾期贷款分类规则", excerpt: "逾期超过 90 天时进入次级类。" }]) });
  });

  const session = { id: explorationId, title: "贷款风险分类探索", status: "READY", exploreJobId: "job-1", version: 2, createdAt: "2026-08-04T07:00:00Z", updatedAt: "2026-08-04T08:00:00Z" };
  await page.route("**/api/v1/explorations", async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify([session]) });
  });
  await page.route(`**/api/v1/explorations/${explorationId}`, async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({
      session,
      etag,
      materials: [{ id: materialId, fileName: "贷款分类制度.txt", format: "TXT", sizeBytes: 2048, status: "READY", createdAt: "2026-08-04T07:00:00Z", updatedAt: "2026-08-04T07:05:00Z" }],
      candidates: [{ id: candidateId, rank: 1, sceneName: "贷款风险分类", sceneDescription: "分类制度知识场景", subSceneName: "逾期分类下迁", subSceneDescription: "基于逾期天数判断分类", rationale: "素材包含完整阈值、例外和来源。", valueLevel: "HIGH", estimatedRuleCount: 8, estimatedFlowCount: 2, tags: ["信贷", "分类"], materialIds: [materialId] }],
      acceptance: null,
    }) });
  });
  await page.route(`**/api/v1/explorations/${explorationId}/candidates/${candidateId}/accept`, async (route) => {
    acceptanceHeaders = { ifMatch: route.request().headers()["if-match"] ?? null, csrf: route.request().headers()["x-xsrf-token"] ?? null };
    await route.fulfill({ status: 201, contentType: "application/json", body: JSON.stringify({ sceneId: "scene-accepted", subSceneId: "sub-accepted", roundId: "round-accepted", reusedMaterialIds: [materialId] }) });
  });

  await page.goto("/explore");
  await expect(page.getByRole("heading", { name: "从证据边界中发现候选场景" })).toBeVisible();
  await expect(page.getByText("贷款分类制度.txt")).toBeVisible();
  await expect(page.getByRole("heading", { name: "贷款风险分类", exact: true })).toBeVisible();

  await page.getByRole("button", { name: "通知，1 条未读" }).click();
  await page.getByRole("button", { name: /场景探索已完成/ }).click();
  await expect.poll(() => notificationRead).toBe(true);

  await page.getByRole("searchbox", { name: "搜索" }).fill("逾期贷款");
  await expect(page.getByText("逾期贷款分类规则")).toBeVisible();
  await page.keyboard.press("Escape");

  await page.getByRole("button", { name: "接受并进入萃取" }).click();
  await expect(page).toHaveURL(/\/scenes\/scene-accepted$/);
  expect(acceptanceHeaders).toEqual({ ifMatch: etag, csrf: "exploration-csrf" });
});

test("governs agent mounts with ETag writes and transactional import", async ({ page }) => {
  const sceneId = "3f7a1c2e-0000-4000-8000-000000000041";
  const subSceneId = "3f7a1c2e-0000-4000-8000-000000000042";
  const modelVersionId = "3f7a1c2e-0000-4000-8000-000000000043";
  const skillVersionId = "3f7a1c2e-0000-4000-8000-000000000044";
  const initialEtag = "a".repeat(64);
  const nextEtag = "b".repeat(64);
  const manifestHash = "c".repeat(64);
  let mountWrite: { ifMatch: string | null; csrf: string | null; body: Record<string, unknown> } | null = null;
  let importApplyIfMatch: string | null = null;
  let scope = {
    scope: "SCENE", scopeId: sceneId, sceneId, etag: initialEtag, mounts: [],
  };

  await page.route("**/api/v1/auth/csrf", async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ headerName: "X-XSRF-TOKEN", parameterName: "_csrf", token: "agent-csrf" }) });
  });
  await page.route("**/api/v1/auth/me", async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ id: "admin-1", username: "admin", displayName: "管理员", enabled: true, roles: ["ADMIN"], mustChangePassword: false }) });
  });
  await page.route("**/api/v1/agent-roles", async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify([{ role: "KNOWLEDGE_EXTRACTOR", displayName: "知识萃取智能体", stage: "环节二", description: "从可信素材生成带来源的 KnowledgeIR" }]) });
  });
  await page.route("**/api/v1/agent-configuration-catalog", async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({
      models: [{ versionId: modelVersionId, connectionId: "connection-1", connectionName: "受控模型", provider: "DASHSCOPE", version: 2, modelId: "qwen-plus", temperature: 0.2, maxOutputTokens: 4096 }],
      skills: [{ versionId: skillVersionId, skillId: "skill-1", name: "结构化萃取", kind: "TEMPLATE", sceneId: null, version: 3, packageHash: "d".repeat(64) }],
    }) });
  });
  await page.route("**/api/v1/scenes?page=0&size=100", async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ items: [{ id: sceneId, name: "贷款分类", description: "", createdAt: "2026-08-04T00:00:00Z", updatedAt: "2026-08-04T00:00:00Z" }], page: 0, size: 100, total: 1 }) });
  });
  await page.route(`**/api/v1/scenes/${sceneId}/subscenes`, async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify([{ id: subSceneId, sceneId, name: "逾期分类", description: "", createdAt: "2026-08-04T00:00:00Z", updatedAt: "2026-08-04T00:00:00Z" }]) });
  });
  await page.route("**/api/v1/agent-mounts?*", async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(scope) });
  });
  await page.route("**/api/v1/agent-mounts/effective?*", async (route) => {
    const mount = scope.mounts[0] as Record<string, unknown> | undefined;
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify([{
      role: "KNOWLEDGE_EXTRACTOR", displayName: "知识萃取智能体", stage: "环节二",
      enabled: Boolean(mount), configured: Boolean(mount),
      modelConfigVersionId: mount ? modelVersionId : null, skillVersionId: mount ? skillVersionId : null,
      optionsJson: mount ? '{"strategy":"balanced"}' : "{}", effectiveHash: mount ? "e".repeat(64) : "f".repeat(64),
      effectiveMountVersionId: mount?.id ?? null, enabledSource: mount ? "SCENE" : null,
      modelSource: mount ? "SCENE" : null, skillSource: mount ? "SCENE" : null,
      optionsSource: mount ? "SCENE" : "TEMPLATE", lineage: mount ? [mount] : [],
    }]) });
  });
  await page.route("**/api/v1/agent-mounts/versions", async (route) => {
    mountWrite = {
      ifMatch: route.request().headers()["if-match"] ?? null,
      csrf: route.request().headers()["x-xsrf-token"] ?? null,
      body: route.request().postDataJSON() as Record<string, unknown>,
    };
    scope = { ...scope, etag: nextEtag, mounts: [{
      id: "mount-1", role: "KNOWLEDGE_EXTRACTOR", scope: "SCENE", scopeId: sceneId, version: 1,
      templateVersionId: "template-1", enabled: true, modelConfigVersionId: modelVersionId,
      skillVersionId, optionsJson: '{"strategy":"balanced"}', configHash: "e".repeat(64),
      createdBy: "admin-1", createdAt: "2026-08-04T00:00:00Z",
    }] };
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(scope) });
  });
  await page.route("**/api/v1/configuration-imports/previews", async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({
      id: "import-1", schemaVersion: "1.0", scope: "SCENE", scopeId: sceneId, sceneId,
      baseEtag: nextEtag, manifestJson: "{}", manifestHash,
      diffJson: '[{"role":"KNOWLEDGE_EXTRACTOR","changedFields":["options"]}]',
      createdBy: "admin-1", createdAt: "2026-08-04T00:00:00Z", appliedBy: null, appliedAt: null, applied: false,
    }) });
  });
  await page.route("**/api/v1/configuration-imports/import-1/apply", async (route) => {
    importApplyIfMatch = route.request().headers()["if-match"] ?? null;
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(scope) });
  });

  await page.goto("/agents");
  await expect(page.getByRole("heading", { name: "知识萃取智能体" })).toBeVisible();
  await page.getByLabel("本层启停").selectOption("true");
  await page.getByLabel("本层模型版本").selectOption(modelVersionId);
  await page.getByLabel("本层 Skill 版本").selectOption(skillVersionId);
  await page.getByLabel("本层运行参数（JSON 对象，留空继承）").fill('{"strategy":"balanced"}');
  await page.getByRole("button", { name: "追加配置版本" }).click();
  await expect(page.getByRole("status")).toContainText("已追加新版本");
  expect(mountWrite).toMatchObject({ ifMatch: initialEtag, csrf: "agent-csrf", body: { scope: "SCENE", scopeId: sceneId, role: "KNOWLEDGE_EXTRACTOR", enabled: true, modelConfigVersionId: modelVersionId, skillVersionId } });

  await page.getByRole("button", { name: "导入配置" }).click();
  await page.locator(".agent-import-panel textarea").fill('[{"role":"KNOWLEDGE_EXTRACTOR","options":{"strategy":"strict"}}]');
  await page.getByRole("button", { name: "生成 Diff 预览" }).click();
  await expect(page.getByText(/校验通过/)).toBeVisible();
  await page.getByRole("button", { name: "确认事务应用" }).click();
  await expect(page.getByRole("status")).toContainText("配置导入已作为一组不可变版本应用");
  expect(importApplyIfMatch).toBe(manifestHash);
});

test("online login sends CSRF metadata and routes first-login users to password change", async ({ page }) => {
  await page.route("**/api/v1/auth/csrf", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ headerName: "X-XSRF-TOKEN", parameterName: "_csrf", token: "e2e-csrf" }),
    });
  });
  await page.route("**/api/v1/auth/login", async (route) => {
    expect(route.request().headers()["x-xsrf-token"]).toBe("e2e-csrf");
    expect(route.request().postDataJSON()).toEqual({ username: "admin", password: "temporary-password" });
    await route.fulfill({ status: 204 });
  });
  await page.route("**/api/v1/auth/me", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        id: "76731d36-39fc-4f00-83ac-c9eb1d183785",
        username: "admin",
        displayName: "Administrator",
        enabled: true,
        roles: ["ADMIN"],
        mustChangePassword: true,
      }),
    });
  });

  await page.goto("/login");
  await page.getByRole("textbox", { name: "用户名" }).fill("admin");
  await page.getByRole("textbox", { name: /密码/ }).fill("temporary-password");
  await page.getByRole("button", { name: "进入工作台" }).click();

  await expect(page).toHaveURL(/\/change-password$/);
  await expect(page.getByRole("heading", { name: "修改初始密码" })).toBeVisible();
});

const modelConnectionFixture = {
  id: "4f06e31c-4d52-4a0e-bb0f-000000000001",
  name: "DashScope 主网关",
  provider: "DASHSCOPE",
  baseUrl: "https://dashscope.aliyuncs.com/compatible-mode/v1",
  enabled: true,
  credentialConfigured: true,
  validationStatus: "UNTESTED",
  lastValidatedAt: null,
  createdAt: "2026-08-01T08:00:00Z",
  updatedAt: "2026-08-03T08:00:00Z",
};

test("manages model connections against the real API contract", async ({ page }) => {
  const csrf = { headerName: "X-XSRF-TOKEN", parameterName: "_csrf", token: "e2e-csrf" };
  let connections: Record<string, unknown>[] = [modelConnectionFixture];
  let versions: Record<string, unknown>[] = [{
    id: "version-1", modelConnectionId: modelConnectionFixture.id, version: 1,
    modelId: "qwen-max", temperature: 0.4, maxOutputTokens: 4096,
    createdAt: "2026-08-01T09:00:00Z",
  }];
  const mutations: unknown[] = [];
  const versionBodies: unknown[] = [];
  const deletes: string[] = [];

  await page.route("**/api/v1/auth/csrf", async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(csrf) });
  });
  await page.route("**/api/v1/model-connections", async (route) => {
    const request = route.request();
    if (request.method() === "GET") {
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(connections) });
      return;
    }
    const body = request.postDataJSON();
    expect(request.headers()["x-xsrf-token"]).toBe(csrf.token);
    expect(body.credential).toBe("e2e-write-only-credential");
    const created = { ...modelConnectionFixture, id: "created-connection", name: body.name, provider: body.provider, baseUrl: body.baseUrl, credentialConfigured: true, validationStatus: "UNTESTED", lastValidatedAt: null };
    mutations.push(body);
    connections = [created, ...connections];
    await route.fulfill({ status: 201, contentType: "application/json", body: JSON.stringify(created) });
  });
  await page.route("**/api/v1/model-connections/*", async (route) => {
    const request = route.request();
    const id = new URL(request.url()).pathname.split("/").pop();
    if (request.method() === "PUT") {
      const body = request.postDataJSON();
      expect(body).not.toHaveProperty("credential");
      expect(body.clearCredential).toBe(false);
      expect(request.headers()["x-xsrf-token"]).toBe(csrf.token);
      const updated = { ...modelConnectionFixture, name: body.name, enabled: body.enabled };
      connections = connections.map((item) => item.id === id ? updated : item);
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(updated) });
      return;
    }
    if (request.method() === "DELETE") {
      deletes.push(request.url());
      connections = connections.filter((item) => item.id !== id);
      await route.fulfill({ status: 204 });
    }
  });
  await page.route("**/api/v1/model-connections/*/connection-tests", async (route) => {
    expect(route.request().headers()["x-xsrf-token"]).toBe(csrf.token);
    connections = connections.map((item) => item.id === modelConnectionFixture.id
      ? { ...item, validationStatus: "CONFIGURATION_VALIDATED", lastValidatedAt: "2026-08-03T09:00:00Z" }
      : item);
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        status: "CONFIGURATION_VALIDATED",
        networkAttempted: false,
        connectivityVerified: false,
        credentialConfigured: true,
        messageCode: "model.configuration.validated-offline",
        testedAt: "2026-08-03T09:00:00Z",
      }),
    });
  });
  await page.route("**/api/v1/model-connections/*/config-versions", async (route) => {
    const request = route.request();
    if (request.method() === "GET") {
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(versions) });
      return;
    }
    const body = request.postDataJSON();
    versionBodies.push(body);
    const created = { id: "version-2", modelConnectionId: modelConnectionFixture.id, version: 2, ...body, createdAt: "2026-08-04T09:00:00Z" };
    versions = [created, ...versions];
    await route.fulfill({ status: 201, contentType: "application/json", body: JSON.stringify(created) });
  });

  await page.goto("/models");

  // Real list rendering: no demo notice, connection rows from the API.
  await expect(page.getByRole("heading", { name: "模型连接与生成参数分开版本化" })).toBeVisible();
  await expect(page.getByText("DashScope 主网关")).toBeVisible();
  await expect(page.getByText("https://dashscope.aliyuncs.com/compatible-mode/v1")).toBeVisible();
  await expect(page.getByText("离线演示", { exact: true })).toHaveCount(0);

  // Create: dialog is accessible, POST carries CSRF and the write-only credential, list refreshes.
  await page.getByRole("button", { name: "新增模型连接" }).click();
  await expect(page.getByRole("dialog", { name: "新增模型连接" })).toBeVisible();
  await page.getByLabel("名称").fill("企业模型网关");
  await page.getByLabel("Provider").selectOption("OPENAI_COMPATIBLE");
  await page.getByLabel("Base URL").fill("https://llm-gateway.corp.example/v1");
  await page.getByLabel("凭据（只写）").fill("e2e-write-only-credential");
  await page.getByRole("button", { name: "创建连接" }).click();
  await expect(page.getByRole("status").first()).toContainText("连接已创建");
  await expect(page.getByText("企业模型网关")).toBeVisible();
  expect(mutations).toHaveLength(1);
  expect(mutations[0]).toMatchObject({
    name: "企业模型网关", provider: "OPENAI_COMPATIBLE",
    baseUrl: "https://llm-gateway.corp.example/v1", enabled: true,
  });
  await expect(page.getByText("e2e-write-only-credential")).toHaveCount(0);

  // Edit: fields prefilled from the API, credential never echoed, PUT omits it.
  const row = page.locator("article").filter({ hasText: "DashScope 主网关" });
  await row.getByRole("button", { name: "编辑" }).click();
  await expect(page.getByRole("dialog", { name: "编辑模型连接" })).toBeVisible();
  await expect(page.getByLabel("名称")).toHaveValue("DashScope 主网关");
  await expect(page.getByLabel("凭据（只写）")).toHaveValue("");
  await page.getByLabel("名称").fill("DashScope 主网关（改名）");
  await page.getByRole("button", { name: "保存修改" }).click();
  await expect(page.getByText("连接已更新，列表已刷新。")).toBeVisible();
  await expect(page.getByText("DashScope 主网关（改名）")).toBeVisible();

  // Connection test renders the offline semantics, not fake provider connectivity.
  const renamedRow = page.locator("article").filter({ hasText: "DashScope 主网关（改名）" });
  await renamedRow.getByRole("button", { name: "测试连接" }).click();
  await expect(renamedRow).toContainText("配置安全校验通过");
  await expect(renamedRow).toContainText("未发起网络请求");
  await expect(renamedRow).toContainText("未验证供应商连通（不代表真实可达）");
  await expect(renamedRow).toContainText("model.configuration.validated-offline");
  await expect(renamedRow).toContainText("配置已校验");
  await expect(page.getByText("已连接")).toHaveCount(0);
  await expect(page.getByText("演示测试通过")).toHaveCount(0);

  // Immutable config versions: view the existing one and append a new one.
  await renamedRow.getByRole("button", { name: "配置版本" }).click();
  await expect(page.getByText("v1", { exact: true })).toBeVisible();
  await expect(page.getByText("qwen-max", { exact: true }).first()).toBeVisible();
  await page.getByLabel("Model ID").fill("qwen-max");
  await page.getByLabel("Temperature").fill("0.2");
  await page.getByLabel("Max Output Tokens").fill("8192");
  await page.getByRole("button", { name: "追加版本" }).click();
  await expect(page.getByText("已追加新的不可变配置版本。")).toBeVisible();
  await expect(page.getByText("v2", { exact: true })).toBeVisible();
  expect(versionBodies).toEqual([{ modelId: "qwen-max", temperature: 0.2, maxOutputTokens: 8192 }]);

  // Delete requires an explicit second confirmation before the soft delete fires.
  const createdRow = page.locator("article").filter({ hasText: "企业模型网关" });
  await createdRow.getByRole("button", { name: "删除" }).click();
  expect(deletes).toHaveLength(0);
  await createdRow.getByRole("button", { name: "确认删除" }).click();
  await expect(page.getByText("连接已删除（软删除，历史配置版本保留可追溯）。")).toBeVisible();
  expect(deletes).toHaveLength(1);
  await expect(page.getByText("企业模型网关")).toHaveCount(0);
  await expect(page.getByText("DashScope 主网关（改名）")).toBeVisible();
});

test("models page renders permission problems and field validation errors", async ({ page }) => {
  const csrf = { headerName: "X-XSRF-TOKEN", parameterName: "_csrf", token: "e2e-csrf" };
  let listFails = true;
  await page.route("**/api/v1/auth/csrf", async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(csrf) });
  });
  await page.route("**/api/v1/model-connections", async (route) => {
    const request = route.request();
    if (request.method() === "GET") {
      if (listFails) {
        await route.fulfill({
          status: 403,
          contentType: "application/problem+json",
          body: JSON.stringify({
            type: "https://knowledge-melting-pot.local/problems/forbidden",
            title: "Forbidden", status: 403,
            detail: "ADMIN role required", code: "forbidden", traceId: "trace-403",
          }),
        });
        return;
      }
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify([modelConnectionFixture]) });
      return;
    }
    await route.fulfill({
      status: 400,
      contentType: "application/problem+json",
      body: JSON.stringify({
        type: "https://knowledge-melting-pot.local/problems/validation",
        title: "Validation failed", status: 400,
        detail: "One or more fields are invalid", code: "validation", traceId: "trace-400",
        errors: [{ field: "baseUrl", message: "must match the host whitelist" }],
      }),
    });
  });

  await page.goto("/models");
  await expect(page.getByRole("alert")).toContainText("ADMIN role required");
  await expect(page.getByRole("button", { name: "重试" })).toBeVisible();

  listFails = false;
  await page.getByRole("button", { name: "重试" }).click();
  await expect(page.getByText("DashScope 主网关")).toBeVisible();

  await page.getByRole("button", { name: "新增模型连接" }).click();
  await page.getByLabel("名称").fill("被拒绝的网关");
  await page.getByLabel("Base URL").fill("https://denied.example.com/v1");
  await page.getByRole("button", { name: "创建连接" }).click();
  await expect(page.getByRole("dialog")).toContainText("One or more fields are invalid");
  await expect(page.getByText("must match the host whitelist")).toBeVisible();
  await page.getByRole("button", { name: "关闭" }).click();
  await expect(page.getByRole("dialog")).toHaveCount(0);
});

const userFixture = {
  id: "e1b7a1f2-1111-4000-8000-000000000001",
  username: "admin",
  displayName: "曹征",
  roles: ["OPERATOR", "PUBLISHER", "ADMIN"],
  enabled: true,
  mustChangePassword: false,
};

const operatorFixture = {
  id: "e1b7a1f2-2222-4000-8000-000000000002",
  username: "linan",
  displayName: "李楠",
  roles: ["OPERATOR"],
  enabled: true,
  mustChangePassword: true,
};

test("manages users against the real API contract", async ({ page }) => {
  const csrf = { headerName: "X-XSRF-TOKEN", parameterName: "_csrf", token: "e2e-csrf" };
  let users: Record<string, unknown>[] = [userFixture, operatorFixture];
  const createBodies: unknown[] = [];
  const patchBodies: unknown[] = [];
  const resetBodies: unknown[] = [];

  await page.route("**/api/v1/auth/csrf", async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(csrf) });
  });
  await page.route("**/api/v1/auth/me", async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(userFixture) });
  });
  await page.route("**/api/v1/users", async (route) => {
    const request = route.request();
    if (request.method() === "GET") {
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(users) });
      return;
    }
    const body = request.postDataJSON();
    expect(request.headers()["x-xsrf-token"]).toBe(csrf.token);
    expect(body.initialPassword).toBe("e2e-initial-password");
    const created = { id: "e1b7a1f2-3333-4000-8000-000000000003", ...body, enabled: true, mustChangePassword: true };
    createBodies.push(body);
    users = [created, ...users];
    await route.fulfill({ status: 201, contentType: "application/json", body: JSON.stringify(created) });
  });
  await page.route("**/api/v1/users/*/password-reset", async (route) => {
    expect(route.request().headers()["x-xsrf-token"]).toBe(csrf.token);
    resetBodies.push(route.request().postDataJSON());
    await route.fulfill({ status: 204 });
  });
  await page.route("**/api/v1/users/*", async (route) => {
    const request = route.request();
    if (request.method() !== "PATCH") return;
    const body = request.postDataJSON();
    const id = new URL(request.url()).pathname.split("/").pop();
    patchBodies.push(body);
    const updated = { ...(users.find((item) => item.id === id) ?? operatorFixture), ...body };
    users = users.map((item) => item.id === id ? updated : item);
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(updated) });
  });

  await page.goto("/users");

  // Real list rendering: no demo notice, accounts from the API.
  await expect(page.getByRole("heading", { name: "用户与可组合角色" })).toBeVisible();
  await expect(page.getByText("李楠")).toBeVisible();
  await expect(page.getByText("@linan")).toBeVisible();
  await expect(page.getByText("首次登录须改密")).toBeVisible();
  await expect(page.getByText("离线演示", { exact: true })).toHaveCount(0);

  // Self-protection: the current account cannot reset its own password here.
  const adminRow = page.locator("article").filter({ hasText: "@admin" });
  await expect(adminRow.getByRole("button", { name: "重置密码" })).toBeDisabled();

  // Create: POST carries CSRF and the write-only initial password; the page never echoes it.
  await page.getByRole("button", { name: "新增用户" }).click();
  await expect(page.getByRole("dialog", { name: "新增用户" })).toBeVisible();
  await page.getByLabel("用户名").fill("zhangsan");
  await page.getByLabel("显示名").fill("张三");
  await page.getByLabel("初始密码（只写）").fill("e2e-initial-password");
  await page.getByRole("checkbox", { name: /OPERATOR/ }).check();
  await page.getByRole("button", { name: "创建用户" }).click();
  await expect(page.getByText("用户已创建；首次登录必须修改初始密码。")).toBeVisible();
  await expect(page.getByText("张三")).toBeVisible();
  expect(createBodies).toHaveLength(1);
  expect(createBodies[0]).toMatchObject({ username: "zhangsan", displayName: "张三", roles: ["OPERATOR"] });
  await expect(page.getByText("e2e-initial-password")).toHaveCount(0);

  // Edit with a role change: the session-revocation warning shows and PATCH sends only changed fields.
  const operatorRow = page.locator("article").filter({ hasText: "@linan" });
  await operatorRow.getByRole("button", { name: "编辑" }).click();
  await expect(page.getByRole("dialog", { name: "编辑用户" })).toBeVisible();
  await expect(page.getByRole("checkbox", { name: /PUBLISHER/ })).not.toBeChecked();
  await page.getByRole("checkbox", { name: /PUBLISHER/ }).check();
  await expect(page.getByText("角色或启停变更保存后，该用户的所有现有会话将立即失效，需要重新登录。")).toBeVisible();
  await page.getByLabel("显示名").fill("李楠（发布）");
  await page.getByRole("button", { name: "保存修改" }).click();
  await expect(page.getByText("用户已更新；其现有会话已全部失效，需要重新登录。")).toBeVisible();
  expect(patchBodies).toHaveLength(1);
  expect(patchBodies[0]).toEqual({ displayName: "李楠（发布）", roles: ["OPERATOR", "PUBLISHER"] });
  expect(patchBodies[0]).not.toHaveProperty("enabled");

  // Display-name-only edit: no session-revocation warning, PATCH omits roles and enabled.
  await operatorRow.getByRole("button", { name: "编辑" }).click();
  await page.getByLabel("显示名").fill("李楠");
  await expect(page.getByText("所有现有会话将立即失效")).toHaveCount(0);
  await page.getByRole("button", { name: "保存修改" }).click();
  await expect(page.getByText("用户已更新。")).toBeVisible();
  expect(patchBodies).toHaveLength(2);
  expect(patchBodies[1]).toEqual({ displayName: "李楠" });

  // An unchanged dialog cannot be saved; an enabled-only change sends just that field.
  await operatorRow.getByRole("button", { name: "编辑" }).click();
  await expect(page.getByRole("button", { name: "保存修改" })).toBeDisabled();
  await page.getByRole("checkbox", { name: "启用该账号" }).uncheck();
  await expect(page.getByText("角色或启停变更保存后，该用户的所有现有会话将立即失效，需要重新登录。")).toBeVisible();
  await page.getByRole("button", { name: "保存修改" }).click();
  await expect(page.getByText("用户已更新；其现有会话已全部失效，需要重新登录。")).toBeVisible();
  expect(patchBodies).toHaveLength(3);
  expect(patchBodies[2]).toEqual({ enabled: false });
  expect(patchBodies[2]).not.toHaveProperty("displayName");

  // Self-edit: the frontend disables disabling oneself and removing own ADMIN role.
  await adminRow.getByRole("button", { name: "编辑" }).click();
  await expect(page.getByRole("checkbox", { name: /ADMIN/ })).toBeDisabled();
  await expect(page.getByRole("checkbox", { name: "启用该账号" })).toBeDisabled();
  await page.getByRole("button", { name: "关闭", exact: true }).click();

  // Reset password: POST carries the write-only newPassword; feedback mentions revocation.
  await operatorRow.getByRole("button", { name: "重置密码" }).click();
  await expect(page.getByRole("dialog", { name: "重置密码" })).toBeVisible();
  await page.getByLabel("新密码（只写）").fill("e2e-replacement-password");
  await page.getByRole("button", { name: "确认重置密码" }).click();
  await expect(page.getByText("密码已重置；该用户下次登录必须修改密码，其现有会话已全部失效。")).toBeVisible();
  expect(resetBodies).toHaveLength(1);
  expect(resetBodies[0]).toEqual({ newPassword: "e2e-replacement-password" });
  await expect(page.getByText("e2e-replacement-password")).toHaveCount(0);
});

test("users page renders permission problems and field validation errors", async ({ page }) => {
  const csrf = { headerName: "X-XSRF-TOKEN", parameterName: "_csrf", token: "e2e-csrf" };
  let listFails = true;
  await page.route("**/api/v1/auth/csrf", async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(csrf) });
  });
  await page.route("**/api/v1/auth/me", async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(userFixture) });
  });
  await page.route("**/api/v1/users", async (route) => {
    const request = route.request();
    if (request.method() === "GET") {
      if (listFails) {
        await route.fulfill({
          status: 403,
          contentType: "application/problem+json",
          body: JSON.stringify({
            type: "https://knowledge-melting-pot.local/problems/forbidden",
            title: "Forbidden", status: 403,
            detail: "ADMIN role required", code: "forbidden", traceId: "trace-403",
          }),
        });
        return;
      }
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify([userFixture, operatorFixture]) });
      return;
    }
    await route.fulfill({
      status: 400,
      contentType: "application/problem+json",
      body: JSON.stringify({
        type: "https://knowledge-melting-pot.local/problems/validation",
        title: "Validation failed", status: 400,
        detail: "One or more fields are invalid", code: "validation", traceId: "trace-400",
        errors: [{ field: "username", message: "username must match the required pattern" }],
      }),
    });
  });

  await page.goto("/users");
  await expect(page.getByRole("alert")).toContainText("ADMIN role required");
  await expect(page.getByRole("button", { name: "重试" })).toBeVisible();

  listFails = false;
  await page.getByRole("button", { name: "重试" }).click();
  await expect(page.getByText("@linan")).toBeVisible();

  await page.getByRole("button", { name: "新增用户" }).click();
  await page.getByLabel("用户名").fill("bad username!");
  await page.getByLabel("显示名").fill("坏名字");
  await page.getByLabel("初始密码（只写）").fill("e2e-initial-password");
  await page.getByRole("checkbox", { name: /OPERATOR/ }).check();
  await page.getByRole("button", { name: "创建用户" }).click();
  await expect(page.getByRole("dialog")).toContainText("One or more fields are invalid");
  await expect(page.getByText("username must match the required pattern")).toBeVisible();
  await page.getByRole("button", { name: "关闭", exact: true }).click();
  await expect(page.getByRole("dialog")).toHaveCount(0);
});

const dashboardScenes = {
  items: [
    {
      id: "3f7a1c2e-0000-4000-8000-000000000001",
      name: "对公贷款五级分类",
      description: "监管制度与分类规则",
      createdAt: "2026-08-01T08:00:00Z",
      updatedAt: "2026-08-03T10:00:00Z",
    },
    {
      id: "3f7a1c2e-0000-4000-8000-000000000002",
      name: "小微企业授信准入",
      description: "准入政策与红线规则",
      createdAt: "2026-08-02T08:00:00Z",
      updatedAt: "2026-08-03T09:00:00Z",
    },
  ],
  page: 0,
  size: 20,
  total: 2,
};

test("dashboard lists real scenes, filters, paginates, and creates a new scene", async ({ page }) => {
  const csrf = { headerName: "X-XSRF-TOKEN", parameterName: "_csrf", token: "e2e-csrf" };
  const createBodies: unknown[] = [];
  const requestedPages: number[] = [];
  await page.route("**/api/v1/auth/csrf", async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(csrf) });
  });
  await page.route("**/api/v1/scenes*", async (route) => {
    const request = route.request();
    if (request.method() === "GET") {
      const page = Number(new URL(request.url()).searchParams.get("page") ?? "0");
      requestedPages.push(page);
      const items = page === 0
        ? dashboardScenes.items
        : [{
            id: "3f7a1c2e-0000-4000-8000-000000000099",
            name: "可疑交易模式识别",
            description: "交易识别规则",
            createdAt: "2026-08-04T08:00:00Z",
            updatedAt: "2026-08-04T09:00:00Z",
          }];
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ items, page, size: 20, total: 21 }) });
      return;
    }
    const body = request.postDataJSON();
    expect(request.headers()["x-xsrf-token"]).toBe(csrf.token);
    createBodies.push(body);
    const created = {
      id: "3f7a1c2e-0000-4000-8000-000000000003",
      name: body.name,
      description: body.description ?? "",
      createdAt: "2026-08-04T08:00:00Z",
      updatedAt: "2026-08-04T08:00:00Z",
    };
    await route.fulfill({ status: 201, contentType: "application/json", body: JSON.stringify(created) });
  });

  await page.goto("/");

  // Real paginated list, no demo notice or fake metrics.
  await expect(page.getByRole("heading", { name: "知识正在形成可追溯的资产" })).toBeVisible();
  await expect(page.getByText("对公贷款五级分类")).toBeVisible();
  await expect(page.getByText("小微企业授信准入")).toBeVisible();
  await expect(page.getByText("离线演示", { exact: true })).toHaveCount(0);
  await expect(page.getByText("当前 Revision", { exact: true })).toHaveCount(0);

  // Pagination reflects the real page count; boundaries disable the controls.
  await expect(page.getByText("第 1 页 / 共 2 页")).toBeVisible();
  await expect(page.getByRole("button", { name: "上一页" })).toBeDisabled();
  await expect(page.getByRole("button", { name: "下一页" })).toBeEnabled();

  // A card opens the real /scenes/{id} route.
  await page.getByRole("button", { name: "打开对公贷款五级分类" }).click();
  await expect(page).toHaveURL(/\/scenes\/3f7a1c2e-0000-4000-8000-000000000001$/);
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "知识正在形成可追溯的资产" })).toBeVisible();

  // Pure frontend text search filters the loaded page only.
  await page.getByPlaceholder("按名称或描述过滤…").fill("小微企业");
  await expect(page.getByText("小微企业授信准入")).toBeVisible();
  await expect(page.getByText("对公贷款五级分类")).toHaveCount(0);
  await page.getByPlaceholder("按名称或描述过滤…").fill("");

  // Next page requests page=1 and disables next at the last page; previous returns to page 0.
  await page.getByRole("button", { name: "下一页" }).click();
  await expect(page.getByText("可疑交易模式识别")).toBeVisible();
  await expect(page.getByText("第 2 页 / 共 2 页")).toBeVisible();
  await expect(page.getByRole("button", { name: "下一页" })).toBeDisabled();
  await expect(page.getByRole("button", { name: "上一页" })).toBeEnabled();
  expect(requestedPages).toContain(1);
  await page.getByRole("button", { name: "上一页" }).click();
  await expect(page.getByText("对公贷款五级分类")).toBeVisible();
  await expect(page.getByText("第 1 页 / 共 2 页")).toBeVisible();

  // Create from the header entry: CSRF POST, then navigation to the returned UUID.
  await page.getByRole("button", { name: "新建萃取场景" }).first().click();
  await expect(page.getByRole("dialog", { name: "新建萃取场景" })).toBeVisible();
  await page.getByLabel("场景名称").fill("可疑交易模式识别");
  await page.getByLabel("描述（可选）").fill("沉淀可疑交易识别规则");
  await page.getByRole("button", { name: "创建场景" }).click();
  await expect(page).toHaveURL(/\/scenes\/3f7a1c2e-0000-4000-8000-000000000003$/);
  expect(createBodies).toHaveLength(1);
  expect(createBodies[0]).toEqual({ name: "可疑交易模式识别", description: "沉淀可疑交易识别规则" });
});

test("dashboard shows an empty state and recovers from a 403 problem via retry", async ({ page }) => {
  let listFails = true;
  await page.route("**/api/v1/scenes*", async (route) => {
    if (listFails) {
      await route.fulfill({
        status: 403,
        contentType: "application/problem+json",
        body: JSON.stringify({
          type: "https://knowledge-melting-pot.local/problems/forbidden",
          title: "Forbidden", status: 403,
          detail: "Authentication required or session expired", code: "forbidden", traceId: "trace-403",
        }),
      });
      return;
    }
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({
      items: [], page: 0, size: 20, total: 0,
    }) });
  });

  await page.goto("/");
  await expect(page.getByRole("alert")).toContainText("无法加载场景列表");
  await expect(page.getByText("Authentication required or session expired")).toBeVisible();
  await expect(page.getByRole("button", { name: "重试" })).toBeVisible();

  listFails = false;
  await page.getByRole("button", { name: "重试" }).click();
  await expect(page.getByText("还没有场景")).toBeVisible();
});

test("scene page manages the real scene, subscenes, and rounds", async ({ page }) => {
  const csrf = { headerName: "X-XSRF-TOKEN", parameterName: "_csrf", token: "e2e-csrf" };
  const sceneId = "3f7a1c2e-0000-4000-8000-000000000005";
  const createdAt = "2026-08-01T08:00:00Z";
  let sceneData = {
    id: sceneId, name: "对公贷款五级分类", description: "监管制度与分类规则",
    createdAt, updatedAt: "2026-08-03T10:00:00Z",
  };
  let subscenes = [{
    id: "sub-1", sceneId, name: "逾期天数与分类下迁", description: "按逾期天数判断最低分类",
    createdAt, updatedAt: createdAt,
  }];
  let rounds = [{
    id: "round-1", subSceneId: "sub-1", roundNumber: 1, status: "DRAFT",
    createdAt, updatedAt: createdAt,
  }];
  const putBodies: unknown[] = [];
  const subsceneBodies: unknown[] = [];
  const roundBodies: unknown[] = [];

  await page.route("**/api/v1/auth/csrf", async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(csrf) });
  });
  await page.route(`**/api/v1/scenes/${sceneId}`, async (route) => {
    const request = route.request();
    if (request.method() === "PUT") {
      const body = request.postDataJSON();
      expect(request.headers()["x-xsrf-token"]).toBe(csrf.token);
      putBodies.push(body);
      sceneData = { ...sceneData, name: body.name, description: body.description ?? "" };
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(sceneData) });
      return;
    }
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(sceneData) });
  });
  await page.route(`**/api/v1/scenes/${sceneId}/subscenes`, async (route) => {
    const request = route.request();
    if (request.method() === "GET") {
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(subscenes) });
      return;
    }
    const body = request.postDataJSON();
    subsceneBodies.push(body);
    const created = { id: "sub-2", sceneId, name: body.name, description: body.description ?? "", createdAt, updatedAt: createdAt };
    subscenes = [...subscenes, created];
    await route.fulfill({ status: 201, contentType: "application/json", body: JSON.stringify(created) });
  });
  await page.route(`**/api/v1/scenes/${sceneId}/rounds`, async (route) => {
    const request = route.request();
    if (request.method() === "GET") {
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(rounds) });
      return;
    }
    const body = request.postDataJSON();
    roundBodies.push(body);
    const nextNumber = rounds.filter((item) => item.subSceneId === body.subSceneId).length + 1;
    const created = { id: `round-${rounds.length + 1}`, subSceneId: body.subSceneId, roundNumber: nextNumber, status: "DRAFT", createdAt, updatedAt: createdAt };
    rounds = [...rounds, created];
    await route.fulfill({ status: 201, contentType: "application/json", body: JSON.stringify(created) });
  });
  await page.route("**/api/v1/materials?*", async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify([]) });
  });

  await page.goto(`/scenes/${sceneId}`);

  // Parallel load renders the real scene, subscene, and round.
  await expect(page.getByRole("heading", { name: "对公贷款五级分类" })).toBeVisible();
  await expect(page.getByText("逾期天数与分类下迁").first()).toBeVisible();
  await expect(page.getByRole("button", { name: /v1 · 草稿/ })).toBeVisible();
  await expect(page.getByText("还没有素材")).toBeVisible();
  await expect(page.getByText("演示萃取 Job")).toHaveCount(0);
  await expect(page.getByText("[SRC-001]")).toHaveCount(0);

  // Save scene: PUT with CSRF, feedback, and refreshed state.
  await page.getByLabel("场景名称").fill("对公贷款五级分类（修订）");
  await page.getByRole("button", { name: "保存场景" }).click();
  await expect(page.getByText("场景已保存。")).toBeVisible();
  expect(putBodies).toHaveLength(1);
  expect(putBodies[0]).toEqual({ name: "对公贷款五级分类（修订）", description: "监管制度与分类规则" });

  // Add a subscene: POST, refresh, and select the created one.
  await page.getByRole("button", { name: /添加子场景/ }).click();
  await expect(page.getByRole("dialog", { name: "添加子场景" })).toBeVisible();
  await page.getByLabel("子场景名称").fill("偿债能力与担保因素综合研判");
  await page.getByRole("button", { name: "创建子场景" }).click();
  await expect(page.getByText("偿债能力与担保因素综合研判").first()).toBeVisible();
  expect(subsceneBodies).toHaveLength(1);
  expect(subsceneBodies[0]).toEqual({ name: "偿债能力与担保因素综合研判" });

  // New round targets the newly selected subscene and appears after refresh.
  await page.getByRole("button", { name: /新一轮/ }).click();
  await expect(page.getByRole("button", { name: /v1 · 草稿/ }).first()).toBeVisible();
  expect(roundBodies).toHaveLength(1);
  expect(roundBodies[0]).toEqual({ subSceneId: "sub-2" });
});

test("scene page shows a 404 with retry and disables rounds without subscenes", async ({ page }) => {
  const sceneId = "3f7a1c2e-0000-4000-8000-000000000006";
  let sceneFails = true;
  await page.route(`**/api/v1/scenes/${sceneId}`, async (route) => {
    if (sceneFails) {
      await route.fulfill({
        status: 404,
        contentType: "application/problem+json",
        body: JSON.stringify({
          type: "https://knowledge-melting-pot.local/problems/not-found",
          title: "Resource not found", status: 404,
          detail: "scene does not exist", code: "not-found", traceId: "trace-404",
        }),
      });
      return;
    }
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        id: sceneId, name: "对公贷款五级分类", description: "监管制度与分类规则",
        createdAt: "2026-08-01T08:00:00Z", updatedAt: "2026-08-03T10:00:00Z",
      }),
    });
  });
  await page.route(`**/api/v1/scenes/${sceneId}/subscenes`, async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify([]) });
  });
  await page.route(`**/api/v1/scenes/${sceneId}/rounds`, async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify([]) });
  });

  await page.goto(`/scenes/${sceneId}`);
  await expect(page.getByRole("alert")).toContainText("无法加载场景");
  await expect(page.getByText("scene does not exist")).toBeVisible();
  await expect(page.getByRole("button", { name: "返回场景库" })).toBeVisible();

  sceneFails = false;
  await page.getByRole("button", { name: "重试" }).click();
  await expect(page.getByRole("heading", { name: "对公贷款五级分类" })).toBeVisible();
  await expect(page.getByText("还没有子场景；点击“添加子场景”创建第一个。")).toBeVisible();
  await expect(page.getByRole("button", { name: /新一轮/ })).toBeDisabled();
  await expect(page.getByText("需要至少一个子场景才能创建轮次")).toBeVisible();
});

test("removes the hardcoded scene shortcut and guards empty scene ids", async ({ page }) => {
  const corporateRequests: string[] = [];
  page.on("request", (request) => {
    if (request.url().includes("corporate-loan-classification")) corporateRequests.push(request.url());
  });

  // The shell no longer offers a hardcoded default scene entry.
  await page.goto("/");
  await expect(page.getByRole("link", { name: "场景流程" })).toHaveCount(0);
  await expect(page.getByText("离线演示", { exact: true })).toHaveCount(0);

  // /scenes/ and /scenes must land on the neutral not-found page without a fake scene id request.
  await page.goto("/scenes/");
  await expect(page.getByRole("heading", { name: "没有找到这个页面" })).toBeVisible();
  await expect(page.getByText("地址可能已变化，或对应场景已被删除。返回工作台查看场景列表。")).toBeVisible();
  expect(corporateRequests).toEqual([]);

  await page.goto("/scenes");
  await expect(page.getByRole("heading", { name: "没有找到这个页面" })).toBeVisible();
  expect(corporateRequests).toEqual([]);
});

test("uploads a material via the browser multipart presigned flow", async ({ page }) => {
  const csrf = { headerName: "X-XSRF-TOKEN", parameterName: "_csrf", token: "e2e-csrf" };
  const sceneId = "3f7a1c2e-0000-4000-8000-000000000007";
  const subSceneId = "sub-1";
  const roundId = "round-1";
  const intentBodies: Record<string, unknown>[] = [];
  const completeBodies: unknown[] = [];
  const putBodies: Buffer[] = [];
  const partSize = 10;
  const fileBytes = Buffer.from("pdf-content-15b");

  await page.route("**/api/v1/auth/csrf", async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(csrf) });
  });
  await page.route(`**/api/v1/scenes/${sceneId}`, async (route) => {
    await route.fulfill({
      status: 200, contentType: "application/json",
      body: JSON.stringify({ id: sceneId, name: "对公贷款五级分类", description: "监管制度与分类规则", createdAt: "2026-08-01T08:00:00Z", updatedAt: "2026-08-03T10:00:00Z" }),
    });
  });
  await page.route(`**/api/v1/scenes/${sceneId}/subscenes`, async (route) => {
    await route.fulfill({
      status: 200, contentType: "application/json",
      body: JSON.stringify([{ id: subSceneId, sceneId, name: "逾期天数与分类下迁", description: "按逾期天数判断最低分类", createdAt: "2026-08-01T08:00:00Z", updatedAt: "2026-08-01T08:00:00Z" }]),
    });
  });
  await page.route(`**/api/v1/scenes/${sceneId}/rounds`, async (route) => {
    await route.fulfill({
      status: 200, contentType: "application/json",
      body: JSON.stringify([{ id: roundId, subSceneId, roundNumber: 1, status: "DRAFT", createdAt: "2026-08-01T08:00:00Z", updatedAt: "2026-08-01T08:00:00Z" }]),
    });
  });
  let materials: unknown[] = [];
  await page.route("**/api/v1/materials?*", async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(materials) });
  });
  await page.route("**/api/v1/materials/upload-intents", async (route) => {
    const request = route.request();
    const body = request.postDataJSON();
    expect(request.headers()["x-xsrf-token"]).toBe(csrf.token);
    expect(request.headers()["idempotency-key"]).toMatch(/^[0-9a-f-]{8,}$/);
    intentBodies.push(body);
    await route.fulfill({
      status: 201, contentType: "application/json",
      body: JSON.stringify({
        id: "intent-7", materialId: "mat-7", objectKey: "quarantine/mat-7",
        materialStatus: "PENDING_UPLOAD", uploadMode: "MULTIPART_PRESIGNED",
        capabilityStatus: "MULTIPART_PRESIGNED", uploadUrlAvailable: true, maxBytes: 209715200,
        supportedFormats: ["pdf", "docx", "xlsx", "txt"], completionBehavior: "QUEUES_VALIDATION",
        messageCode: "material.upload.multipart-presigned",
        parts: [
          { partNumber: 1, url: "http://127.0.0.1:4173/storage/parts/1?X-Amz-Signature=test", headers: {} },
          { partNumber: 2, url: "http://127.0.0.1:4173/storage/parts/2?X-Amz-Signature=test", headers: {} },
        ],
        partSize, partCount: 2,
        presignedUrls: ["http://127.0.0.1:4173/storage/parts/1?X-Amz-Signature=test", "http://127.0.0.1:4173/storage/parts/2?X-Amz-Signature=test"],
      }),
    });
  });
  await page.route("**/storage/parts/*", async (route) => {
    expect(route.request().method()).toBe("PUT");
    putBodies.push(route.request().postDataBuffer() ?? Buffer.alloc(0));
    const partNumber = new URL(route.request().url()).pathname.split("/").pop();
    await route.fulfill({ status: 200, headers: { ETag: `"part-etag-${partNumber}"` }, body: "" });
  });
  await page.route("**/api/v1/materials/upload-intents/*/complete", async (route) => {
    const body = route.request().postDataJSON();
    completeBodies.push(body);
    await route.fulfill({
      status: 202, contentType: "application/json",
      body: JSON.stringify({ jobId: "job-9", status: "QUEUED", statusUrl: "/api/v1/jobs/job-9", eventsUrl: "/api/v1/jobs/job-9/events" }),
    });
  });
  await page.route("**/api/v1/jobs/job-9", async (route) => {
    await route.fulfill({
      status: 200, contentType: "application/json",
      body: JSON.stringify({ id: "job-9", type: "INGEST", status: "RUNNING", stage: "scan", percent: 40, attempt: 0, errorCode: null, createdAt: "2026-08-01T08:00:00Z", updatedAt: "2026-08-01T08:00:00Z" }),
    });
  });
  await page.route("**/api/v1/materials/upload-intents/*", async (route) => {
    await route.fulfill({ status: 204 });
  });

  await page.goto(`/scenes/${sceneId}`);
  await expect(page.getByText("还没有素材")).toBeVisible();

  await page.getByRole("button", { name: "上传素材" }).click();
  await expect(page.getByRole("dialog", { name: "上传素材" })).toBeVisible();

  // Frontend file validation rejects .doc before any request is made.
  await page.getByLabel(/文件（PDF/).setInputFiles({ name: "legacy.doc", mimeType: "application/msword", buffer: Buffer.from("doc") });
  await expect(page.getByText("仅支持 PDF / DOCX / XLSX / TXT；.doc 与 .xls 不受支持。")).toBeVisible();

  await page.getByLabel(/文件（PDF/).setInputFiles({ name: "rules.pdf", mimeType: "application/pdf", buffer: fileBytes });
  await expect(page.getByRole("button", { name: "开始上传" })).toBeEnabled();

  // Holdout partitions disable the regulatory-alignment flag.
  await page.getByRole("radio", { name: /LABELED_HOLDOUT/ }).check();
  await expect(page.getByRole("checkbox", { name: "监管依据" })).toBeDisabled();
  await expect(page.getByText("留出评测分区不能作为监管对齐依据。")).toBeVisible();
  await page.getByRole("radio", { name: /SOURCE/ }).check();
  await expect(page.getByRole("checkbox", { name: "监管依据" })).toBeEnabled();

  await page.getByRole("button", { name: "开始上传" }).click();
  await expect(page.getByText("上传完成，校验任务已排队")).toBeVisible();
  await expect(page.getByText(/任务 job-9/)).toBeVisible();

  expect(intentBodies).toHaveLength(1);
  expect(intentBodies[0]).toMatchObject({
    fileName: "rules.pdf",
    sizeBytes: fileBytes.length,
    mediaType: "application/pdf",
    roundId,
    subSceneIds: [subSceneId],
    partition: "SOURCE",
    shareScope: "ROUND",
    regulatorySource: false,
  });
  expect(String(intentBodies[0].sha256)).toMatch(/^[a-f0-9]{64}$/);
  expect(putBodies).toHaveLength(2);
  expect(putBodies[0].length).toBe(partSize);
  expect(putBodies[1].length).toBe(fileBytes.length - partSize);
  expect(completeBodies).toHaveLength(1);
  expect(completeBodies[0]).toEqual({
    parts: [
      { partNumber: 1, etag: '"part-etag-1"' },
      { partNumber: 2, etag: '"part-etag-2"' },
    ],
  });

  // Job status is refreshable and the completed material appears in the list.
  await page.getByRole("button", { name: "刷新状态" }).click();
  await expect(page.getByText(/RUNNING/)).toBeVisible();
  await page.getByRole("button", { name: "完成" }).click();
  await expect(page.getByRole("dialog", { name: "上传素材" })).toHaveCount(0);

  materials = [{
    id: "mat-7", fileName: "rules.pdf", format: "PDF", mediaType: "application/pdf",
    sizeBytes: fileBytes.length, status: "UPLOADED",
    createdAt: "2026-08-01T08:00:00Z", updatedAt: "2026-08-01T08:05:00Z",
    binding: { id: "b-7", roundId, subSceneId, partition: "SOURCE", shareScope: "ROUND", regulatorySource: false, active: true },
  }];
  await page.getByRole("button", { name: "刷新" }).click();
  await expect(page.getByText("rules.pdf")).toBeVisible();
  await expect(page.getByText("已上传")).toBeVisible();
  await expect(page.getByText("商业银行金融资产风险分类办法.pdf")).toHaveCount(0);
});

test("aborts the upload intent when a part upload fails", async ({ page }) => {
  const csrf = { headerName: "X-XSRF-TOKEN", parameterName: "_csrf", token: "e2e-csrf" };
  const sceneId = "3f7a1c2e-0000-4000-8000-000000000008";
  const abortUrls: string[] = [];
  const partSize = 10;

  await page.route("**/api/v1/auth/csrf", async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(csrf) });
  });
  await page.route(`**/api/v1/scenes/${sceneId}`, async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ id: sceneId, name: "对公贷款五级分类", description: "", createdAt: "2026-08-01T08:00:00Z", updatedAt: "2026-08-01T08:00:00Z" }) });
  });
  await page.route(`**/api/v1/scenes/${sceneId}/subscenes`, async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify([{ id: "sub-1", sceneId, name: "逾期天数与分类下迁", description: "", createdAt: "2026-08-01T08:00:00Z", updatedAt: "2026-08-01T08:00:00Z" }]) });
  });
  await page.route(`**/api/v1/scenes/${sceneId}/rounds`, async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify([{ id: "round-1", subSceneId: "sub-1", roundNumber: 1, status: "DRAFT", createdAt: "2026-08-01T08:00:00Z", updatedAt: "2026-08-01T08:00:00Z" }]) });
  });
  await page.route("**/api/v1/materials?*", async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify([]) });
  });
  await page.route("**/api/v1/materials/upload-intents", async (route) => {
    await route.fulfill({
      status: 201, contentType: "application/json",
      body: JSON.stringify({
        id: "intent-8", materialId: "mat-8", objectKey: "quarantine/mat-8",
        materialStatus: "PENDING_UPLOAD", uploadMode: "MULTIPART_PRESIGNED",
        capabilityStatus: "MULTIPART_PRESIGNED", uploadUrlAvailable: true, maxBytes: 209715200,
        supportedFormats: ["pdf", "docx", "xlsx", "txt"], completionBehavior: "QUEUES_VALIDATION",
        messageCode: "material.upload.multipart-presigned",
        parts: [
          { partNumber: 1, url: "http://127.0.0.1:4173/storage/parts/1?X-Amz-Signature=test", headers: {} },
          { partNumber: 2, url: "http://127.0.0.1:4173/storage/parts/2?X-Amz-Signature=test", headers: {} },
        ],
        partSize, partCount: 2, presignedUrls: [],
      }),
    });
  });
  await page.route("**/storage/parts/*", async (route) => {
    const partNumber = new URL(route.request().url()).pathname.split("/").pop();
    if (partNumber === "2") {
      await route.fulfill({ status: 500, contentType: "text/plain", body: "boom" });
      return;
    }
    await route.fulfill({ status: 200, headers: { ETag: '"part-etag-1"' }, body: "" });
  });
  await page.route("**/api/v1/materials/upload-intents/*", async (route) => {
    expect(route.request().method()).toBe("DELETE");
    abortUrls.push(route.request().url());
    await route.fulfill({ status: 204 });
  });
  await page.route("**/api/v1/materials/upload-intents/*/complete", async (route) => {
    throw new Error("complete must not be called after a failed part upload");
  });

  await page.goto(`/scenes/${sceneId}`);
  await page.getByRole("button", { name: "上传素材" }).click();
  await page.getByLabel(/文件（PDF/).setInputFiles({ name: "broken.pdf", mimeType: "application/pdf", buffer: Buffer.from("%PDF broken content!!") });
  await page.getByRole("button", { name: "开始上传" }).click();

  await expect(page.getByRole("alert")).toContainText("第 2/2 部分上传失败（HTTP 500）");
  await expect.poll(() => abortUrls.length).toBe(1);
  await expect(page.getByText("上传完成，校验任务已排队")).toHaveCount(0);
  await page.getByRole("button", { name: "关闭", exact: true }).last().click();
  await expect(page.getByRole("dialog", { name: "上传素材" })).toHaveCount(0);
});

test("maintains a knowledge document revision flow in step 2", async ({ page }) => {
  const csrf = { headerName: "X-XSRF-TOKEN", parameterName: "_csrf", token: "e2e-csrf" };
  const sceneId = "3f7a1c2e-0000-4000-8000-000000000009";
  const subSceneId = "sub-1";
  let doc: Record<string, unknown> | null = null;
  let revisionCounter = 0;
  const revisions: Record<string, unknown>[] = [];
  const saveBodies: Record<string, unknown>[] = [];
  const ifMatchHeaders: (string | null)[] = [];

  await page.route("**/api/v1/auth/csrf", async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(csrf) });
  });
  await page.route(`**/api/v1/scenes/${sceneId}`, async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ id: sceneId, name: "对公贷款五级分类", description: "", createdAt: "2026-08-01T08:00:00Z", updatedAt: "2026-08-01T08:00:00Z" }) });
  });
  await page.route(`**/api/v1/scenes/${sceneId}/subscenes`, async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify([{ id: subSceneId, sceneId, name: "逾期天数与分类下迁", description: "", createdAt: "2026-08-01T08:00:00Z", updatedAt: "2026-08-01T08:00:00Z" }]) });
  });
  await page.route(`**/api/v1/scenes/${sceneId}/rounds`, async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify([{ id: "round-1", subSceneId, roundNumber: 1, status: "DRAFT", createdAt: "2026-08-01T08:00:00Z", updatedAt: "2026-08-01T08:00:00Z" }]) });
  });
  await page.route("**/api/v1/materials?*", async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify([]) });
  });
  await page.route(`**/api/v1/knowledge-documents/${subSceneId}`, async (route) => {
    const request = route.request();
    if (request.method() === "GET") {
      if (doc === null) {
        await route.fulfill({ status: 404, contentType: "application/problem+json", body: JSON.stringify({ detail: "knowledge document not found", code: "not-found", traceId: "trace" }) });
        return;
      }
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(doc) });
      return;
    }
    const body = request.postDataJSON();
    const ifMatch = request.headers()["if-match"] ?? null;
    saveBodies.push(body);
    ifMatchHeaders.push(ifMatch);
    if (doc !== null && ifMatch !== null && ifMatch !== "*" && ifMatch !== doc.etag) {
      await route.fulfill({ status: 412, contentType: "application/problem+json", body: JSON.stringify({ detail: "knowledge document has a newer revision", code: "precondition-failed", traceId: "trace-412" }) });
      return;
    }
    revisionCounter += 1;
    doc = {
      id: subSceneId, subSceneId, revisionId: `rev-${revisionCounter}`, revisionNumber: revisionCounter,
      contentMd: body.contentMd, contentHash: `hash-${revisionCounter}`, finalized: body.finalize,
      sourceRefs: [], etag: `"etag-${revisionCounter}"`,
    };
    revisions.push(doc);
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(doc) });
  });
  await page.route(`**/api/v1/knowledge-documents/${subSceneId}/revisions`, async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(revisions) });
  });

  await page.goto(`/scenes/${sceneId}`);
  await page.getByRole("button", { name: "进入知识萃取" }).click();

  // First visit: 404 seeds an empty editor for a new document.
  await expect(page.getByRole("textbox", { name: "知识文档 Markdown" })).toBeVisible();
  await expect(page.getByText("新文档", { exact: true })).toBeVisible();

  // Create the first revision: wildcard If-Match and a body carrying subSceneId.
  await page.getByRole("textbox", { name: "知识文档 Markdown" }).fill("# 逾期分类规则\n\n说明内容");
  await page.getByRole("button", { name: "保存 Revision" }).click();
  await expect(page.getByText("已保存 Revision v1。")).toBeVisible();
  expect(saveBodies).toHaveLength(1);
  expect(saveBodies[0]).toEqual({ subSceneId, contentMd: "# 逾期分类规则\n\n说明内容", finalize: false });
  expect(ifMatchHeaders[0]).toBe("*");
  await expect(page.getByText("v1", { exact: true }).first()).toBeVisible();

  // Finalize with a real SRC anchor: server validation passes and revision bumps.
  await page.getByRole("textbox", { name: "知识文档 Markdown" }).fill("# 逾期分类规则\n\n[SRC-001] 说明");
  await page.getByRole("button", { name: "定稿" }).click();
  await expect(page.getByText("文档已定稿（Revision v2）。")).toBeVisible();
  expect(saveBodies).toHaveLength(2);
  expect(saveBodies[1]).toMatchObject({ finalize: true, contentMd: "# 逾期分类规则\n\n[SRC-001] 说明" });
  expect(ifMatchHeaders[1]).toBe('"etag-1"');
  await expect(page.getByText("已定稿", { exact: true })).toBeVisible();

  // Revision history lists the immutable versions.
  await page.getByRole("button", { name: "Revision 历史" }).click();
  await expect(page.getByText("v1", { exact: true }).first()).toBeVisible();
  await expect(page.getByText("v2", { exact: true }).first()).toBeVisible();

  // A concurrent update triggers a 412 conflict with reload guidance.
  doc = { ...doc!, etag: '"etag-concurrent"' };
  await page.getByRole("textbox", { name: "知识文档 Markdown" }).fill("# 逾期分类规则\n\n[SRC-001] 被覆盖的内容");
  await page.getByRole("button", { name: "保存 Revision" }).click();
  await expect(page.getByText("knowledge document has a newer revision")).toBeVisible();
  await expect(page.getByRole("button", { name: "重新加载" })).toBeVisible();
});

test("step 2 surfaces server finalization validation errors without a fake revision", async ({ page }) => {
  const csrf = { headerName: "X-XSRF-TOKEN", parameterName: "_csrf", token: "e2e-csrf" };
  const sceneId = "3f7a1c2e-0000-4000-8000-000000000010";
  const subSceneId = "sub-1";
  const saveBodies: Record<string, unknown>[] = [];

  await page.route("**/api/v1/auth/csrf", async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(csrf) });
  });
  await page.route(`**/api/v1/scenes/${sceneId}`, async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ id: sceneId, name: "对公贷款五级分类", description: "", createdAt: "2026-08-01T08:00:00Z", updatedAt: "2026-08-01T08:00:00Z" }) });
  });
  await page.route(`**/api/v1/scenes/${sceneId}/subscenes`, async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify([{ id: subSceneId, sceneId, name: "逾期天数与分类下迁", description: "", createdAt: "2026-08-01T08:00:00Z", updatedAt: "2026-08-01T08:00:00Z" }]) });
  });
  await page.route(`**/api/v1/scenes/${sceneId}/rounds`, async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify([{ id: "round-1", subSceneId, roundNumber: 1, status: "DRAFT", createdAt: "2026-08-01T08:00:00Z", updatedAt: "2026-08-01T08:00:00Z" }]) });
  });
  await page.route("**/api/v1/materials?*", async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify([]) });
  });
  await page.route(`**/api/v1/knowledge-documents/${subSceneId}`, async (route) => {
    const request = route.request();
    if (request.method() === "GET") {
      await route.fulfill({ status: 404, contentType: "application/problem+json", body: JSON.stringify({ detail: "knowledge document not found", code: "not-found", traceId: "trace" }) });
      return;
    }
    const body = request.postDataJSON();
    saveBodies.push(body);
    if (body.finalize && !/\[SRC-[A-Za-z0-9_-]{1,100}\]/.test(String(body.contentMd))) {
      await route.fulfill({ status: 422, contentType: "application/problem+json", body: JSON.stringify({ detail: "a finalized document requires at least one [SRC-*] anchor", code: "document-validation", traceId: "trace-422" }) });
      return;
    }
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ id: subSceneId, subSceneId, revisionId: "rev-1", revisionNumber: 1, contentMd: body.contentMd, contentHash: "hash", finalized: body.finalize, sourceRefs: [], etag: '"etag-1"' }) });
  });

  await page.goto(`/scenes/${sceneId}`);
  await page.getByRole("button", { name: "进入知识萃取" }).click();
  await page.getByRole("textbox", { name: "知识文档 Markdown" }).fill("# 无锚点标题");
  await page.getByRole("button", { name: "定稿" }).click();

  // The server validation error is shown verbatim and no revision is created.
  await expect(page.getByText("a finalized document requires at least one [SRC-*] anchor")).toBeVisible();
  await expect(page.getByText("已保存 Revision", { exact: false })).toHaveCount(0);
  expect(saveBodies).toHaveLength(1);
  expect(saveBodies[0]).toMatchObject({ finalize: true });
});

test("generates assets, tracks the job, and publishes in step 3", async ({ page }) => {
  const csrf = { headerName: "X-XSRF-TOKEN", parameterName: "_csrf", token: "e2e-csrf" };
  const sceneId = "3f7a1c2e-0000-4000-8000-000000000011";
  const subSceneId = "sub-1";
  const baseAsset = (type: string, status: string, extra: Record<string, unknown> = {}) => ({
    id: `asset-${type.toLowerCase()}`, subSceneId, type, version: 1, status,
    documentRevisionId: "rev-1", objectKey: "", checksum: "", failureReason: "",
    createdAt: "2026-08-03T08:00:00Z", updatedAt: "2026-08-03T08:00:00Z", ...extra,
  });
  const pendingTypes = ["RULE_CATALOG", "DECISION_FLOW", "SKILL_PACKAGE", "QA_PAIRS"];
  let assets = [
    ...pendingTypes.map((type) => baseAsset(type, "PENDING")),
    baseAsset("EVALUATION_SET", "BLOCKED", { failureReason: "no READY LABELED_HOLDOUT binding for the latest round" }),
  ];
  const genBodies: Record<string, unknown>[] = [];
  const validationBodies: Record<string, unknown>[] = [];
  const releaseBodies: Record<string, unknown>[] = [];
  let jobPolls = 0;

  await page.route("**/api/v1/auth/csrf", async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(csrf) });
  });
  await page.route("**/api/v1/auth/me", async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({
      id: "u-1", username: "publisher", displayName: "发布员", enabled: true,
      roles: ["OPERATOR", "PUBLISHER"], mustChangePassword: false,
    }) });
  });
  await page.route(`**/api/v1/scenes/${sceneId}`, async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ id: sceneId, name: "对公贷款五级分类", description: "", createdAt: "2026-08-01T08:00:00Z", updatedAt: "2026-08-01T08:00:00Z" }) });
  });
  await page.route(`**/api/v1/scenes/${sceneId}/subscenes`, async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify([{ id: subSceneId, sceneId, name: "逾期天数与分类下迁", description: "", createdAt: "2026-08-01T08:00:00Z", updatedAt: "2026-08-01T08:00:00Z" }]) });
  });
  await page.route(`**/api/v1/scenes/${sceneId}/rounds`, async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify([{ id: "round-1", subSceneId, roundNumber: 1, status: "DRAFT", createdAt: "2026-08-01T08:00:00Z", updatedAt: "2026-08-01T08:00:00Z" }]) });
  });
  await page.route("**/api/v1/materials?*", async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify([]) });
  });
  await page.route(`**/api/v1/knowledge-documents/${subSceneId}`, async (route) => {
    const request = route.request();
    if (request.method() === "GET") {
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({
        id: subSceneId, subSceneId, revisionId: "rev-1", revisionNumber: 3,
        contentMd: "# 逾期分类规则\n\n[SRC-001] 依据", contentHash: "hash", finalized: true,
        sourceRefs: [], etag: '"etag-3"',
      }) });
      return;
    }
    await route.continue();
  });
  await page.route(`**/api/v1/subscenes/${subSceneId}/assets`, async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(assets) });
  });
  await page.route(`**/api/v1/subscenes/${subSceneId}/asset-generation-jobs`, async (route) => {
    const body = route.request().postDataJSON();
    expect(route.request().headers()["idempotency-key"]).toMatch(/^[0-9a-f-]{8,}$/);
    genBodies.push(body);
    await route.fulfill({ status: 202, contentType: "application/json", body: JSON.stringify({
      jobId: "gen-job-1", statusUrl: "/api/v1/jobs/gen-job-1", eventsUrl: "/api/v1/jobs/gen-job-1/events", status: "QUEUED",
    }) });
  });
  await page.route("**/api/v1/jobs/gen-job-1", async (route) => {
    jobPolls += 1;
    const running = jobPolls === 1;
    if (!running) {
      assets = [
        ...pendingTypes.map((type) => baseAsset(type, "READY", {
          objectKey: `assets/${subSceneId}/${type.toLowerCase()}/v1/bundle.zip`, checksum: "abc",
        })),
        baseAsset("EVALUATION_SET", "BLOCKED", { failureReason: "no READY LABELED_HOLDOUT binding for the latest round" }),
      ];
    }
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({
      id: "gen-job-1", type: "GENERATE_ALL", status: running ? "RUNNING" : "SUCCEEDED",
      stage: running ? "asset" : null, percent: running ? 60 : 100, attempt: 0, errorCode: null,
      createdAt: "2026-08-03T08:00:00Z", updatedAt: "2026-08-03T08:00:00Z",
    }) });
  });
  await page.route(`**/api/v1/scenes/${sceneId}/release-validations`, async (route) => {
    validationBodies.push(route.request().postDataJSON());
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({
      ready: true, coverage: "PARTIAL", baseReleaseId: "base-1",
      selected: [subSceneId], carriedForward: [], missing: [],
      blockers: [], warnings: ["EVALUATION_SET 仍为 BLOCKED，未来全覆盖会被阻断"],
    }) });
  });
  await page.route(`**/api/v1/scenes/${sceneId}/releases/latest`, async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({
      id: "base-1", sceneId, tag: "v0.9", coverage: "PARTIAL", note: "上次发布",
      previousReleaseId: null, manifestSha256: "base-manifest", createdAt: "2026-08-02T08:00:00Z",
    }) });
  });
  await page.route(`**/api/v1/scenes/${sceneId}/releases`, async (route) => {
    releaseBodies.push(route.request().postDataJSON());
    expect(route.request().headers()["idempotency-key"]).toMatch(/^[0-9a-f-]{8,}$/);
    await route.fulfill({ status: 201, contentType: "application/json", body: JSON.stringify({
      id: "rel-1", sceneId, tag: "v1.0", coverage: "PARTIAL", note: "首次发布",
      previousReleaseId: null, manifestSha256: "manifest-hash-123", createdAt: "2026-08-03T08:00:00Z",
    }) });
  });
  await page.route("**/api/v1/releases/rel-1/manifest", async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ schemaVersion: "1.0", releaseId: "rel-1", tag: "v1.0" }) });
  });
  await page.route("**/api/v1/releases/*/subscenes/*/evaluation-runs", async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify([]) });
  });

  await page.goto(`/scenes/${sceneId}`);
  await page.getByRole("button", { name: /知识生成及发布/ }).click();

  // Real asset list: five types, BLOCKED evaluation set shows its prerequisite.
  await expect(page.getByText("前置缺失", { exact: true })).toBeVisible();
  await expect(page.getByText("评测集需要至少一份 READY 的留出（HOLDOUT）素材；缺少时会阻断发布预检。")).toBeVisible();
  await expect(page.getByRole("heading", { name: "真实留出集评测" })).toBeVisible();
  await expect(page.getByText("当前轮次没有 READY 的 LABELED_HOLDOUT 素材，不能运行或显示评测指标。")).toBeVisible();
  await expect(page.getByRole("button", { name: "运行评测" })).toBeDisabled();
  await expect(page.getByText("下载 Bundle")).toHaveCount(0);

  // Generate all: POST carries revision + types, job is polled to terminal state, assets refresh.
  await page.getByRole("button", { name: "生成全部" }).click();
  await expect(page.getByText(/资产生成任务 gen-job-1/)).toBeVisible();
  await expect(page.getByText(/SUCCEEDED/)).toBeVisible({ timeout: 7000 });
  expect(genBodies).toHaveLength(1);
  expect(genBodies[0]).toMatchObject({
    documentRevisionId: "rev-1",
    types: ["RULE_CATALOG", "DECISION_FLOW", "SKILL_PACKAGE", "QA_PAIRS", "EVALUATION_SET"],
  });

  await expect(page.getByText("下载 Bundle").first()).toBeVisible();
  await expect(page.getByRole("link", { name: "下载 Bundle" }).first()).toHaveAttribute("href", /\/api\/v1\/assets\/asset-rule_catalog\/download/);

  // Release: baseline is loaded, preflight uses it, and the published request carries it.
  await expect(page.getByText(/当前发布基线：v0.9/)).toBeVisible();
  await page.getByLabel("发布 tag").fill("v1.0");
  await page.getByLabel("发布说明").fill("首次发布");
  await page.getByRole("checkbox", { name: /将逾期天数与分类下迁加入本次发布/ }).check();
  await page.getByRole("checkbox", { name: "我已核对发布内容与范围" }).check();
  await page.getByRole("button", { name: "发布预检" }).click();
  await expect(page.getByText("发布预检通过")).toBeVisible();
  expect(validationBodies).toHaveLength(1);
  expect(validationBodies[0]).toMatchObject({ tag: "v1.0", confirmed: true, expectedBaseReleaseId: "base-1" });

  // Editing the draft after preflight disables publishing until a re-preflight.
  await page.getByLabel("发布说明").fill("首次发布（修订）");
  await expect(page.getByText("发布内容已变更，确认发布已禁用；请重新执行发布预检。")).toBeVisible();
  await expect(page.getByRole("button", { name: "内容已变更，请重新预检" })).toBeDisabled();
  await page.getByRole("button", { name: "发布预检" }).click();
  await expect(page.getByText("发布预检通过")).toBeVisible();
  expect(validationBodies).toHaveLength(2);

  await page.getByRole("button", { name: "确认发布" }).click();
  await expect(page.getByText("已发布 v1.0 · 部分覆盖")).toBeVisible();
  expect(releaseBodies).toHaveLength(1);
  expect(releaseBodies[0]).toMatchObject({ tag: "v1.0", confirmed: true, expectedBaseReleaseId: "base-1" });

  await page.getByRole("button", { name: "查看 Manifest" }).click();
  await expect(page.getByText(/"releaseId"\s*:\s*"rel-1"/)).toBeVisible();

  // Manifest downloads as a real file (Blob download), not just inline text.
  const downloadPromise = page.waitForEvent("download");
  await page.getByRole("button", { name: "下载 Manifest" }).click();
  const download = await downloadPromise;
  expect(download.suggestedFilename()).toBe("manifest-v1.0.json");
});

test("operators cannot publish in step 3", async ({ page }) => {
  const csrf = { headerName: "X-XSRF-TOKEN", parameterName: "_csrf", token: "e2e-csrf" };
  const sceneId = "3f7a1c2e-0000-4000-8000-000000000012";
  const subSceneId = "sub-1";

  await page.route("**/api/v1/auth/csrf", async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(csrf) });
  });
  await page.route("**/api/v1/auth/me", async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({
      id: "u-2", username: "operator", displayName: "运营", enabled: true,
      roles: ["OPERATOR"], mustChangePassword: false,
    }) });
  });
  await page.route(`**/api/v1/scenes/${sceneId}`, async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ id: sceneId, name: "对公贷款五级分类", description: "", createdAt: "2026-08-01T08:00:00Z", updatedAt: "2026-08-01T08:00:00Z" }) });
  });
  await page.route(`**/api/v1/scenes/${sceneId}/subscenes`, async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify([{ id: subSceneId, sceneId, name: "逾期天数与分类下迁", description: "", createdAt: "2026-08-01T08:00:00Z", updatedAt: "2026-08-01T08:00:00Z" }]) });
  });
  await page.route(`**/api/v1/scenes/${sceneId}/rounds`, async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify([{ id: "round-1", subSceneId, roundNumber: 1, status: "DRAFT", createdAt: "2026-08-01T08:00:00Z", updatedAt: "2026-08-01T08:00:00Z" }]) });
  });
  await page.route("**/api/v1/materials?*", async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify([]) });
  });
  await page.route(`**/api/v1/subscenes/${subSceneId}/assets`, async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify([]) });
  });
  await page.route("**/api/v1/knowledge-documents/sub-1", async (route) => {
    await route.fulfill({ status: 404, contentType: "application/problem+json", body: JSON.stringify({ detail: "not found", code: "not-found", traceId: "t" }) });
  });
  await page.route("**/api/v1/scenes/3f7a1c2e-0000-4000-8000-000000000012/releases/latest", async (route) => {
    await route.fulfill({ status: 404, contentType: "application/problem+json", body: JSON.stringify({ detail: "scene has no published release", code: "not-found", traceId: "t" }) });
  });

  await page.goto(`/scenes/${sceneId}`);
  await page.getByRole("button", { name: /知识生成及发布/ }).click();

  await expect(page.getByText(/仅 PUBLISHER \/ ADMIN 可执行发布/)).toBeVisible();
  await expect(page.getByLabel("发布 tag")).toHaveCount(0);
  await expect(page.getByText(/尚未创建并定稿知识文档/)).toBeVisible();
});

test("audit page lists real events with safe details, paging, and CSV export", async ({ page }) => {
  const event = (id: string, action: string, details: Record<string, unknown>, occurredAt: string) => ({
    id, actorId: `00000000-0000-0000-0000-00000000${id}`, action, targetType: "ENTITY",
    targetId: `target-${id}`, detailsJson: JSON.stringify(details), traceId: `tr-${id}`,
    occurredAt,
  });
  await page.route("**/api/v1/audit-events?*", async (route) => {
    const pageNo = Number(new URL(route.request().url()).searchParams.get("page") ?? "0");
    if (pageNo === 1) {
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify([
        event("03", "USER_UPDATED", {}, "2026-08-03T10:00:00Z"),
      ]) });
      return;
    }
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify([
      event("01", "RELEASE_PUBLISHED", { tag: "v1.0", manifestHash: "m123", secretKey: "SHOULD-NOT-SHOW" }, "2026-08-03T08:00:00Z"),
      event("02", "MODEL_CONNECTION_CREATED", { name: "网关名称" }, "2026-08-03T09:00:00Z"),
      ...Array.from({ length: 48 }, (_, index) =>
        event(String(10 + index).padStart(2, "0"), "JOB_SUCCEEDED", {}, "2026-08-03T09:00:00Z")),
    ]) });
  });

  await page.goto("/audit");

  // Real events render; allowlisted metadata only, arbitrary details never leak.
  await expect(page.getByText("RELEASE_PUBLISHED")).toBeVisible();
  await expect(page.getByText(/tag=v1\.0/)).toBeVisible();
  await expect(page.getByText("SHOULD-NOT-SHOW")).toHaveCount(0);
  await expect(page.getByText("网关名称")).toHaveCount(0);

  // Actor column is neutral: only the short UUID, no role/type inference.
  await expect(page.getByText("Actor 00000000", { exact: false }).first()).toBeVisible();
  await expect(page.getByText("ADMIN", { exact: true })).toHaveCount(0);

  // Local filtering of the current page.
  await page.getByPlaceholder("例如 RELEASE_PUBLISHED").fill("MODEL_CONNECTION");
  await expect(page.getByText("MODEL_CONNECTION_CREATED")).toBeVisible();
  await expect(page.getByText("RELEASE_PUBLISHED")).toHaveCount(0);
  await page.getByPlaceholder("例如 RELEASE_PUBLISHED").fill("");

  // Simple paging to the next page.
  await page.getByRole("button", { name: "下一页" }).click();
  await expect(page.getByText("USER_UPDATED")).toBeVisible();

  // CSV export of the filtered current page.
  const downloadPromise = page.waitForEvent("download");
  await page.getByRole("button", { name: "导出筛选结果" }).click();
  const download = await downloadPromise;
  expect(download.suggestedFilename()).toBe("audit-page-2.csv");
});

test("audit page shows an admin-only 403 state", async ({ page }) => {
  await page.route("**/api/v1/audit-events?*", async (route) => {
    await route.fulfill({ status: 403, contentType: "application/problem+json", body: JSON.stringify({ detail: "Access Denied", code: "forbidden", traceId: "t-403" }) });
  });

  await page.goto("/audit");

  await expect(page.getByText("仅 ADMIN 可查看审计记录。")).toBeVisible();
  await expect(page.getByRole("button", { name: "重试" })).toBeVisible();
});

test.beforeEach(async ({ page }) => {
  // Every test that renders the Shell triggers /auth/me; provide a neutral
  // default so no request falls through to the vite proxy and 502s. Tests that
  // need a specific identity register their own route (registered later, wins).
  await page.route("**/api/v1/auth/me", async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({
      id: "00000000-0000-0000-0000-000000000099",
      username: "default-operator",
      displayName: "默认操作员",
      enabled: true,
      roles: ["OPERATOR"],
      mustChangePassword: false,
    }) });
  });
});

test("shell shows the real admin session identity and role", async ({ page }) => {
  await page.route("**/api/v1/auth/me", async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({
      id: "00000000-0000-0000-0000-000000000001",
      username: "admin-a", displayName: "管理员甲", enabled: true,
      roles: ["OPERATOR", "PUBLISHER", "ADMIN"], mustChangePassword: false,
    }) });
  });

  await page.goto("/");

  await expect(page.getByRole("button", { name: "当前用户：管理员甲" }).first()).toBeVisible();
  await expect(page.getByText("管理员甲")).toBeVisible();
  await expect(page.getByText("管理员", { exact: true })).toBeVisible();
  await expect(page.getByText("曹征")).toHaveCount(0);
});

test("shell shows an operator identity without fabricating roles", async ({ page }) => {
  await page.route("**/api/v1/auth/me", async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({
      id: "00000000-0000-0000-0000-000000000002",
      username: "op-b", displayName: "运营员乙", enabled: true,
      roles: ["OPERATOR"], mustChangePassword: false,
    }) });
  });

  await page.goto("/");

  await expect(page.getByRole("button", { name: "当前用户：运营员乙" }).first()).toBeVisible();
  await expect(page.getByText("运营员乙")).toBeVisible();
  await expect(page.getByText("运营", { exact: true })).toBeVisible();
  await expect(page.getByText("管理员", { exact: true })).toHaveCount(0);
});

test("shell falls back to a neutral identity without a session", async ({ page }) => {
  await page.route("**/api/v1/auth/me", async (route) => {
    await route.fulfill({ status: 401, contentType: "application/problem+json", body: JSON.stringify({ detail: "unauthorized", code: "authentication-failed", traceId: "t" }) });
  });

  await page.goto("/");

  await expect(page.getByText("未识别身份").first()).toBeVisible();
  await expect(page.getByText("曹征")).toHaveCount(0);
  await expect(page.getByText("管理员", { exact: true })).toHaveCount(0);
});

test("skill library lists templates and instances and supports the real write flow", async ({ page }) => {
  const csrf = { headerName: "X-XSRF-TOKEN", parameterName: "_csrf", token: "e2e-csrf" };
  const manifest = '{"schemaVersion":"1.0","executionMode":"RESOURCE_ONLY","resources":["rules.json"]}';
  const mk = (id: string, name: string, kind: string, extra: Record<string, unknown> = {}) => ({
    id, name, kind, description: "", sceneId: null, sourceSkillId: null, sourceSkillVersionId: null,
    version: 1, packageHash: "a".repeat(64), manifestJson: manifest, createdAt: "2026-08-03T08:00:00Z", ...extra,
  });
  let skills = [
    mk("tpl-1", "规则萃取模板", "TEMPLATE"),
    mk("ins-1", "对公贷款·规则萃取", "INSTANCE", { sceneId: "scene-1", sourceSkillId: "tpl-1", sourceSkillVersionId: "sv-1", version: 2 }),
  ];
  const createBodies: Record<string, unknown>[] = [];
  const forkBodies: Record<string, unknown>[] = [];
  const versionBodies: Record<string, unknown>[] = [];

  await page.route("**/api/v1/auth/me", async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({
      id: "u-1", username: "admin-s", displayName: "管理员甲", enabled: true,
      roles: ["ADMIN"], mustChangePassword: false,
    }) });
  });
  await page.route("**/api/v1/auth/csrf", async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(csrf) });
  });
  await page.route("**/api/v1/skills?*", async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(skills) });
  });
  await page.route("**/api/v1/scenes?*", async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({
      items: [{ id: "scene-1", name: "对公贷款五级分类", description: "", createdAt: "2026-08-01T08:00:00Z", updatedAt: "2026-08-01T08:00:00Z" }],
      page: 0, size: 20, total: 1,
    }) });
  });
  await page.route("**/api/v1/skills", async (route) => {
    if (route.request().method() === "GET") {
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(skills) });
      return;
    }
    const body = route.request().postDataJSON();
    expect(route.request().headers()["idempotency-key"]).toMatch(/^[0-9a-f-]{8,}$/);
    createBodies.push(body);
    const created = mk("tpl-2", body.name, "TEMPLATE");
    skills = [created, ...skills];
    await route.fulfill({ status: 201, contentType: "application/json", body: JSON.stringify(created) });
  });
  await page.route("**/api/v1/skills/tpl-1/instances", async (route) => {
    const body = route.request().postDataJSON();
    forkBodies.push(body);
    const created = mk("ins-2", "规则萃取模板", "INSTANCE", { sceneId: body.sceneId, sourceSkillId: "tpl-1", sourceSkillVersionId: "sv-1" });
    skills = [created, ...skills];
    await route.fulfill({ status: 201, contentType: "application/json", body: JSON.stringify(created) });
  });
  await page.route("**/api/v1/skills/ins-1/versions", async (route) => {
    const body = route.request().postDataJSON();
    versionBodies.push(body);
    skills = skills.map((skill) => skill.id === "ins-1" ? { ...skill, version: 3, packageHash: body.packageHash, manifestJson: body.manifest } : skill);
    await route.fulfill({ status: 201, contentType: "application/json", body: JSON.stringify({ id: "ver-3", skillId: "ins-1", version: 3, manifestJson: body.manifest, packageHash: body.packageHash, createdBy: "u-1", createdAt: "2026-08-03T10:00:00Z" }) });
  });

  await page.goto("/skills");

  // Real list: template + instance with lineage/scene info.
  await expect(page.getByText("规则萃取模板")).toBeVisible();
  await expect(page.getByText("对公贷款·规则萃取")).toBeVisible();
  await expect(page.getByText(/v2/)).toBeVisible();
  await expect(page.getByText("tpl-1", { exact: true })).toBeVisible();

  // ADMIN creates a template: POST carries the resource-only manifest.
  await page.getByRole("button", { name: "新建模板" }).click();
  await page.getByLabel("名称").fill("合规萃取模板");
  await page.getByLabel("Manifest").fill(manifest);
  await page.getByLabel("包哈希").fill("b".repeat(64));
  await page.getByRole("button", { name: "保存" }).click();
  await expect(page.getByText("合规萃取模板")).toBeVisible();
  expect(createBodies).toHaveLength(1);
  expect(createBodies[0]).toMatchObject({ name: "合规萃取模板", manifest, packageHash: "b".repeat(64) });

  // Fork an instance with a scene: only the target scene is required.
  await page.locator("article").filter({ hasText: "规则萃取模板" }).getByRole("button", { name: "复制为实例" }).click();
  await expect(page.getByRole("dialog")).toContainText("对公贷款五级分类");
  await page.getByRole("button", { name: "创建实例" }).click();
  await expect.poll(() => forkBodies.length).toBe(1);
  expect(forkBodies[0]).toEqual({ sceneId: "scene-1" });

  // Instance creates a new immutable version.
  await page.locator("article").filter({ hasText: "对公贷款·规则萃取" }).getByRole("button", { name: "创建新版本" }).click();
  await page.getByLabel("Manifest").fill(manifest);
  await page.getByLabel("包哈希").fill("d".repeat(64));
  await page.getByRole("button", { name: "保存" }).click();
  await expect.poll(() => versionBodies.length).toBe(1);
  expect(versionBodies[0]).toMatchObject({ manifest, packageHash: "d".repeat(64) });

  // Read-only manifest dialog.
  await page.locator("article").filter({ hasText: "对公贷款·规则萃取" }).getByRole("button", { name: "查看 Manifest" }).click();
  await expect(page.getByRole("dialog").getByText("RESOURCE_ONLY").first()).toBeVisible();
});

test("skill page shows admin-only template creation and a 403 error state", async ({ page }) => {
  await page.route("**/api/v1/auth/me", async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({
      id: "u-2", username: "op-s", displayName: "运营员", enabled: true,
      roles: ["OPERATOR"], mustChangePassword: false,
    }) });
  });
  await page.route("**/api/v1/skills?*", async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify([{
      id: "tpl-1", name: "规则萃取模板", kind: "TEMPLATE", description: "", sceneId: null,
      sourceSkillId: null, sourceSkillVersionId: null, version: 1, packageHash: "a".repeat(64),
      manifestJson: '{"executionMode":"RESOURCE_ONLY"}', createdAt: "2026-08-03T08:00:00Z",
    }]) });
  });
  await page.route("**/api/v1/skills", async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify([{
      id: "tpl-1", name: "规则萃取模板", kind: "TEMPLATE", description: "", sceneId: null,
      sourceSkillId: null, sourceSkillVersionId: null, version: 1, packageHash: "a".repeat(64),
      manifestJson: '{"executionMode":"RESOURCE_ONLY"}', createdAt: "2026-08-03T08:00:00Z",
    }]) });
  });

  await page.goto("/skills");

  // OPERATOR cannot create templates (ADMIN only) but can fork instances.
  await expect(page.getByRole("button", { name: "新建模板" })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "复制为实例" })).toBeVisible();
});
