package ru.big.survey.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.SimpleTransactionStatus;
import ru.big.survey.config.SurveyProperties;
import ru.big.survey.domain.CallBudget;
import ru.big.survey.domain.PhoneVerification;
import ru.big.survey.persistence.CallBudgetRepository;
import ru.big.survey.persistence.PhoneDeviceRepository;
import ru.big.survey.persistence.PhoneVerificationRepository;

/** Повторная проверка телефона: токен без звонка получает только устройство, на котором вводили код. */
class PhoneVerificationServiceTest {

    private static final String PHONE = "79161234567";
    private static final String OTHER_PHONE = "79167654321";

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-21T10:00:00Z"));
    private final AtomicInteger calls = new AtomicInteger();
    private final Map<String, PhoneVerification> verificationStore = new ConcurrentHashMap<>();
    private final Map<LocalDate, Integer> budgetStore = new ConcurrentHashMap<>();
    private final Map<String, ReentrantLock> rowLocks = new ConcurrentHashMap<>();
    private final ThreadLocal<List<ReentrantLock>> heldLocks = ThreadLocal.withInitial(ArrayList::new);
    private final ThreadLocal<Boolean> inTransaction = ThreadLocal.withInitial(() -> false);
    private volatile RuntimeException flashFailure;
    private volatile long flashDelayMs;
    private final Map<String, Instant> deviceStore = new HashMap<>();   // "phone|hash" -> expiresAt

    private SurveyProperties props;
    private PhoneVerificationService service;
    private TokenService tokens;

    @BeforeEach
    void setUp() {
        Clock clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        props = new SurveyProperties();
        props.getSecurity().setTokenSecret("0123456789abcdef0123456789abcdef");
        props.getSecurity().setVerificationTokenValid(Duration.ofMinutes(30));
        tokens = new TokenService(props, clock);
        tokens.init();
        FlashCallClient flash = phone -> {
            if (flashFailure != null) {
                throw flashFailure;
            }
            if (flashDelayMs > 0) {
                sleep(flashDelayMs);   // обращение к провайдеру занимает время: окно для гонки
            }
            calls.incrementAndGet();
            return "1234";
        };
        service = new PhoneVerificationService(verifications(), devices(), budgets(), flash, tokens, props, clock);
    }

    // ---------- сценарии ----------

    @Test
    void code_confirmation_registers_device_and_issues_new_secret() {
        PhoneVerificationService.VerifyResult r = confirm(PHONE);

        assertThat(r.token()).isNotNull();
        assertThat(tokens.parseVerification(r.token()).get().phone()).isEqualTo(PHONE);
        assertThat(DeviceSecrets.isWellFormed(r.deviceSecret())).isTrue();
        // в БД только хеш секрета, сам секрет не хранится
        assertThat(deviceStore).containsOnlyKeys(PHONE + "|" + DeviceSecrets.hash(r.deviceSecret()));
        assertThat(deviceStore.keySet().iterator().next()).doesNotContain(r.deviceSecret());
    }

    @Test
    void known_device_gets_token_without_a_call() {
        String secret = confirm(PHONE).deviceSecret();
        int before = calls.get();

        PhoneVerificationService.CallResult r = service.call(PHONE, secret);

        assertThat(r.status()).isEqualTo("already_verified");
        assertThat(tokens.parseVerification(r.token()).get().phone()).isEqualTo(PHONE);
        assertThat(calls.get()).isEqualTo(before);
    }

    @Test
    void knowing_the_number_is_not_enough() {
        confirm(PHONE);
        int before = calls.get();

        PhoneVerificationService.CallResult r = service.call(PHONE, null);

        assertThat(r.status()).isEqualTo("called");
        assertThat(r.token()).isNull();
        assertThat(calls.get()).isEqualTo(before + 1);
    }

    @Test
    void unknown_or_malformed_cookie_does_not_skip_the_call() {
        confirm(PHONE);
        int before = calls.get();

        assertThat(service.call(PHONE, DeviceSecrets.generate()).status()).isEqualTo("called");   // чужой секрет
        now.set(now.get().plusSeconds(120));
        assertThat(service.call(PHONE, "abc").status()).isEqualTo("called");                      // мусор в cookie
        assertThat(calls.get()).isEqualTo(before + 2);
    }

    @Test
    void secret_of_one_phone_does_not_open_another() {
        String secret = confirm(PHONE).deviceSecret();

        PhoneVerificationService.CallResult r = service.call(OTHER_PHONE, secret);

        assertThat(r.status()).isEqualTo("called");
        assertThat(r.token()).isNull();
    }

