import { describe, expect, it } from "vitest";
import { fitStage } from "../src/fit-window";

describe("fitStage", () => {
  it("leaves a TV's 1920×1080 alone", () => {
    expect(fitStage(1920, 1080)).toBeNull();
    expect(fitStage(1919, 1081)).toBeNull();
  });
  it("shrinks into a smaller window of the same shape", () => {
    expect(fitStage(1280, 720)).toEqual({ scale: 2 / 3, x: 0, y: 0 });
  });
  it("centres in a taller window", () => {
    const fit = fitStage(1440, 900)!;
    expect(fit.scale).toBeCloseTo(0.75);
    expect(fit.x).toBeCloseTo(0);
    expect(fit.y).toBeCloseTo(45);
  });
  it("centres in a wider window", () => {
    const fit = fitStage(2000, 720)!;
    expect(fit.scale).toBeCloseTo(2 / 3);
    expect(fit.x).toBeCloseTo(360);
    expect(fit.y).toBeCloseTo(0);
  });
  it("grows into a bigger window", () => {
    expect(fitStage(3840, 2160)).toEqual({ scale: 2, x: 0, y: 0 });
  });
  it("ignores a window with no size yet", () => {
    expect(fitStage(0, 0)).toBeNull();
  });
});
