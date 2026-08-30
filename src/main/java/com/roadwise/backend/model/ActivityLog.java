package com.roadwise.backend.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "activity_logs")
public class ActivityLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDateTime timestamp = LocalDateTime.now();

    // Actor Information
    private Long actorId;
    private String actorName;
    private String actorRole;
    private String actorOffice;

    // Event Classification & Target
    @Column(nullable = false)
    private String category; // "PROJECT", "QA", "EXPORT", "AUTH", "USER", "SYSTEM"

    @Column(nullable = false)
    private String action; // e.g., "REPORT_VALIDATED", "STATUS_UPDATED", "EXPORT_CSV"

    private String targetEntity; // e.g., "#PRJ-0040", "#USR-0012", "SYSTEM"

    @Column(columnDefinition = "TEXT")
    private String description;

    private String status = "SUCCESS"; // "SUCCESS", "WARNING", "FAILED"

    // Network & Device Telemetry
    private String ipAddress;
    private String httpMethod;

    @Column(columnDefinition = "TEXT")
    private String userAgent;

    public ActivityLog() {
    }

    // --- GETTERS AND SETTERS ---
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }

    public Long getActorId() { return actorId; }
    public void setActorId(Long actorId) { this.actorId = actorId; }

    public String getActorName() { return actorName; }
    public void setActorName(String actorName) { this.actorName = actorName; }

    public String getActorRole() { return actorRole; }
    public void setActorRole(String actorRole) { this.actorRole = actorRole; }

    public String getActorOffice() { return actorOffice; }
    public void setActorOffice(String actorOffice) { this.actorOffice = actorOffice; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public String getTargetEntity() { return targetEntity; }
    public void setTargetEntity(String targetEntity) { this.targetEntity = targetEntity; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }

    public String getHttpMethod() { return httpMethod; }
    public void setHttpMethod(String httpMethod) { this.httpMethod = httpMethod; }

    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
}