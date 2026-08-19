package ru.big.survey.service;

import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Тонкая обёртка над Jackson 3 ObjectMapper: JSON-строки в jsonb-колонках ходят как String. */
@Component
public class Json {

    private final ObjectMapper mapper;

    public Json(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public JsonNode read(String text) {
        if (text == null || text.isBlank()) {
            return mapper.createObjectNode();
        }
        return mapper.readTree(text);
    }

    public String write(Object value) {
        if (value == null) {
            return null;
        }
        return mapper.writeValueAsString(value);
    }

    public ObjectNode object() {
        return mapper.createObjectNode();
    }

    public ObjectNode object(Map<String, ?> values) {
        return mapper.valueToTree(values);
    }

    /** Канонизированное представление для контрольной суммы: ключи объектов отсортированы. */
    public String canonical(JsonNode node) {
        return mapper.writer().with(tools.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .writeValueAsString(mapper.convertValue(node, java.util.TreeMap.class));
    }

    public ObjectMapper mapper() {
        return mapper;
    }
}
