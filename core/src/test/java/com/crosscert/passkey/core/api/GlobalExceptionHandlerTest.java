package com.crosscert.passkey.core.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Unit-level MockMvc test for GlobalExceptionHandler.
 *
 * Uses standaloneSetup (no Spring Boot context) so the core java-library
 * module does not need a @SpringBootApplication on the test classpath.
 * The GlobalExceptionHandler is registered explicitly via
 * MockMvcBuilders.standaloneSetup(...).setControllerAdvice(...).
 */
class GlobalExceptionHandlerTest {

    private MockMvc mvc;

    @RestController
    static class TestController {
        @GetMapping("/test/business")
        String business() {
            throw new BusinessException(ErrorCode.TENANT_NOT_FOUND);
        }

        @GetMapping("/test/business-detail")
        String businessDetail() {
            throw new BusinessException(ErrorCode.KEY_ROTATION_CONFLICT, "lease held by other");
        }

        @GetMapping("/test/illegal")
        String illegal() {
            throw new IllegalArgumentException("bad value");
        }

        @GetMapping("/test/boom")
        String boom() {
            throw new RuntimeException("kaboom");
        }
    }

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders
                .standaloneSetup(new TestController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void businessExceptionMapsToErrorCodeStatusAndEnvelope() throws Exception {
        mvc.perform(get("/test/business"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("T001"))
                .andExpect(jsonPath("$.message").value("Tenant not found"))
                .andExpect(jsonPath("$.error.errorCode").value("T001"));
    }

    @Test
    void businessExceptionWithDetailKeepsCodeButCustomMessage() throws Exception {
        mvc.perform(get("/test/business-detail"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("S001"))
                .andExpect(jsonPath("$.message").value("lease held by other"));
    }

    @Test
    void illegalArgumentMapsToInvalidInput() throws Exception {
        mvc.perform(get("/test/illegal"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("C001"))
                .andExpect(jsonPath("$.message").value("bad value"));
    }

    @Test
    void unhandledExceptionMapsToInternalServerError() throws Exception {
        mvc.perform(get("/test/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("C999"))
                .andExpect(jsonPath("$.message").value("Server error"));
    }
}
