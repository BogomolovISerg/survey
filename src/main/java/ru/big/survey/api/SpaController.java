package ru.big.survey.api;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Маршруты React-приложения отдаются как index.html; API и статика сюда не попадают. */
@Controller
public class SpaController {

    @GetMapping({"/", "/e/{eventId}", "/staff", "/staff/**", "/admin", "/admin/**", "/login"})
    public String spa() {
        return "forward:/index.html";
    }
}
