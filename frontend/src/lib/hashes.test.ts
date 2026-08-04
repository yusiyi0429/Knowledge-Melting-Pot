import { describe, expect, it } from "vitest";
import { sha256Hex } from "./hashes";

describe("sha256Hex", () => {
  it("computes the well-known digest for a fixed input", async () => {
    const digest = await sha256Hex(new TextEncoder().encode("abc").buffer as ArrayBuffer);
    expect(digest).toBe("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
  });
});
