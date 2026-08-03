import { expect, test } from "@playwright/test";

test("walks through the auditable extraction and partial-release prototype", async ({ page }) => {
  const consoleErrors: string[] = [];
  page.on("console", (message) => {
    if (message.type() === "error") consoleErrors.push(message.text());
  });

  await page.goto("/login");
  await expect(page).toHaveTitle("知识萃取智能体工作台");
  await page.getByRole("button", { name: "进入离线演示" }).click();

  await expect(page.getByRole("heading", { name: "知识正在形成可追溯的资产" })).toBeVisible();
  await page.getByRole("button", { name: "打开对公贷款五级分类" }).click();
  await expect(page).toHaveURL(/\/scenes\/corporate-loan-classification$/);
  await expect(page.getByText("留出评测永不进入 Prompt、检索或 QA 生成")).toBeVisible();

  await page.getByRole("button", { name: "进入知识萃取" }).click();
  await expect(page.getByRole("textbox", { name: "知识文档 Markdown" })).toHaveValue(/\[SRC-001\]/);
  await page.getByRole("button", { name: "演示萃取 Job" }).click();
  await expect(page.getByRole("progressbar", { name: "任务进度" })).toHaveAttribute("value", "100", {
    timeout: 7_000,
  });
  await expect(page.getByText("completed", { exact: true })).toBeVisible();

  await page.getByRole("button", { name: "查看生成资产" }).click();
  await expect(page.getByRole("heading", { name: "评测集" })).toBeVisible();
  await expect(page.getByText("当前子场景未绑定足够的留出标注案例")).toBeVisible();
  await expect(page.getByText("发布预检通过，将冻结内容和配置哈希。")).toBeVisible();
  await expect(page.getByRole("button", { name: "发布选中范围" })).toBeEnabled();
  expect(consoleErrors).toEqual([]);
});

test("serves application deep links and governance routes", async ({ page }) => {
  const routes = [
    ["/agents", "七种角色，一条显式工作流"],
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
