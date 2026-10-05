package ru.big.survey.config;

import java.time.Duration;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Кэширование статики SPA. Сборки Vite в /assets/ имеют хэш в имени — их можно кэшировать вечно.
 * index.html (и прочие нехэшированные файлы) браузер обязан перепроверять при каждом заходе:
 * иначе после деплоя новой сборки Safari/iOS держит старый index.html, ссылающийся на удалённые
 * чанки, и посетитель получает «Importing a module script failed».
 */
@Configuration
public class StaticCacheConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/assets/**")
                .addResourceLocations("classpath:/static/assets/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).immutable());
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .setCacheControl(CacheControl.noCache().mustRevalidate());
    }
}
