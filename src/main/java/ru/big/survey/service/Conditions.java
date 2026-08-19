package ru.big.survey.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import tools.jackson.databind.JsonNode;

/**
 * Интерпретатор условий показа элементов анкеты — порт conditions.js из v1 (без eval).
 * Строка вида: Город == "Москва" || (Статус != "Другое" && !Согласие); операторы == != > >= < <=, || &&, !, скобки,
 * литералы "строка"/'строка', числа, true/false; идентификаторы — имена полей ответов.
 * Структурированная форма: {field, op, value} | {any:[…]} | {all:[…]} | {not: …}.
 * Пустое условие → true. Синтаксическая ошибка → true (потерять поле хуже, чем показать лишнее).
 */
public final class Conditions {

    private Conditions() {
    }

    private static final Map<String, Node> CACHE = new ConcurrentHashMap<>();

    /** Видимость элемента (компонента или поля) по его свойству conditions при данных ответах. */
    public static boolean isVisible(JsonNode item, JsonNode answers) {
        if (item == null || !item.isObject()) {
            return true;
        }
        return evaluate(item.get("conditions"), answers);
    }

    public static boolean evaluate(JsonNode condition, JsonNode answers) {
        Node ast;
        try {
            ast = compile(condition);
        } catch (RuntimeException e) {
            return true;
        }
        if (ast == null) {
            return true;
        }
        return truthy(ast.eval(answers));
    }

    static Node compile(JsonNode condition) {
        if (condition == null || condition.isNull() || condition.isMissingNode()) {
            return null;
        }
        if (condition.isString()) {
            String text = condition.stringValue();
            if (text.isBlank()) {
                return null;
            }
            return CACHE.computeIfAbsent(text, Conditions::parse);
        }
        if (condition.isObject()) {
            return fromObject(condition);
        }
        throw new IllegalArgumentException("Условие должно быть строкой или объектом");
    }

    // ---------- AST ----------

    sealed interface Node permits Field, Literal, Not, And, Or, Compare {
        Object eval(JsonNode answers);
    }

    record Field(String name) implements Node {
        public Object eval(JsonNode answers) {
            if (answers == null) {
                return null;
            }
            JsonNode v = answers.get(name);
            return v == null || v.isNull() ? null : v;
        }
    }

    record Literal(Object value) implements Node {
        public Object eval(JsonNode answers) { return value; }
    }

    record Not(Node operand) implements Node {
        public Object eval(JsonNode answers) { return !truthy(operand.eval(answers)); }
    }

    record And(Node left, Node right) implements Node {
        public Object eval(JsonNode answers) { return truthy(left.eval(answers)) && truthy(right.eval(answers)); }
    }

    record Or(Node left, Node right) implements Node {
        public Object eval(JsonNode answers) { return truthy(left.eval(answers)) || truthy(right.eval(answers)); }
    }

    record Compare(String op, Node left, Node right) implements Node {
        public Object eval(JsonNode answers) {
            Object l = left.eval(answers);
            Object r = right.eval(answers);
            switch (op) {
                case "==": return looseEquals(l, r);
                case "!=": return !looseEquals(l, r);
                default: {
                    Double ln = numeric(l);
                    Double rn = numeric(r);
                    if (ln == null || rn == null) {
                        return false;
                    }
                    return switch (op) {
                        case ">" -> ln > rn;
                        case ">=" -> ln >= rn;
                        case "<" -> ln < rn;
                        default -> ln <= rn;
                    };
                }
            }
        }
    }

    // ---------- семантика значений (как в v1) ----------

    static boolean truthy(Object v) {
        if (v == null) {
            return false;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof Number n) {
            return n.doubleValue() != 0;
        }
        if (v instanceof String s) {
            return !s.isEmpty() && !s.equalsIgnoreCase("false");
        }
        if (v instanceof JsonNode n) {
            if (n.isNull() || n.isMissingNode()) {
                return false;
            }
            if (n.isArray()) {
                return n.size() > 0;
            }
            if (n.isBoolean()) {
                return n.asBoolean();
            }
            if (n.isNumber()) {
                return n.asDouble() != 0;
            }
            if (n.isString()) {
                String s = n.stringValue();
                return !s.isEmpty() && !s.equalsIgnoreCase("false");
            }
            return true;
        }
        return true;
    }

