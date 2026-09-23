package com.roadwise.backend.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.time.ZoneId;

@Entity
@Table(name = "report_status_logs")
public class ReportStatusLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Link back to the parent RoadReport
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "report_id", nullable = false)
    private RoadReport report;

    // The event keyword: SUBMITTED, REJECTED, RESUBMITTED, VALIDATED, DISPATCHED,
    // PENDING_BUDGET, IN_PROGRESS, REPAIR_REWORK_REQUESTED, COMPLETED, CLOSED
    @Column(nullable = false, length = 50)
    private String action;

    @Column(name = "previous_status", length = 50)
    private String previousStatus;

    @Column(name = "new_status", nullable = false, length = 50)
    private String newStatus;

    // Preserves the rejection reason, rework summary, or repair comments
    @Column(columnDefinition = "TEXT")
    private String remarks;

    // Actor accountability details
    @Column(name = "actor_name", length = 100)
    private String actorName;

    @Column(name = "actor_role", length = 50)
    private String actorRole;

    // Optional snapshot image URL (e.g., resubmitted photo or CEO repair proof)
    @Column(name = "attachment_url", columnDefinition = "TEXT")
    private String attachmentUrl;

    // Exact event timestamp
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now(ZoneId.of("Asia/Manila"));
        }
    }

    public ReportStatusLog() {
    }

    public ReportStatusLog(RoadReport report, String action, String previousStatus, String newStatus,
                           String remarks, String actorName, String actorRole, String attachmentUrl) {
        this.report = report;
        this.action = action;
        this.previousStatus = previousStatus;
        this.newStatus = newStatus;
        this.remarks = remarks;
        this.actorName = actorName;
        this.actorRole = actorRole;
        this.attachmentUrl = attachmentUrl;
        this.createdAt = LocalDateTime.now(ZoneId.of("Asia/Manila"));
    }

    // ==========================================
    // GETTERS & SETTERS
    // ==========================================
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public RoadReport getReport() {
        return report;
    }

    public void setReport(RoadReport report) {
        this.report = report;
    }

    public Long getReportId() {
        return report != null ? report.getId() : null;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getPreviousStatus() {
        return previousStatus;
    }

    public void setPreviousStatus(String previousStatus) {
        this.previousStatus = previousStatus;
    }

    public String getNewStatus() {
        return newStatus;
    }

    public void setNewStatus(String newStatus) {
        this.newStatus = newStatus;
    }

    public String getRemarks() {
        return remarks;
    }

    public void setRemarks(String remarks) {
        this.remarks = remarks;
    }

    public String getActorName() {
        return actorName;
    }

    public void setActorName(String actorName) {
        this.actorName = actorName;
    }

    public String getActorRole() {
        return actorRole;
    }

    public void setActorRole(String actorRole) {
        this.actorRole = actorRole;
    }

    public String getAttachmentUrl() {
        return attachmentUrl;
    }

    public void setAttachmentUrl(String attachmentUrl) {
        this.attachmentUrl = attachmentUrl;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}