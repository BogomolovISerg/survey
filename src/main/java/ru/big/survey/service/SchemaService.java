package ru.big.survey.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Работа со схемой анкеты формата v1:
 *   { enums: {имя: [значения]}, components: {имя: {header, conditions, required[], data:[{поле:{...}}|"{Компонент}"], style}},
 *     schema: ["{Компонент}", ...], style: {}, output: {поле: ""} }
 * Сервис хранит схему как прислала 1С; здесь — проверка целостности, контрольная сумма и серверная проверка ответа.
 */
@Service
public class SchemaService {

    private final Json json;

    public SchemaService(Json json) {
        this.json = json;
    }

    /** Проверить схему из 1С; при ошибке — ApiException 400 с понятным текстом. */
    public void validate(JsonNode q) {
        if (q == null || !q.isObject()) {
            throw ApiException.badRequest("schema", "questionnaire должен быть объектом.");
        }
        JsonNode components = q.get("components");
        if (components == null || !components.isObject() || components.size() == 0) {
            throw ApiException.badRequest("schema", "В анкете нет компонентов (components).");
        }
        JsonNode order = q.get("schema");
        if (order == null || !order.isArray() || order.size() == 0) {
            throw ApiException.badRequest("schema", "В анкете не задан порядок компонентов (schema).");
        }
        for (JsonNode ref : order) {
            String name = refName(ref);
            if (name.isEmpty() || components.get(name) == null) {
                throw ApiException.badRequest("schema", "Схема ссылается на неизвестный компонент «" + ref.asString() + "».");
            }
        }
        for (Map.Entry<String, JsonNode> entry : components.properties()) {
            JsonNode component = entry.getValue();
            if (!component.isObject()) {
                throw ApiException.badRequest("schema", "Компонент «" + entry.getKey() + "» имеет неверный формат.");
            }
            JsonNode data = component.get("data");
            if (data != null && data.isArray()) {
                for (JsonNode element : data) {
                    if (element.isString()) {
                        String name = refName(element);
                        if (components.get(name) == null) {
                            throw ApiException.badRequest("schema",
                                    "Компонент «" + entry.getKey() + "» ссылается на неизвестный компонент «" + name + "».");
                        }
                    } else if (!element.isObject()) {
                        throw ApiException.badRequest("schema", "Компонент «" + entry.getKey() + "»: элемент data должен быть объектом.");
                    }
                }
            }
        }
        JsonNode enums = q.get("enums");
        if (enums != null && !enums.isNull() && !enums.isObject()) {
            throw ApiException.badRequest("schema", "enums должен быть объектом.");
        }
    }

    /** SHA-256 канонизированного JSON — одинаковая схема не создаёт новую версию. */
    public String checksum(JsonNode q) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(json.canonical(q).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Проверка ответа: обязательные поля видимых компонентов заполнены (скрытые по условию — не обязательны, как в v1).
     * Возвращает список незаполненных обязательных полей.
     */
    public List<String> missingRequired(JsonNode schema, JsonNode answers) {
        List<String> missing = new ArrayList<>();
        JsonNode components = schema.get("components");
        JsonNode order = schema.get("schema");
        if (components == null || order == null) {
            return missing;
        }
        for (JsonNode ref : order) {
            JsonNode component = components.get(refName(ref));
            if (component == null || !Conditions.isVisible(component, answers)) {
                continue;
            }
            collectMissing(component, components, answers, missing, 0);
        }
        return missing;
    }

    private void collectMissing(JsonNode component, JsonNode components, JsonNode answers, List<String> missing, int depth) {
        if (depth > 5) {
            return;
        }
        JsonNode required = component.get("required");
        JsonNode data = component.get("data");
        Map<String, JsonNode> definitions = new LinkedHashMap<>();
        if (data != null && data.isArray()) {
            for (JsonNode element : data) {
                if (element.isString()) {
                    JsonNode nested = components.get(refName(element));
                    if (nested != null && Conditions.isVisible(nested, answers)) {
                        collectMissing(nested, components, answers, missing, depth + 1);
                    }
                } else if (element.isObject()) {
                    for (Map.Entry<String, JsonNode> field : element.properties()) {
                        // Одноимённые поля с разными условиями (как «Город» в v1): берём видимое определение
                        JsonNode existing = definitions.get(field.getKey());
                        if (existing == null || !Conditions.isVisible(existing, answers)) {
                            definitions.put(field.getKey(), field.getValue());
                        }
                    }
                }
            }
        }
        if (required == null || !required.isArray()) {
            return;
        }
        for (JsonNode r : required) {
            String name = r.asString();
            JsonNode definition = definitions.get(name);
            if (definition != null && !Conditions.isVisible(definition, answers)) {
                continue;
            }
            JsonNode value = answers == null ? null : answers.get(name);
            if (isEmpty(value) && !missing.contains(name)) {
                missing.add(name);
            }
        }
    }

    private static boolean isEmpty(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return true;
        }
        if (value.isString()) {
            return value.stringValue().isBlank();
        }
        if (value.isArray()) {
            return value.size() == 0;
        }
        if (value.isBoolean()) {
            return !value.asBoolean();
        }
        return false;
    }

    /** "{Идентификация}" → "Идентификация" (как refName в v1). */
    public static String refName(JsonNode ref) {
        if (ref == null) {
            return "";
        }
        String s = ref.isString() ? ref.stringValue() : ref.toString();
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == '_') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Публичное представление схемы для клиента: схема из 1С + блок event. */
    public ObjectNode publicView(JsonNode schema, ObjectNode event) {
        ObjectNode view = (ObjectNode) schema.deepCopy();
        view.set("event", event);
        return view;
    }
}