    private static boolean looseEquals(Object a, Object b) {
        if (a instanceof JsonNode an && an.isArray()) {
            for (JsonNode x : an) {
                if (looseEquals(x, b)) {
                    return true;
                }
            }
            return false;
        }
        if (b instanceof JsonNode bn && bn.isArray()) {
            for (JsonNode x : bn) {
                if (looseEquals(a, x)) {
                    return true;
                }
            }
            return false;
        }
        boolean aBool = a instanceof Boolean || (a instanceof JsonNode n && n.isBoolean());
        boolean bBool = b instanceof Boolean || (b instanceof JsonNode n && n.isBoolean());
        if (aBool || bBool) {
            return truthy(a) == truthy(b);
        }
        return asString(a).equals(asString(b));
    }

    private static String asString(Object v) {
        if (v == null) {
            return "";
        }
        if (v instanceof JsonNode n) {
            if (n.isNull() || n.isMissingNode()) {
                return "";
            }
            if (n.isString()) {
                return n.stringValue();
            }
            if (n.isNumber()) {
                double d = n.asDouble();
                return d == Math.rint(d) && !Double.isInfinite(d) ? String.valueOf((long) d) : String.valueOf(d);
            }
            return n.toString();
        }
        if (v instanceof Double d) {
            return d == Math.rint(d) && !Double.isInfinite(d) ? String.valueOf(d.longValue()) : String.valueOf(d);
        }
        return String.valueOf(v);
    }

