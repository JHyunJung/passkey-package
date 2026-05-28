package com.crosscert.passkey.admin.funnel;

import java.util.List;

public final class FunnelDto {
    private FunnelDto() {}

    public record View(
            int windowDays,
            Stage registration,
            Stage authentication,
            double conversion,
            List<DailyPoint> series,
            List<EventCount> byEventType
    ) {}

    public record Stage(long attempts, long success, double ratio) {}

    public record DailyPoint(String day, long attempts, long success) {}

    public record EventCount(String type, long n) {}
}
