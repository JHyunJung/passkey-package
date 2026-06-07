package com.crosscert.passkey.rpapp.web;

import com.crosscert.passkey.rpapp.session.SessionKeys;
import com.crosscert.passkey.rpapp.user.RpAppUser;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PageController {

    @GetMapping("/")
    public String index(HttpSession s, Model m) {
        RpAppUser u = (RpAppUser) s.getAttribute(SessionKeys.USER);
        m.addAttribute("user", u);
        return "index";
    }

    @GetMapping("/register")
    public String register() { return "register"; }

    @GetMapping("/login")
    public String login() { return "login"; }
}
