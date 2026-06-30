package com.crosscert.passkey.rpapp.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** 데모 화면(Thymeleaf) 라우팅. index / register / login 페이지를 돌려준다. */
@Controller
public class PageController {

    @GetMapping("/")
    public String index() { return "index"; }

    @GetMapping("/register")
    public String register() { return "register"; }

    @GetMapping("/login")
    public String login() { return "login"; }
}
