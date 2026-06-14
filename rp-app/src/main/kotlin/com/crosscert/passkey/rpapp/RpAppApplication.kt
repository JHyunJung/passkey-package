package com.crosscert.passkey.rpapp

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class RpAppApplication

fun main(args: Array<String>) {
    runApplication<RpAppApplication>(*args)
}
