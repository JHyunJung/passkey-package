package com.crosscert.passkey.rpapp.common.response;

import java.util.List;
import java.util.Objects;

/** 페이지네이션 응답 봉투(목록 + 페이지 메타데이터). */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext,
        boolean hasPrevious
) {
    public PageResponse {
        // content 는 필수 — null 이면 즉시 실패시킨다.
        Objects.requireNonNull(content, "content");
    }
}
