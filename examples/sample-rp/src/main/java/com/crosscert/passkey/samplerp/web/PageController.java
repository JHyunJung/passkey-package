package com.crosscert.passkey.samplerp.web;

import com.crosscert.passkey.samplerp.session.SessionKeys;
import com.crosscert.passkey.samplerp.user.SampleRpUser;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PageController {

    @GetMapping("/")
    public String index(HttpSession s, Model m) {
        SampleRpUser u = (SampleRpUser) s.getAttribute(SessionKeys.USER);
        m.addAttribute("user", u);
        return "index";
    }

    @GetMapping("/register")
    public String register() { return "register"; }

    @GetMapping("/login")
    public String login() { return "login"; }
}
