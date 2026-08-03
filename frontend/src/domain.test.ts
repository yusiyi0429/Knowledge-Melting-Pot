import { describe, expect, it } from "vitest";
import type { Asset, Subscene } from "./domain";
import { releaseCanInclude, toStatusTone } from "./domain";

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
