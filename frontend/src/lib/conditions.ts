/**
 * Безопасный интерпретатор условий показа элементов анкеты (порт conditions.js из v1, без eval).
 *
 * Строка:  Город == "Москва" || (Статус != "Другое" && !Согласие)
 *   операторы == != === !== > >= < <=, || &&, !, скобки; литералы "строка"/'строка', числа, true/false;
 *   идентификаторы — имена полей ответов (русские/латинские буквы, цифры, _).
 * Объект:  { field, op, value } | { any: [...] } | { all: [...] } | { not: ... }
 * Пустое условие → виден. Синтаксическая ошибка → виден (потерять поле хуже, чем показать лишнее).
 */

export type Answers = Record<string, unknown>;
export type Condition = string | ConditionObject | null | undefined;
export interface ConditionObject {
  field?: string;
  op?: string;
  value?: unknown;
  any?: Condition[];
  all?: Condition[];
  not?: Condition;
}

type Node =
  | { type: "field"; name: string }
  | { type: "literal"; value: unknown }
  | { type: "not"; operand: Node }
  | { type: "and"; left: Node; right: Node }
  | { type: "or"; left: Node; right: Node }
  | { type: "compare"; op: string; left: Node; right: Node };

type Token = { type: "string" | "number" | "boolean" | "ident" | "op"; value: unknown };

const IDENT_START = /[A-Za-zА-Яа-яЁё_]/;
const IDENT_CHAR = /[A-Za-zА-Яа-яЁё0-9_]/;
const DIGIT = /[0-9]/;
const COMPARE_OPS = ["==", "!=", ">", ">=", "<", "<="];

export function tokenize(src: string): Token[] {
  const tokens: Token[] = [];
  const s = String(src);
  let i = 0;
  while (i < s.length) {
    const ch = s[i];
    if (ch === " " || ch === "\t" || ch === "\n" || ch === "\r") {
      i++;
      continue;
    }
    if (ch === '"' || ch === "'") {
      const quote = ch;
      let j = i + 1;
      let value = "";
      while (j < s.length && s[j] !== quote) {
        if (s[j] === "\\" && j + 1 < s.length) {
          value += s[j + 1];
          j += 2;
        } else {
          value += s[j];
          j++;
        }
      }
      if (j >= s.length) throw new SyntaxError(`Незакрытая строка начиная с позиции ${i}`);
      tokens.push({ type: "string", value });
      i = j + 1;
      continue;
    }
    if (DIGIT.test(ch)) {
      let j = i;
      while (j < s.length && (DIGIT.test(s[j]) || s[j] === ".")) j++;
      tokens.push({ type: "number", value: Number(s.slice(i, j)) });
      i = j;
      continue;
    }
    if (IDENT_START.test(ch)) {
      let j = i;
      while (j < s.length && IDENT_CHAR.test(s[j])) j++;
      const word = s.slice(i, j);
      if (word === "true" || word === "false") tokens.push({ type: "boolean", value: word === "true" });
      else tokens.push({ type: "ident", value: word });
      i = j;
      continue;
    }
    const three = s.slice(i, i + 3);
    const two = s.slice(i, i + 2);
    if (three === "===" || three === "!==") {
      tokens.push({ type: "op", value: three.slice(0, 2) });
      i += 3;
      continue;
    }
    if (["==", "!=", ">=", "<=", "||", "&&"].includes(two)) {
      tokens.push({ type: "op", value: two });
      i += 2;
      continue;
    }
    if (ch === ">" || ch === "<" || ch === "!" || ch === "(" || ch === ")") {
      tokens.push({ type: "op", value: ch });
      i++;
      continue;
    }
    throw new SyntaxError(`Неожиданный символ «${ch}» в позиции ${i}`);
  }
  return tokens;
}

export function parse(src: string): Node {
  const tokens = tokenize(src);
  let pos = 0;
  const peek = () => tokens[pos];
  const next = () => tokens[pos++];
  const isOp = (v: string) => peek() !== undefined && peek().type === "op" && peek().value === v;

  const primary = (): Node => {
    const t = next();
    if (!t) throw new SyntaxError("Неожиданный конец условия");
    if (t.type === "ident") return { type: "field", name: t.value as string };
    if (t.type === "string" || t.type === "number" || t.type === "boolean") return { type: "literal", value: t.value };
    if (t.type === "op" && t.value === "(") {
      const e = or();
      if (!isOp(")")) throw new SyntaxError("Ожидалась закрывающая скобка");
      next();
      return e;
    }
    throw new SyntaxError(`Неожиданный токен «${String(t.value)}»`);
  };
  const compare = (): Node => {
    const left = primary();
    const t = peek();
    if (t && t.type === "op" && COMPARE_OPS.includes(t.value as string)) {
      const op = next().value as string;
      return { type: "compare", op, left, right: primary() };
    }
    return left;
  };
  const unary = (): Node => {
    if (isOp("!")) {
      next();
      return { type: "not", operand: unary() };
    }
    return compare();
  };
  const and = (): Node => {
    let left = unary();
    while (isOp("&&")) {
      next();
      left = { type: "and", left, right: unary() };
    }
    return left;
  };
  const or = (): Node => {
    let left = and();
    while (isOp("||")) {
      next();
      left = { type: "or", left, right: and() };
    }
    return left;
  };
  const ast = or();
  if (pos < tokens.length) throw new SyntaxError(`Лишний токен «${String(tokens[pos].value)}» после конца условия`);
  return ast;
}

