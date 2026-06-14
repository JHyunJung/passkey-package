package com.crosscert.passkey.rpapp.web

import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping

@Controller
class PageController {

    @GetMapping("/")
    fun index(): String = "index"

    @GetMapping("/register")
    fun register(): String = "register"

    @GetMapping("/login")
    fun login(): String = "login"
}
