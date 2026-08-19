import { describe, expect, it } from "vitest";
import { formatPhone, isValidPhone, maskPhone, normalizePhone } from "./phone";

describe("phone", () => {
  it("нормализация как в v1", () => {
    expect(normalizePhone("+7 (916) 123-45-67")).toBe("79161234567");
    expect(normalizePhone("8 916 123 45 67")).toBe("79161234567");
    expect(normalizePhone("9161234567")).toBe("79161234567");
    expect(normalizePhone("")).toBe("");
    expect(isValidPhone("79161234567")).toBe(true);
    expect(isValidPhone("7916123456")).toBe(false);
  });
  it("маска по мере ввода", () => {
    expect(formatPhone("")).toBe("");
    expect(formatPhone("9")).toBe("+7 (9");
    expect(formatPhone("916")).toBe("+7 (916)");
    expect(formatPhone("9161")).toBe("+7 (916) 1");
    expect(formatPhone("89161234567")).toBe("+7 (916) 123-45-67");
    expect(formatPhone("+7 916 123 45 67 89")).toBe("+7 (916) 123-45-67");
    expect(formatPhone("7")).toBe("+7");
  });
  it("маска для журналов", () => {
    expect(maskPhone("79161234567")).toBe("+7 9** *** ** 67");
  });
});
