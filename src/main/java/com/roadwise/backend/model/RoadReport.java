package com.roadwise.backend.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "reports")
public class RoadReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // --- 1. Road Details (From Frontend) ---
    private String cityRoadName;
    private String cityRoadId;
    private String roadImportance;
    private String roadType;
    private String terrainType;
    private Double width;
    private Double length;
    private Integer numberOfBridges;
    private Double lengthOfCulverts;

    // --- 2. Damage Information (From Frontend) ---
    @Column(columnDefinition = "TEXT")
    private String damageImage;

    @Column(columnDefinition = "TEXT")
    private String damageDescription;
    private Double latitude;
    private Double longitude;

    private String damageType;
    private Double damageLength;
    private Double damageWidth;

    // --- 3. System & Analytics Tracking ---
    private Integer inventoryYear;
    private String status;

    // Admin & CEO Remarks
    @Column(columnDefinition = "TEXT")
    private String adminRemarks;

    @Column(columnDefinition = "TEXT")
    private String repairRemarks;

    @Column(columnDefinition = "TEXT")
    private String proofOfRepairImage;

    // 🚀 OFFICIAL DATE CONCLUDED / ARCHIVED
    @Column(name = "date_archived")
    private LocalDateTime dateArchived;

    // 🎯 TIMELINE ESTIMATION & COMPLETION TRACKING
    @Column(name = "target_completion_date")
    private LocalDate targetCompletionDate;

    @Column(name = "actual_completion_date")
    private LocalDateTime actualCompletionDate;

    // AI Classification
    private String cvDamageClassification;
    private Double cvConfidenceScore;

    // Relationships
    @JsonIgnoreProperties({"reports", "hibernateLazyInitializer", "handler"})
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "barangay_id")
    private Barangay barangay;

    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler", "password"})
    @ManyToOne
    @JoinColumn(name = "user_id")
    private User user;

    private String reportedBy;
    private String severity;

    // 🚀 AUTOMATIC SUBMISSION DATE & INVENTORY YEAR
    @Column(name = "date_submitted")
    private LocalDate dateSubmitted;

    @PrePersist
    public void prePersist() {
        if (this.dateSubmitted == null) {
            this.dateSubmitted = LocalDate.now();
        }
        if (this.inventoryYear == null) {
            this.inventoryYear = LocalDate.now().getYear();
        }
    }

    public RoadReport() {
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

    public String getCityRoadName() {
        return cityRoadName;
    }

    public void setCityRoadName(String cityRoadName) {
        this.cityRoadName = cityRoadName;
    }

    public String getCityRoadId() {
        return cityRoadId;
    }

    public void setCityRoadId(String cityRoadId) {
        this.cityRoadId = cityRoadId;
    }

    public String getRoadImportance() {
        return roadImportance;
    }

    public void setRoadImportance(String roadImportance) {
        this.roadImportance = roadImportance;
    }

    public String getRoadType() {
        return roadType;
    }

    public void setRoadType(String roadType) {
        this.roadType = roadType;
    }

    public String getTerrainType() {
        return terrainType;
    }

    public void setTerrainType(String terrainType) {
        this.terrainType = terrainType;
    }

    public Double getWidth() {
        return width;
    }

    public void setWidth(Double width) {
        this.width = width;
    }

    public Double getLength() {
        return length;
    }

    public void setLength(Double length) {
        this.length = length;
    }

    public Integer getNumberOfBridges() {
        return numberOfBridges;
    }

    public void setNumberOfBridges(Integer numberOfBridges) {
        this.numberOfBridges = numberOfBridges;
    }

    public Double getLengthOfCulverts() {
        return lengthOfCulverts;
    }

    public void setLengthOfCulverts(Double lengthOfCulverts) {
        this.lengthOfCulverts = lengthOfCulverts;
    }

    public String getDamageImage() {
        return damageImage;
    }

    public void setDamageImage(String damageImage) {
        this.damageImage = damageImage;
    }

    public String getDamageDescription() {
        return damageDescription;
    }

    public void setDamageDescription(String damageDescription) {
        this.damageDescription = damageDescription;
    }

    public Double getLatitude() {
        return latitude;
    }

    public void setLatitude(Double latitude) {
        this.latitude = latitude;
    }

    public Double getLongitude() {
        return longitude;
    }

    public void setLongitude(Double longitude) {
        this.longitude = longitude;
    }

    public Integer getInventoryYear() {
        return inventoryYear;
    }

    public void setInventoryYear(Integer inventoryYear) {
        this.inventoryYear = inventoryYear;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getCvDamageClassification() {
        return cvDamageClassification;
    }

    public void setCvDamageClassification(String cvDamageClassification) {
        this.cvDamageClassification = cvDamageClassification;
    }

    public Double getCvConfidenceScore() {
        return cvConfidenceScore;
    }

    public void setCvConfidenceScore(Double cvConfidenceScore) {
        this.cvConfidenceScore = cvConfidenceScore;
    }

    public String getRepairRemarks() {
        return repairRemarks;
    }

    public void setRepairRemarks(String repairRemarks) {
        this.repairRemarks = repairRemarks;
    }

    public String getProofOfRepairImage() {
        return proofOfRepairImage;
    }

    public void setProofOfRepairImage(String proofOfRepairImage) {
        this.proofOfRepairImage = proofOfRepairImage;
    }

    public Barangay getBarangay() {
        return barangay;
    }

    public void setBarangay(Barangay barangay) {
        this.barangay = barangay;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public String getReportedBy() {
        return reportedBy;
    }

    public void setReportedBy(String reportedBy) {
        this.reportedBy = reportedBy;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public LocalDate getDateSubmitted() {
        return dateSubmitted;
    }

    public void setDateSubmitted(LocalDate dateSubmitted) {
        this.dateSubmitted = dateSubmitted;
    }

    public String getAdminRemarks() {
        return adminRemarks;
    }

    public void setAdminRemarks(String adminRemarks) {
        this.adminRemarks = adminRemarks;
    }

    public String getDamageType() {
        return damageType;
    }

    public void setDamageType(String damageType) {
        this.damageType = damageType;
    }

    public Double getDamageLength() {
        return damageLength;
    }

    public void setDamageLength(Double damageLength) {
        this.damageLength = damageLength;
    }

    public Double getDamageWidth() {
        return damageWidth;
    }

    public void setDamageWidth(Double damageWidth) {
        this.damageWidth = damageWidth;
    }

    public LocalDateTime getDateArchived() {
        return dateArchived;
    }

    public void setDateArchived(LocalDateTime dateArchived) {
        this.dateArchived = dateArchived;
    }

    public LocalDate getTargetCompletionDate() {
        return targetCompletionDate;
    }

    public void setTargetCompletionDate(LocalDate targetCompletionDate) {
        this.targetCompletionDate = targetCompletionDate;
    }

    public LocalDateTime getActualCompletionDate() {
        return actualCompletionDate;
    }

    public void setActualCompletionDate(LocalDateTime actualCompletionDate) {
        this.actualCompletionDate = actualCompletionDate;
    }
}