import { describe, expect, it } from "vitest";
import { giftTokenFromScan } from "./giftToken";

describe("giftTokenFromScan", () => {
  it("ссылка панели стенда → токен", () => {
    expect(giftTokenFromScan("https://survey.bigcom.ru/survey/staff/gift?t=g.abc.123.sig")).toBe("g.abc.123.sig");
  });
  it("сам токен", () => {
    expect(giftTokenFromScan(" g.abc.123.sig ")).toBe("g.abc.123.sig");
  });
  it("чужой QR", () => {
    expect(giftTokenFromScan("https://example.com/")).toBeNull();
    expect(giftTokenFromScan("привет")).toBeNull();
    expect(giftTokenFromScan("https://survey.bigcom.ru/survey/e/062c")).toBeNull();
  });
});