    @Test
    void device_expires_with_verified_valid_period() {
        String secret = confirm(PHONE).deviceSecret();

        now.set(now.get().plus(Duration.ofHours(23)));
        assertThat(service.call(PHONE, secret).status()).isEqualTo("already_verified");

        now.set(now.get().plus(Duration.ofHours(2)));   // прошло 25 часов
        assertThat(service.call(PHONE, secret).status()).isEqualTo("called");
    }

    @Test
    void verified_phone_cannot_get_token_without_a_fresh_code() {
        confirm(PHONE);

        // раньше повторный verify по уже подтверждённому номеру выдавал токен при любом коде
        assertThatThrownBy(() -> service.verify(PHONE, "0000"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode()).isEqualTo("expired");
    }

    @Test
    void wrong_code_registers_no_device() {
        service.call(PHONE, null);

        assertThatThrownBy(() -> service.verify(PHONE, "9999"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode()).isEqualTo("wrong_code");
        assertThat(deviceStore).isEmpty();
    }

    @Test
    void foreign_call_does_not_revoke_the_confirmed_device() {
        String secret = confirm(PHONE).deviceSecret();

        now.set(now.get().plusSeconds(120));
        service.call(PHONE, null);   // посторонний заказывает звонок на чужой номер: сбрасывает состояние кода

        assertThat(service.call(PHONE, secret).status()).isEqualTo("already_verified");
    }

    @Test
    void every_confirmation_issues_a_fresh_secret() {
        String first = confirm(PHONE).deviceSecret();
        now.set(now.get().plusSeconds(120));
        String second = confirm(PHONE).deviceSecret();

        assertThat(second).isNotEqualTo(first);
    }

    @Test
    void purge_removes_only_expired_devices() {
        String secret = confirm(PHONE).deviceSecret();

        service.purgeExpired();
        assertThat(deviceStore).hasSize(1);

        now.set(now.get().plus(Duration.ofHours(25)));
        service.purgeExpired();
        assertThat(deviceStore).isEmpty();
        assertThat(service.call(PHONE, secret).status()).isEqualTo("called");
    }

    // ---------- лимит попыток ----------

    @Test
    void failed_attempt_is_committed_not_rolled_back_with_the_exception() {
        // Неверный код — это ApiException (RuntimeException): без noRollbackFor Spring откатывает транзакцию
        // вместе с увеличенным счётчиком, и лимит попыток не работает. Проверяем на настоящем перехватчике Spring.
        service.call(PHONE, null);
        RecordingTxManager tx = new RecordingTxManager();
        PhoneVerificationService proxied = transactional(tx);

        assertThatThrownBy(() -> proxied.verify(PHONE, "9999")).isInstanceOf(ApiException.class);

        assertThat(tx.rollbacks).isEqualTo(0);
        assertThat(tx.commits).isEqualTo(1);
    }

    @Test
    void attempts_are_limited_and_exhausted_code_is_dead() {
        service.call(PHONE, null);

        for (int left = 4; left >= 1; left--) {
            assertThat(failure(() -> service.verify(PHONE, "0000"))).isEqualTo("wrong_code");
        }
        assertThat(failure(() -> service.verify(PHONE, "0000"))).isEqualTo("too_many");   // пятая неудачная
        assertThat(failure(() -> service.verify(PHONE, "0001"))).isEqualTo("too_many");   // шестая даже не проверяется
        // верный код после исчерпания попыток токен не даёт
        assertThat(failure(() -> service.verify(PHONE, "1234"))).isEqualTo("too_many");
        assertThat(deviceStore).isEmpty();
    }

    @Test
    void new_call_resets_attempts() {
        service.call(PHONE, null);
        for (int i = 0; i < 5; i++) {
            failure(() -> service.verify(PHONE, "0000"));
        }
        now.set(now.get().plusSeconds(120));

        service.call(PHONE, null);

        assertThat(service.verify(PHONE, "1234").token()).isNotNull();
    }

    @Test
    void parallel_guesses_cannot_exceed_the_attempt_limit() throws Exception {
        service.call(PHONE, null);
        int threads = 40;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Callable<Boolean> task = () -> {
                start.await();
                try {
                    service.verify(PHONE, "0000");
                    return true;
                } catch (ApiException e) {
                    // true — попытка реально проверена (код сравнивался); false — отсечена до проверки
                    return !e.getMessage().startsWith("Превышено");
                }
            };
            results.add(pool.submit(task));
        }
        start.countDown();
        int checked = 0;
        for (Future<Boolean> f : results) {
            if (f.get(10, TimeUnit.SECONDS)) {
                checked++;
            }
        }
        pool.shutdownNow();

        assertThat(checked).isEqualTo(5);   // ровно столько, сколько разрешено, а не по числу параллельных запросов
        assertThat(failure(() -> service.verify(PHONE, "1234"))).isEqualTo("too_many");
    }

    // ---------- лимиты звонков ----------

    @Test
    void daily_limit_per_phone_is_enforced() {
        for (int i = 0; i < 8; i++) {
            service.call(PHONE, null);
            now.set(now.get().plusSeconds(61));   // окно повтора прошло
        }
        assertThat(calls.get()).isEqualTo(8);

        assertThat(failure(() -> service.call(PHONE, null))).isEqualTo("too_many");
        assertThat(calls.get()).isEqualTo(8);
    }

    @Test
    void parallel_calls_for_one_phone_order_a_single_flash_call() throws Exception {
        flashDelayMs = 50;   // пока идёт звонок провайдеру, остальные запросы уже пришли
        int threads = 20;
        PhoneVerificationService proxied = transactional(new RecordingTxManager());
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Callable<String> task = () -> {
                start.await();
                return proxied.call(PHONE, null).status();
            };
            results.add(pool.submit(task));
        }
        start.countDown();
        int called = 0;
        int waiting = 0;
        for (Future<String> f : results) {
            String status = f.get(20, TimeUnit.SECONDS);
            if (status.equals("called")) {
                called++;
            } else if (status.equals("wait")) {
                waiting++;
            }
        }
        pool.shutdownNow();

        assertThat(calls.get()).isEqualTo(1);   // провайдеру ушёл один заказ, а не по числу запросов
        assertThat(called).isEqualTo(1);
        assertThat(waiting).isEqualTo(threads - 1);
    }

