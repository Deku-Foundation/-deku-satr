import { describe, expect, it } from "vitest";
import { dilateMask, thresholdMask, unionMasks } from "../src/mask.js";

describe("thresholdMask", () => {
  it("converts grayscale values to a binary alpha mask", () => {
    expect([...thresholdMask([0, 40, 100, 200, 255], 0.35)]).toEqual([0, 0, 255, 255, 255]);
  });
});
describe("dilateMask", () => {
  it("expands a foreground pixel without changing dimensions", () => {
    const input = new Uint8Array(25);
    input[12] = 255;
    const output = dilateMask(input, 5, 5, 1);
    expect(output).toHaveLength(25);
    expect([...output].filter((x) => x > 0)).toHaveLength(9);
    expect(output[6]).toBe(255);
    expect(output[0]).toBe(0);
  });
  it("normalizes a mask with zero radius", () => {
    expect([...dilateMask(new Uint8Array([0, 1, 255]), 3, 1, 0)]).toEqual([0, 255, 255]);
  });
  it("rejects inconsistent dimensions", () => {
    expect(() => dilateMask(new Uint8Array(3), 2, 2, 1)).toThrow(RangeError);
  });
});
describe("unionMasks", () => {
  it("combines foreground from multiple masks", () => {
    expect([...unionMasks([new Uint8Array([0, 255, 0]), new Uint8Array([255, 0, 0])], 3, 1)])
      .toEqual([255, 255, 0]);
  });
});