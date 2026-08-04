import { describe, expect, it } from "vitest";
import type { Asset, Subscene } from "./domain";
import {
  safeAuditDetails,
  MATERIAL_STATUS_LABELS,
  connectionTestSummary,
  connectionValidationLabel,
  mediaTypeForFile,
  modelProviderLabel,
  releaseCanInclude,
  toStatusTone,
  validateMaterialFile,
} from "./domain";

const subscene: Subscene = {
  id: "sub-1",
  name: "测试场景",
  description: "",
  revision: "rev-1",
  releaseState: "READY",
};

const readyAsset: Asset = {
  id: "asset-1",
  name: "规则",
  format: "JSON",
  description: "",
  state: "READY",
  version: "v1",
  sourceRevision: "rev-1",
};

describe("releaseCanInclude", () => {
  it("allows a ready subscene when every asset is ready", () => {
    expect(releaseCanInclude(subscene, [readyAsset])).toBe(true);
  });

  it("blocks a subscene with a blocked asset", () => {
    expect(releaseCanInclude(subscene, [{ ...readyAsset, state: "BLOCKED" }])).toBe(false);
  });

  it("blocks a subscene whose own release state is blocked", () => {
    expect(releaseCanInclude({ ...subscene, releaseState: "BLOCKED" }, [readyAsset])).toBe(false);
  });
});

describe("toStatusTone", () => {
  it("maps operational states to stable UI tones", () => {
    expect(toStatusTone("READY")).toBe("success");
    expect(toStatusTone("EXTRACTING")).toBe("info");
    expect(toStatusTone("BLOCKED")).toBe("warning");
    expect(toStatusTone("FAILED")).toBe("danger");
  });
});

describe("model connection display helpers", () => {
  it("labels the supported providers", () => {
    expect(modelProviderLabel("DASHSCOPE")).toBe("DashScope");
    expect(modelProviderLabel("OPENAI_COMPATIBLE")).toBe("OpenAI 兼容");
  });

  it("labels the validation status returned by the API", () => {
    expect(connectionValidationLabel("UNTESTED")).toBe("未验证");
    expect(connectionValidationLabel("CONNECTIVITY_VERIFIED")).toBe("连通已验证");
    expect(toStatusTone("CONNECTIVITY_VERIFIED")).toBe("success");
  });

  it("renders a failed network test without claiming provider connectivity", () => {
    const summary = connectionTestSummary({ networkAttempted: false, connectivityVerified: false });

    expect(summary.label).toBe("连接测试未通过");
    expect(summary.note).toContain("未发起网络请求");
    expect(summary.note).toContain("未能验证 Provider 连通性");
  });

  it("follows the flags if a future backend does verify connectivity", () => {
    const summary = connectionTestSummary({ networkAttempted: true, connectivityVerified: true });

    expect(summary.label).toBe("连通已验证");
    expect(summary.note).toContain("已发起网络请求");
    expect(summary.note).toContain("已确认 Provider 与凭据可用");
  });
});

describe("material file helpers", () => {
  it("derives canonical media types from allowed extensions only", () => {
    expect(mediaTypeForFile("a.pdf")).toBe("application/pdf");
    expect(mediaTypeForFile("b.DOCX")).toBe("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    expect(mediaTypeForFile("c.xlsx")).toBe("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    expect(mediaTypeForFile("d.txt")).toBe("text/plain");
    expect(mediaTypeForFile("e.doc")).toBeNull();
    expect(mediaTypeForFile("f.xls")).toBeNull();
    expect(mediaTypeForFile("g.exe")).toBeNull();
    expect(mediaTypeForFile("noextension")).toBeNull();
  });

  it("rejects unsupported, empty, and oversized files", () => {
    expect(validateMaterialFile({ name: "a.doc", size: 10 })).toContain("DOC");
    expect(validateMaterialFile({ name: "a.pdf", size: 0 })).toContain("0 字节");
    expect(validateMaterialFile({ name: "a.pdf", size: 201 * 1024 * 1024 })).toContain("200MB");
    expect(validateMaterialFile({ name: "a.pdf", size: 100 })).toBeNull();
    expect(validateMaterialFile({ name: "a.txt", size: 200 * 1024 * 1024 })).toBeNull();
  });
});

describe("safeAuditDetails", () => {
  it("keeps only allowlisted scalar metadata", () => {
    const details = safeAuditDetails(JSON.stringify({
      tag: "v1.0",
      manifestHash: "m123",
      sceneId: "sc-1",
      secretKey: "super-secret",
      credential: "sk-xxx",
      name: "自由文本名称",
      baseUrl: "https://internal.example/v1",
      nested: { tag: "leaked" },
    }));

    expect(details).toContainEqual({ key: "tag", value: "v1.0" });
    expect(details).toContainEqual({ key: "manifestHash", value: "m123" });
    expect(details.map((item) => item.key)).toEqual(
      expect.not.arrayContaining(["secretKey", "credential", "name", "baseUrl"]),
    );
  });

  it("returns nothing for malformed JSON or non-object payloads", () => {
    expect(safeAuditDetails("not json")).toEqual([]);
    expect(safeAuditDetails("[]")).toEqual([]);
    expect(safeAuditDetails("\"plain\"")).toEqual([]);
    expect(safeAuditDetails("null")).toEqual([]);
  });
});
