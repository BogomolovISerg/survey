package ru.big.survey.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ru.big.survey.domain.Event;
import ru.big.survey.persistence.EventRepository;

/** Шаги подтверждения телефона (звонок, ввод кода) доступны только для существующего и открытого мероприятия. */
class ResponseServiceEventCheckTest {

    private final Map<UUID, Event> store = new HashMap<>();
    private final ResponseService service = new ResponseService(events(), null, null, null, null, null, null, null, null);

    @Test
    void unknown_event_is_not_found() {
        assertThatThrownBy(() -> service.requireOpenEvent(UUID.randomUUID()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode()).isEqualTo("not_found");
    }

    @Test
    void event_without_published_version_or_inactive_is_closed() throws Exception {
        UUID unpublished = add(0, true);
        UUID inactive = add(1, false);

        assertThatThrownBy(() -> service.requireOpenEvent(unpublished))
                .isInstanceOf(ApiException.class).extracting(e -> ((ApiException) e).getCode()).isEqualTo("closed");
        assertThatThrownBy(() -> service.requireOpenEvent(inactive))
                .isInstanceOf(ApiException.class).extracting(e -> ((ApiException) e).getCode()).isEqualTo("closed");
    }

    @Test
    void active_published_event_passes() throws Exception {
        UUID open = add(1, true);

        service.requireOpenEvent(open);   // не бросает
        assertThat(store.containsKey(open)).isTrue();
    }

    private UUID add(int version, boolean active) throws Exception {
        UUID id = UUID.randomUUID();
        Event event = Event.create(id, Instant.parse("2026-10-21T10:00:00Z"));
        set(event, "currentVersion", version);
        set(event, "active", active);
        store.put(id, event);
        return id;
    }

    private static void set(Event event, String field, Object value) throws Exception {
        Field f = Event.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(event, value);
    }

    private EventRepository events() {
        return (EventRepository) Proxy.newProxyInstance(EventRepository.class.getClassLoader(), new Class<?>[]{EventRepository.class},
                (self, method, args) -> {
                    if (method.getName().equals("findById")) {
                        return Optional.ofNullable(store.get((UUID) args[0]));
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
