import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError, changePassword, login } from "./api";

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
