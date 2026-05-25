package com.crosscert.passkey.core.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "TENANT")
public class Tenant {

    @Id
    @Column(name = "ID", length = 64, nullable = false)
    private String id;

    @Column(name = "DISPLAY_NAME", length = 256, nullable = false)
    private String displayName;

    @Column(name = "STATUS", length = 16, nullable = false)
    private String status;

    @Column(name = "CREATED_AT", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "UPDATED_AT", nullable = false)
    private Instant updatedAt;

    protected Tenant() {}

    public Tenant(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
        this.status = "active";
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public String getId() { return id; }
    public String getDisplayName() { return displayName; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
