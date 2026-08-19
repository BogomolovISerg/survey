import { describe, expect, it } from "vitest";
import { buildPayload, isEmptyAnswer, parseSchema, refName, SchemaError } from "./schema";

const input = {
  enums: { Города: ["Москва", "Другой..."], Интерес: ["по работе", "для себя"] },
  components: {
    Идентификация: {
      conditions: "",
      required: ["Имя", "Телефон", "Город"],
      data: [
        { Имя: { label: "Имя", element: "inputbox" } },
        { Телефон: { label: "Телефон", element: "inputbox", mask: "phone" } },
        { Город: { label: "Город", element: "radio", type: "enum", values: "Города" } },
        { Метро: { conditions: 'Город == "Москва"', label: "Метро", element: "inputbox" } },
      ],
    },
    Интерес: { data: [{ Интерес: { element: "radio", type: "enum", values: "Интерес" } }] },
    Бренды: { data: [{ Бренды: { element: "checkbox", type: "enum", values: "Интерес" } }, { Согласие: { element: "checkbox", type: "boolean" } }] },
    Обёртка: { data: ["{Бренды}"] },
  },
  schema: ["{Идентификация}", "{Интерес}", "{Обёртка}"],
  style: { theme: "dark" },
  output: { Имя: "", Телефон: "", Город: "", Метро: "", Интерес: "", Бренды: "", Согласие: "" },
  event: { id: "062c", name: "Тест", gift: true, active: true },
  version: 3,
};

describe("schema", () => {
  it("разбирает схему v1 и разворачивает ссылки", () => {
    const s = parseSchema(input);
    expect(s.order.map((o) => o.name)).toEqual(["Идентификация", "Интерес", "Обёртка"]);
    expect(s.enums.Города).toEqual(["Москва", "Другой..."]);
    expect(s.styles).toEqual({ theme: "dark" });
    expect(s.event).toEqual({ id: "062c", name: "Тест", gift: true, active: true, theme: undefined });
    expect(s.version).toBe(3);
    const wrapper = s.components["Обёртка"].data[0];
    expect("component" in wrapper && wrapper.component).toBe("Бренды");
  });
  it("нормализует output для чекбоксов", () => {
    const s = parseSchema(input);
    expect(s.output.Бренды).toEqual([]);
    expect(s.output.Согласие).toBe(false);
    expect(s.output.Имя).toBe("");
  });
  it("ошибки контракта", () => {
    expect(() => parseSchema({ components: {}, schema: [] })).toThrow(SchemaError);
    expect(() => parseSchema({ components: { A: {} }, schema: [] })).toThrow(/порядок/);
    expect(() => parseSchema({ components: { A: {} }, schema: ["{B}"] })).toThrow(/неизвестный/);
    expect(() => parseSchema({ components: { A: { data: ["{X}"] } }, schema: ["{A}"] })).toThrow(/X/);
    expect(() => parseSchema({ message: "Мероприятие завершено" })).toThrow("Мероприятие завершено");
    expect(() => parseSchema("nope")).toThrow(SchemaError);
  });
  it("payload: массивы через запятую", () => {
    expect(buildPayload({ a: ["x", "y"], b: "z", c: true })).toEqual({ a: "x, y", b: "z", c: true });
  });
  it("refName и isEmptyAnswer", () => {
    expect(refName("{Идентификация}")).toBe("Идентификация");
    expect(refName(" { Comp_1 } ")).toBe("Comp_1");
    expect(isEmptyAnswer("")).toBe(true);
    expect(isEmptyAnswer("  ")).toBe(true);
    expect(isEmptyAnswer([])).toBe(true);
    expect(isEmptyAnswer(false)).toBe(true);
    expect(isEmptyAnswer("x")).toBe(false);
    expect(isEmptyAnswer(["x"])).toBe(false);
    expect(isEmptyAnswer(0)).toBe(false);
  });
});
