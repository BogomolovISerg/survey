import { describe, expect, it } from "vitest";
import { evaluate, isVisible, parse, tokenize } from "./conditions";

describe("conditions", () => {
  it("пустое условие — виден", () => {
    expect(evaluate(undefined, {})).toBe(true);
    expect(evaluate("", {})).toBe(true);
    expect(evaluate("  ", {})).toBe(true);
    expect(evaluate(null, {})).toBe(true);
  });
  it("сравнения строк", () => {
    expect(evaluate('Город == "Москва"', { Город: "Москва" })).toBe(true);
    expect(evaluate("Город == 'Москва'", { Город: "Питер" })).toBe(false);
    expect(evaluate('Город != "Москва"', { Город: "Питер" })).toBe(true);
    expect(evaluate('Город != "Москва"', {})).toBe(true);
    expect(evaluate('Город === "Москва"', { Город: "Москва" })).toBe(true);
    expect(evaluate('Город !== "Москва"', { Город: "Москва" })).toBe(false);
  });
  it("логика и скобки", () => {
    const a = { Статус: "Работаю в салоне", Интерес: "по работе" };
    expect(evaluate('Статус == "Работаю в салоне" || Статус == "Администратор"', a)).toBe(true);
    expect(evaluate('Статус == "Другое" && Интерес == "по работе"', a)).toBe(false);
    expect(evaluate('(Статус == "Другое" || Интерес == "по работе") && !Пусто', a)).toBe(true);
    expect(evaluate('!(Интерес == "по работе")', a)).toBe(false);
  });
  it("массивы — «содержит»", () => {
    expect(evaluate('Бренды == "A"', { Бренды: ["B", "A"] })).toBe(true);
    expect(evaluate('Бренды == "C"', { Бренды: ["B", "A"] })).toBe(false);
    expect(evaluate("Бренды", { Бренды: [] })).toBe(false);
    expect(evaluate("Бренды", { Бренды: ["x"] })).toBe(true);
  });
  it("булевы и числа", () => {
    expect(evaluate("Согласие == true", { Согласие: true })).toBe(true);
    expect(evaluate("Согласие == true", { Согласие: "true" })).toBe(true);
    expect(evaluate("Согласие", { Согласие: "false" })).toBe(false);
    expect(evaluate("Возраст >= 18", { Возраст: "20" })).toBe(true);
    expect(evaluate("Возраст < 18", { Возраст: "20,5" })).toBe(false);
    expect(evaluate("Возраст > 1", { Возраст: "abc" })).toBe(false);
  });
  it("синтаксическая ошибка — виден, onError вызван", () => {
    let called = 0;
    expect(evaluate("Город == ", {}, () => called++)).toBe(true);
    expect(evaluate('Город ==== "x"', {}, () => called++)).toBe(true);
    expect(evaluate('Город == "незакрытая', {}, () => called++)).toBe(true);
    expect(called).toBe(3);
    expect(() => parse("Город == ")).toThrow(SyntaxError);
    expect(() => tokenize("a # b")).toThrow(SyntaxError);
  });
  it("структурированная форма", () => {
    const cond = { any: [{ field: "Город", op: "==", value: "Москва" }, { field: "Город", value: "Казань" }] };
    expect(evaluate(cond, { Город: "Казань" })).toBe(true);
    expect(evaluate(cond, { Город: "Тула" })).toBe(false);
    const not = { not: { all: [{ field: "A", value: "1" }, { field: "B", value: "2" }] } };
    expect(evaluate(not, { A: "1", B: "2" })).toBe(false);
    expect(evaluate(not, { A: "1" })).toBe(true);
    expect(evaluate({ field: "A", op: "~", value: 1 }, {})).toBe(true); // неизвестный оператор → виден
  });
  it("isVisible по свойству conditions", () => {
    expect(isVisible({ conditions: 'Город == "Москва"' }, { Город: "Москва" })).toBe(true);
    expect(isVisible({ conditions: 'Город == "Москва"' }, { Город: "Тверь" })).toBe(false);
    expect(isVisible({}, {})).toBe(true);
    expect(isVisible(null, {})).toBe(true);
  });
});