export const truthy = (v: unknown): boolean => {
  if (Array.isArray(v)) return v.length > 0;
  if (typeof v === "string") return v !== "" && v.toLowerCase() !== "false";
  return Boolean(v);
};

const looseEquals = (a: unknown, b: unknown): boolean => {
  if (Array.isArray(a)) return a.some((x) => looseEquals(x, b));
  if (Array.isArray(b)) return b.some((x) => looseEquals(a, x));
  if (typeof a === "boolean" || typeof b === "boolean") return truthy(a) === truthy(b);
  if (a === undefined || a === null) a = "";
  if (b === undefined || b === null) b = "";
  return String(a) === String(b);
};

const numeric = (v: unknown): number | null => {
  const n = typeof v === "number" ? v : parseFloat(String(v).replace(",", "."));
  return Number.isNaN(n) ? null : n;
};

function evalNode(node: Node, values: Answers | undefined): unknown {
  switch (node.type) {
    case "field":
      return values ? values[node.name] : undefined;
    case "literal":
      return node.value;
    case "not":
      return !truthy(evalNode(node.operand, values));
    case "and":
      return truthy(evalNode(node.left, values)) && truthy(evalNode(node.right, values));
    case "or":
      return truthy(evalNode(node.left, values)) || truthy(evalNode(node.right, values));
    case "compare": {
      const l = evalNode(node.left, values);
      const r = evalNode(node.right, values);
      if (node.op === "==") return looseEquals(l, r);
      if (node.op === "!=") return !looseEquals(l, r);
      const ln = numeric(l);
      const rn = numeric(r);
      if (ln === null || rn === null) return false;
      if (node.op === ">") return ln > rn;
      if (node.op === ">=") return ln >= rn;
      if (node.op === "<") return ln < rn;
      return ln <= rn;
    }
  }
}

function fromObject(cond: Condition): Node {
  if (typeof cond === "string") return parse(cond);
  if (!cond || typeof cond !== "object") throw new SyntaxError("Неизвестная форма условия");
  if (Array.isArray(cond.any)) {
    return (
      cond.any.map(fromObject).reduce<Node | null>((acc, n) => (acc ? { type: "or", left: acc, right: n } : n), null) ?? {
        type: "literal",
        value: true,
      }
    );
  }
  if (Array.isArray(cond.all)) {
    return (
      cond.all.map(fromObject).reduce<Node | null>((acc, n) => (acc ? { type: "and", left: acc, right: n } : n), null) ?? {
        type: "literal",
        value: true,
      }
    );
  }
  if (cond.not !== undefined) return { type: "not", operand: fromObject(cond.not) };
  if (typeof cond.field === "string") {
    const op = cond.op || "==";
    if (!COMPARE_OPS.includes(op)) throw new SyntaxError(`Неизвестный оператор «${op}»`);
    return { type: "compare", op, left: { type: "field", name: cond.field }, right: { type: "literal", value: cond.value } };
  }
  throw new SyntaxError("Неизвестная форма условия");
}

const cache = new Map<string, Node>();

export function compile(condition: Condition): Node | null {
  if (condition === undefined || condition === null || condition === "") return null;
  if (typeof condition === "string") {
    if (condition.trim() === "") return null;
    let ast = cache.get(condition);
    if (!ast) {
      ast = parse(condition);
      cache.set(condition, ast);
    }
    return ast;
  }
  if (typeof condition === "object") return fromObject(condition);
  throw new SyntaxError("Условие должно быть строкой или объектом");
}

export function evaluate(condition: Condition, values: Answers | undefined, onError?: (err: Error, condition: Condition) => void): boolean {
  let ast: Node | null;
  try {
    ast = compile(condition);
  } catch (err) {
    if (onError) onError(err as Error, condition);
    return true;
  }
  if (ast === null) return true;
  return truthy(evalNode(ast, values));
}

/** Видимость элемента/компонента анкеты по его свойству conditions. */
export function isVisible(item: { conditions?: Condition } | null | undefined, values: Answers | undefined): boolean {
  if (!item || typeof item !== "object") return true;
  return evaluate(item.conditions, values, (err, cond) => {
    console.warn(`Некорректное условие показа «${String(cond)}»: ${err.message}. Элемент показан.`);
  });
}
