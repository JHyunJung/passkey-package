package com.crosscert.passkey.core.entity;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class TenantConveyanceTest {

    private Tenant tenant(String conveyance) {
        Tenant t = new Tenant();
        t.setAttestationConveyance(conveyance);
        return t;
    }

    @Test
    void maps_uppercase_enum_to_webauthn_lowercase() {
        assertThat(tenant("NONE").getAttestationConveyanceLowercase()).isEqualTo("none");
        assertThat(tenant("INDIRECT").getAttestationConveyanceLowercase()).isEqualTo("indirect");
        assertThat(tenant("DIRECT").getAttestationConveyanceLowercase()).isEqualTo("direct");
        assertThat(tenant("ENTERPRISE").getAttestationConveyanceLowercase()).isEqualTo("enterprise");
    }

    @Test
    void unknown_or_null_falls_back_to_none() {
        assertThat(tenant(null).getAttestationConveyanceLowercase()).isEqualTo("none");
        assertThat(tenant("bogus").getAttestationConveyanceLowercase()).isEqualTo("none");
    }

    @Test
    void trims_and_lowercases_and_blank_falls_back() {
        assertThat(tenant("  DiReCt  ").getAttestationConveyanceLowercase()).isEqualTo("direct");
        assertThat(tenant("").getAttestationConveyanceLowercase()).isEqualTo("none"); // 빈 문자열(컬럼 present but blank)
    }
}
