package com.roadwise.backend.model;

import jakarta.persistence.*;

import java.time.LocalDate;

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

    // This holds the Admin's rejection feedback!
    @Column(columnDefinition = "TEXT")
    private String adminRemarks;

    // 🚀 NEW: CEO Repair Proof Tracking
    @Column(columnDefinition = "TEXT")
    private String repairRemarks;
    private String proofOfRepairImage;

    // ⬇️ ADD THIS EXACT LINE TO FIX THE JSON INFINITE LOOP ⬇️
    // Keep ONLY this relationship in your model
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties({"reports", "hibernateLazyInitializer", "handler"})// ⬇️ THIS IS THE NEW LINE ⬇️
    @ManyToOne(fetch = FetchType.EAGER)                    // ⬇️ FORCE IT TO LOAD ⬇️
    @JoinColumn(name = "barangay_id")
    private Barangay barangay;

    private String severity;
    private java.time.LocalDate dateSubmitted;
    // This tells Spring Boot: "Right before you save to PostgreSQL, grab today's date!"
    @PrePersist
    public void prePersist() {
        this.dateSubmitted = java.time.LocalDate.now();
    }

    // Empty constructor required by Spring
    public RoadReport() {
    }

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

    private String cvDamageClassification;
    private Double cvConfidenceScore;

    public String getCvDamageClassification() {
        return cvDamageClassification;
    }

    public void setCvDamageClassification(String cvDamageClassification) {
        this.cvDamageClassification = cvDamageClassification;
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

    public Double getCvConfidenceScore() {
        return cvConfidenceScore;
    }

    public void setCvConfidenceScore(Double cvConfidenceScore) {
        this.cvConfidenceScore = cvConfidenceScore;
    }

    public Barangay getBarangay() {
        return barangay;
    }

    public void setBarangay(Barangay barangay) {
        this.barangay = barangay;
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
}


