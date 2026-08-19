package ru.big.survey.config;

import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import ru.big.survey.domain.Role;
import ru.big.survey.security.AppUserDetailsService;
import ru.big.survey.service.UserService;

/**
 * Две цепочки:
 *  1) /api/v1/sync/** — HTTP Basic без сессии, только роль INTEGRATION (учётка 1С); снаружи путь дополнительно закрыт в nginx;
 *  2) всё остальное — сессия в cookie: публичные пути (анкета, SPA, health) открыты, /api/v1/staff/** — STAFF|ADMIN,
 *     /api/v1/admin/** — ADMIN. Вход — POST /api/v1/auth/login (JSON), контекст сохраняется в HttpSession.
 * CSRF отключён осознанно: API принимает только JSON, cookie сессии SameSite=Lax, форм с браузерным submit нет.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    DaoAuthenticationProvider authenticationProvider(AppUserDetailsService userDetailsService, PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return provider;
    }

    @Bean
    AuthenticationManager authenticationManager(DaoAuthenticationProvider provider) {
        return new ProviderManager(provider);
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    @Order(1)
    SecurityFilterChain syncFilterChain(HttpSecurity http, DaoAuthenticationProvider provider) throws Exception {
        return http
                .securityMatcher("/api/v1/sync/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authenticationProvider(provider)
                .authorizeHttpRequests(authorize -> authorize.anyRequest().hasRole(Role.INTEGRATION.name()))
                .httpBasic(Customizer.withDefaults())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, ex) -> {
                            response.setHeader("WWW-Authenticate", "Basic realm=\"survey-sync\"");
                            writeJson(response, HttpStatus.UNAUTHORIZED, "unauthorized", "Требуется авторизация учётной записи интеграции.");
                        })
                        .accessDeniedHandler((request, response, ex) ->
                                writeJson(response, HttpStatus.FORBIDDEN, "forbidden", "Учётной записи не назначена роль INTEGRATION.")))
                .build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain appFilterChain(HttpSecurity http, DaoAuthenticationProvider provider,
                                       SecurityContextRepository securityContextRepository) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .securityContext(context -> context.securityContextRepository(securityContextRepository))
                .authenticationProvider(provider)
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/api/v1/public/**", "/api/v1/auth/login", "/api/v1/auth/me").permitAll()
                        .requestMatchers("/api/v1/staff/**").hasAnyRole(Role.STAFF.name(), Role.ADMIN.name())
                        .requestMatchers("/api/v1/admin/**").hasRole(Role.ADMIN.name())
                        .requestMatchers("/api/**").authenticated()
                        .requestMatchers("/actuator/health/**").permitAll()
                        .anyRequest().permitAll())   // SPA, статика, /e/{guid}, /staff, /admin — страницы; доступ к данным решает API
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
                        .accessDeniedHandler((request, response, ex) ->
                                writeJson(response, HttpStatus.FORBIDDEN, "forbidden", "Недостаточно прав.")))
                .build();
    }

    /** Первый администратор создаётся из конфига, пока реестр пользователей пуст. */
    @Bean
    ApplicationRunner bootstrapAdministrator(UserService users, SurveyProperties properties) {
        return args -> users.bootstrapAdministrator(properties.getSecurity().getBootstrapAdmin());
    }

    private static void writeJson(HttpServletResponse response, HttpStatus status, String code, String message) throws java.io.IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"error\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }
}
