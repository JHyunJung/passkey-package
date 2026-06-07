package com.crosscert.passkey.rpapp.common.response;

import java.util.List;

public record PageResponse<T>(
        List<T> content, int page, int size,
        long totalElements, int totalPages,
        boolean hasNext, boolean hasPrevious
) {}