    @Test
    void daily_budget_stops_new_calls_for_all_phones() {
        props.getVerification().setMaxCallsPerDay(2);
        service.call("79160000001", null);
        service.call("79160000002", null);
        int before = calls.get();

        assertThat(failure(() -> service.call("79160000003", null))).isEqualTo("busy");

        assertThat(calls.get()).isEqualTo(before);   // провайдеру заказ не уходил
    }

    @Test
    void daily_budget_does_not_block_a_confirmed_device() {
        String secret = confirm(PHONE).deviceSecret();
        props.getVerification().setMaxCallsPerDay(1);   // бюджет уже исчерпан

        assertThat(service.call(PHONE, secret).status()).isEqualTo("already_verified");
        assertThat(failure(() -> service.call(OTHER_PHONE, null))).isEqualTo("busy");
    }

    @Test
    void daily_budget_resets_on_the_next_day() {
        props.getVerification().setMaxCallsPerDay(1);
        service.call(PHONE, null);
        assertThat(failure(() -> service.call(OTHER_PHONE, null))).isEqualTo("busy");

        now.set(now.get().plus(Duration.ofHours(15)));   // уже следующие сутки по Москве

        assertThat(service.call(OTHER_PHONE, null).status()).isEqualTo("called");
    }

    @Test
    void failed_provider_call_does_not_consume_the_budget() {
        flashFailure = ApiException.upstream("Провайдер недоступен");

        assertThat(failure(() -> service.call(PHONE, null))).isEqualTo("upstream");

        assertThat(budgetStore).isEmpty();
    }

    @Test
    void purge_removes_old_budget_days() {
        service.call(PHONE, null);
        assertThat(budgetStore).hasSize(1);

        now.set(now.get().plus(Duration.ofDays(10)));
        service.purgeExpired();

        assertThat(budgetStore).isEmpty();
    }

    // ---------- помощники и фейки репозиториев ----------

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Как COMMIT/ROLLBACK в БД: блокировки строк, взятые транзакцией потока, снимаются. */
    private void releaseRowLocks() {
        List<ReentrantLock> held = heldLocks.get();
        for (ReentrantLock l : held) {
            l.unlock();
        }
        held.clear();
        inTransaction.set(false);
    }

    private static String failure(Runnable action) {
        try {
            action.run();
        } catch (ApiException e) {
            return e.getCode();
        }
        return "no_error";
    }

