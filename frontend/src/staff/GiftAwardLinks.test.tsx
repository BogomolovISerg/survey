import { describe, expect, it } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import { MemoryRouter } from "react-router";
import { GiftAwardLinks } from "./GiftAwardLinks";
import type { EventSummary } from "../api/client";

const base: EventSummary = { id: "event-1", name: "Мероприятие", giftEnabled: true, active: true, version: 1, publishedAt: "", publicUrl: "" };
describe("Кнопки выдачи для мероприятия", () => {
  for (const navigation of [false, true]) {
    for (const mode of ["questionnaire", "manual", "both", undefined] as const) {
      it(`отображает ${mode ?? "старое значение"}, меню: ${navigation}`, () => {
        const html = renderToStaticMarkup(<MemoryRouter><GiftAwardLinks event={{ ...base, giftAwardMode: mode }} navigation={navigation} /></MemoryRouter>);
        expect(html.includes("Выдача с анкетой")).toBe(mode !== "manual");
        expect(html.includes("Выдача без анкеты")).toBe(mode !== "questionnaire");
        expect(html).toContain("?event=event-1");
      });
    }
    it(`скрывает выдачу без подарков и до загрузки, меню: ${navigation}`, () => {
      expect(renderToStaticMarkup(<MemoryRouter><GiftAwardLinks event={{ ...base, giftEnabled: false }} navigation={navigation} /></MemoryRouter>)).toBe("");
      expect(renderToStaticMarkup(<MemoryRouter><GiftAwardLinks navigation={navigation} /></MemoryRouter>)).toBe("");
    });
  }
});
