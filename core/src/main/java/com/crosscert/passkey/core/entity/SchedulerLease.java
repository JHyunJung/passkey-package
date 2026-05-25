package com.crosscert.passkey.core.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "SCHEDULER_LEASE")
public class SchedulerLease {

    @Id
    @Column(name = "NAME", length = 64, nullable = false)
    private String name;

    @Column(name = "HOLDER", length = 256, nullable = false)
    private String holder;

    @Column(name = "EXPIRES_AT", nullable = false)
    private Instant expiresAt;

    protected SchedulerLease() {}

    public String getName() { return name; }
    public String getHolder() { return holder; }
    public Instant getExpiresAt() { return expiresAt; }
}
