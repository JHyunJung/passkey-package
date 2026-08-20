package com.crosscert.passkey.core.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 스키마 소유자 계정명(= 스키마명)을 한 곳에서 관리한다.
 *
 * <p>raw SQL 에 스키마명을 하드코딩하면 계정명이 바뀔 때마다 코드를 고쳐야 하고,
 * 컴파일은 통과하므로 런타임에야 깨진다. 값을 설정으로 빼면 다음 rename 은
 * 환경변수 교체로 끝난다.
 *
 * <p>값의 출처는 배포자가 통제하는 설정이며 사용자 입력이 아니다. 그래도
 * 식별자 패턴을 부팅 시점에 검증해 오타·오설정을 조기에 드러낸다.
 */
@Component
public class DbSchemaProperties {

    private static final Pattern VALID = Pattern.compile("^[A-Za-z][A-Za-z0-9_]*$");

    private final String schema;

    public DbSchemaProperties(@Value("${passkey.db.schema}") String schema) {
        String trimmed = schema == null ? "" : schema.trim();
        if (!VALID.matcher(trimmed).matches()) {
            throw new IllegalArgumentException(
                    "passkey.db.schema 가 올바른 식별자가 아닙니다: '" + schema + "'");
        }
        this.schema = trimmed;
    }

    /** 검증된 스키마 식별자. raw SQL 의 테이블 prefix 로 쓴다. */
    public String schema() {
        return schema;
    }
}
