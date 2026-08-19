package ru.big.survey;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Сервис анкетирования посетителей выставок (survey).
 * Разворачивается как WAR во внешнем Tomcat под контекстом /survey; для локальной отладки — spring-boot:run.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class SurveyApplication extends SpringBootServletInitializer {

    @Override
    protected SpringApplicationBuilder configure(SpringApplicationBuilder application) {
        return application.sources(SurveyApplication.class);
    }

    public static void main(String[] args) {
        SpringApplication.run(SurveyApplication.class, args);
    }
}
