package com.crosscert.passkey.rpapp.common.response;

import java.util.List;
import java.util.Objects;

public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext,
        boolean hasPrevious
) {
    // 원본 Kotlin 의 non-null `content: List<T>` 계약을 보존(null 시 fail-fast).
    public PageResponse {
        Objects.requireNonNull(content, "content");
    }
}
