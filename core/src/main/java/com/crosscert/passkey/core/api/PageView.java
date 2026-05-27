package com.crosscert.passkey.core.api;

import org.springframework.data.domain.Page;

import java.util.List;

public record PageView<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        boolean hasNext
) {
    public static <T> PageView<T> from(Page<T> p) {
        return new PageView<>(
                p.getContent(),
                p.getNumber(),
                p.getSize(),
                p.getTotalElements(),
                p.hasNext());
    }
}