    private PhoneVerificationService transactional(RecordingTxManager tx) {
        ProxyFactory factory = new ProxyFactory(service);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(tx, new AnnotationTransactionAttributeSource()));
        return (PhoneVerificationService) factory.getProxy();
    }

    private class RecordingTxManager implements PlatformTransactionManager {
        int commits;
        int rollbacks;

        @Override public TransactionStatus getTransaction(TransactionDefinition definition) {
            inTransaction.set(true);
            return new SimpleTransactionStatus();
        }
        @Override public synchronized void commit(TransactionStatus status) {
            commits++;
            releaseRowLocks();
        }
        @Override public synchronized void rollback(TransactionStatus status) {
            rollbacks++;
            releaseRowLocks();
        }
    }

    /** Заказ звонка и ввод верного кода (фейковый провайдер всегда возвращает 1234). */
    private PhoneVerificationService.VerifyResult confirm(String phone) {
        service.call(phone, null);
        return service.verify(phone, "1234");
    }

    private PhoneVerificationRepository verifications() {
        return proxy(PhoneVerificationRepository.class, (method, args) -> switch (method.getName()) {
            case "findById" -> Optional.ofNullable(verificationStore.get((String) args[0]));
            case "insertIfAbsent" -> {
                boolean created = verificationStore.putIfAbsent((String) args[0], PhoneVerification.create((String) args[0], (Instant) args[1])) == null;
                yield created ? 1 : 0;
            }
            case "findForUpdate" -> {
                if (inTransaction.get()) {   // SELECT ... FOR UPDATE: строка занята до конца транзакции
                    ReentrantLock lock = rowLocks.computeIfAbsent((String) args[0], k -> new ReentrantLock());
                    lock.lock();
                    heldLocks.get().add(lock);
                }
                yield Optional.ofNullable(verificationStore.get((String) args[0]));
            }
            case "save" -> {
                PhoneVerification v = (PhoneVerification) args[0];
                verificationStore.put(v.getPhone(), v);
                yield v;
            }
            case "claimAttempt" -> {
                synchronized (verificationStore) {   // как блокировка строки при UPDATE ... WHERE attempts < :max
                    PhoneVerification v = verificationStore.get((String) args[0]);
                    Instant at = (Instant) args[1];
                    int max = (int) args[2];
                    if (v != null && !v.isVerified() && v.getCodeHash() != null && v.getExpiresAt() != null
                            && v.getExpiresAt().isAfter(at) && v.getAttempts() < max) {
                        setAttempts(v, v.getAttempts() + 1);
                        yield 1;
                    }
                    yield 0;
                }
            }
            case "deleteExpired" -> 0;
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }

    private static void setAttempts(PhoneVerification v, int value) {
        try {
            java.lang.reflect.Field f = PhoneVerification.class.getDeclaredField("attempts");
            f.setAccessible(true);
            f.setInt(v, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private CallBudgetRepository budgets() {
        return proxy(CallBudgetRepository.class, (method, args) -> switch (method.getName()) {
            case "findById" -> Optional.ofNullable(budgetStore.get((LocalDate) args[0])).map(c -> new CallBudget((LocalDate) args[0], c));
            case "increment" -> {
                budgetStore.merge((LocalDate) args[0], 1, Integer::sum);
                yield 1;
            }
            case "deleteBefore" -> {
                int before = budgetStore.size();
                budgetStore.keySet().removeIf(day -> day.isBefore((LocalDate) args[0]));
                yield before - budgetStore.size();
            }
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }

    private PhoneDeviceRepository devices() {
        return proxy(PhoneDeviceRepository.class, (method, args) -> switch (method.getName()) {
            case "existsByPhoneAndDeviceHashAndExpiresAtAfter" -> {
                Instant expires = deviceStore.get(args[0] + "|" + args[1]);
                yield expires != null && expires.isAfter((Instant) args[2]);
            }
            case "upsert" -> {
                deviceStore.put(args[0] + "|" + args[1], (Instant) args[3]);
                yield 1;
            }
            case "deleteExpired" -> {
                int before = deviceStore.size();
                deviceStore.values().removeIf(expires -> expires.isBefore((Instant) args[0]));
                yield before - deviceStore.size();
            }
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }

    private interface Handler {
        Object handle(java.lang.reflect.Method method, Object[] args);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Handler handler) {
        InvocationHandler h = (self, method, args) -> method.getDeclaringClass() == Object.class
                ? method.invoke(handler, args)
                : handler.handle(method, args);
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, h);
    }
}