    private static Double numeric(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        String s = v instanceof JsonNode n
                ? (n.isNumber() ? String.valueOf(n.asDouble()) : (n.isString() ? n.stringValue() : ""))
                : String.valueOf(v);
        try {
            return Double.parseDouble(s.trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---------- структурированная форма ----------

    private static Node fromObject(JsonNode cond) {
        if (cond.isString()) {
            return compile(cond);
        }
        if (!cond.isObject()) {
            throw new IllegalArgumentException("Неизвестная форма условия");
        }
        if (cond.get("any") != null && cond.get("any").isArray()) {
            Node acc = null;
            for (JsonNode c : cond.get("any")) {
                Node n = fromObject(c);
                acc = acc == null ? n : new Or(acc, n);
            }
            return acc == null ? new Literal(true) : acc;
        }
        if (cond.get("all") != null && cond.get("all").isArray()) {
            Node acc = null;
            for (JsonNode c : cond.get("all")) {
                Node n = fromObject(c);
                acc = acc == null ? n : new And(acc, n);
            }
            return acc == null ? new Literal(true) : acc;
        }
        if (cond.get("not") != null) {
            return new Not(fromObject(cond.get("not")));
        }
        if (cond.get("field") != null && cond.get("field").isString()) {
            String op = cond.get("op") == null ? "==" : cond.get("op").asString();
            if (!List.of("==", "!=", ">", ">=", "<", "<=").contains(op)) {
                throw new IllegalArgumentException("Неизвестный оператор " + op);
            }
            JsonNode value = cond.get("value");
            Object literal = value == null || value.isNull() ? null
                    : value.isBoolean() ? value.asBoolean()
                    : value.isNumber() ? value.asDouble()
                    : value.isString() ? value.stringValue()
                    : value;
            return new Compare(op, new Field(cond.get("field").stringValue()), new Literal(literal));
        }
        throw new IllegalArgumentException("Неизвестная форма условия");
    }

    // ---------- строковая форма: токенизатор + рекурсивный спуск ----------

    private record Token(String type, Object value) {}

    static List<Token> tokenize(String src) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        int n = src.length();
        while (i < n) {
            char ch = src.charAt(i);
            if (ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r') {
                i++;
                continue;
            }
            if (ch == '"' || ch == '\'') {
                char quote = ch;
                int j = i + 1;
                StringBuilder value = new StringBuilder();
                while (j < n && src.charAt(j) != quote) {
                    if (src.charAt(j) == '\\' && j + 1 < n) {
                        value.append(src.charAt(j + 1));
                        j += 2;
                    } else {
                        value.append(src.charAt(j));
                        j++;
                    }
                }
                if (j >= n) {
                    throw new IllegalArgumentException("Незакрытая строка с позиции " + i);
                }
                tokens.add(new Token("string", value.toString()));
                i = j + 1;
                continue;
            }
            if (Character.isDigit(ch)) {
                int j = i;
                while (j < n && (Character.isDigit(src.charAt(j)) || src.charAt(j) == '.')) {
                    j++;
                }
                tokens.add(new Token("number", Double.parseDouble(src.substring(i, j))));
                i = j;
                continue;
            }
            if (isIdentStart(ch)) {
                int j = i;
                while (j < n && isIdentChar(src.charAt(j))) {
                    j++;
                }
                String word = src.substring(i, j);
                if (word.equals("true") || word.equals("false")) {
                    tokens.add(new Token("boolean", word.equals("true")));
                } else {
                    tokens.add(new Token("ident", word));
                }
                i = j;
                continue;
            }
            String three = src.substring(i, Math.min(n, i + 3));
            String two = src.substring(i, Math.min(n, i + 2));
            if (three.equals("===") || three.equals("!==")) {
                tokens.add(new Token("op", three.substring(0, 2)));
                i += 3;
                continue;
            }
            if (List.of("==", "!=", ">=", "<=", "||", "&&").contains(two)) {
                tokens.add(new Token("op", two));
                i += 2;
                continue;
            }
            if (ch == '>' || ch == '<' || ch == '!' || ch == '(' || ch == ')') {
                tokens.add(new Token("op", String.valueOf(ch)));
                i++;
                continue;
            }
            throw new IllegalArgumentException("Неожиданный символ «" + ch + "» в позиции " + i);
        }
        return tokens;
    }

    private static boolean isIdentStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isIdentChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    static Node parse(String src) {
        Parser p = new Parser(tokenize(src));
        Node ast = p.or();
        if (p.pos < p.tokens.size()) {
            throw new IllegalArgumentException("Лишний токен после конца условия");
        }
        return ast;
    }

    private static final class Parser {
        final List<Token> tokens;
        int pos;

        Parser(List<Token> tokens) {
            this.tokens = tokens;
        }

        Token peek() { return pos < tokens.size() ? tokens.get(pos) : null; }
        Token next() { return tokens.get(pos++); }

        boolean isOp(String v) {
            Token t = peek();
            return t != null && t.type().equals("op") && t.value().equals(v);
        }

        Node primary() {
            Token t = peek();
            if (t == null) {
                throw new IllegalArgumentException("Неожиданный конец условия");
            }
            next();
            switch (t.type()) {
                case "ident": return new Field((String) t.value());
                case "string", "number", "boolean": return new Literal(t.value());
                default:
                    if (t.value().equals("(")) {
                        Node e = or();
                        if (!isOp(")")) {
                            throw new IllegalArgumentException("Ожидалась закрывающая скобка");
                        }
                        next();
                        return e;
                    }
                    throw new IllegalArgumentException("Неожиданный токен «" + t.value() + "»");
            }
        }

        Node compare() {
            Node left = primary();
            Token t = peek();
            if (t != null && t.type().equals("op") && List.of("==", "!=", ">", ">=", "<", "<=").contains((String) t.value())) {
                String op = (String) next().value();
                Node right = primary();
                return new Compare(op, left, right);
            }
            return left;
        }

        Node unary() {
            if (isOp("!")) {
                next();
                return new Not(unary());
            }
            return compare();
        }

        Node and() {
            Node left = unary();
            while (isOp("&&")) {
                next();
                left = new And(left, unary());
            }
            return left;
        }

        Node or() {
            Node left = and();
            while (isOp("||")) {
                next();
                left = new Or(left, and());
            }
            return left;
        }
    }
}
