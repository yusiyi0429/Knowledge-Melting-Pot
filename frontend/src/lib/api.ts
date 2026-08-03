export type AuthenticatedUser = {
  id: string;
  username: string;
  displayName: string;
  enabled: boolean;
  roles: Array<"OPERATOR" | "PUBLISHER" | "ADMIN">;
  mustChangePassword: boolean;
};

type CsrfMetadata = {
  headerName: string;
  parameterName: string;
  token: string;
};

type Problem = {
  detail?: string;
  code?: string;
  traceId?: string;
};

export class ApiError extends Error {
  readonly status: number;
  readonly code?: string;
  readonly traceId?: string;

  constructor(message: string, status: number, problem?: Problem) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.code = problem?.code;
    this.traceId = problem?.traceId;
  }
}

async function readProblem(response: Response): Promise<Problem | undefined> {
  const contentType = response.headers.get("content-type") ?? "";
  if (!contentType.includes("application/problem+json") && !contentType.includes("application/json")) {
    return undefined;
  }
  try {
    return (await response.json()) as Problem;
  } catch {
    return undefined;
  }
}

async function requireSuccess(response: Response, fallback: string): Promise<Response> {
  if (response.ok) return response;
  const problem = await readProblem(response);
  throw new ApiError(problem?.detail || fallback, response.status, problem);
}

async function csrf(): Promise<CsrfMetadata> {
  const response = await requireSuccess(
    await fetch("/api/v1/auth/csrf", { credentials: "same-origin", cache: "no-store" }),
    "无法获取安全令牌。",
  );
  const metadata = (await response.json()) as CsrfMetadata;
  if (!/^[A-Za-z0-9-]{1,64}$/.test(metadata.headerName) || !metadata.token) {
    throw new ApiError("服务端返回了无效的安全令牌。", 502);
  }
  return metadata;
}

async function postWithCsrf(path: string, body: unknown): Promise<Response> {
  const token = await csrf();
  return fetch(path, {
    method: "POST",
    credentials: "same-origin",
    cache: "no-store",
    headers: {
      "Content-Type": "application/json",
      [token.headerName]: token.token,
    },
    body: JSON.stringify(body),
  });
}

export async function login(username: string, password: string): Promise<AuthenticatedUser> {
  await requireSuccess(
    await postWithCsrf("/api/v1/auth/login", { username, password }),
    "登录失败，请检查账号或稍后重试。",
  );
  const response = await requireSuccess(
    await fetch("/api/v1/auth/me", { credentials: "same-origin", cache: "no-store" }),
    "已登录，但无法读取当前账号。",
  );
  return (await response.json()) as AuthenticatedUser;
}

export async function changePassword(currentPassword: string, newPassword: string): Promise<void> {
  await requireSuccess(
    await postWithCsrf("/api/v1/auth/password", { currentPassword, newPassword }),
    "密码修改失败，请检查当前密码和密码策略。",
  );
}
