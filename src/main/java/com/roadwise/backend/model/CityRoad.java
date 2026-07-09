package com.roadwise.backend.model;

import jakarta.persistence.*;

@Entity
@Table(name = "city_roads")
public class CityRoad {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id; // The computer's internal ID

    private String roadId; // The human-readable ID (e.g., "RD-001")
    private String roadName;
    private String roadImportance; // Core, Non-Core
    private String terrainType;    // Flat, Mountainous, Rolling
    private String roadType;       // Asphalt, Gravel, Earth, Concrete, Mixed

    // This creates the "barangay_id" Foreign Key column in the database automatically!
    @ManyToOne
    @JoinColumn(name = "barangay_id", nullable = false)
    private Barangay barangay;

    public CityRoad() {
    }

    // --- GETTERS AND SETTERS ---
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getRoadId() { return roadId; }
    public void setRoadId(String roadId) { this.roadId = roadId; }

    public String getRoadName() { return roadName; }
    public void setRoadName(String roadName) { this.roadName = roadName; }

    public String getRoadImportance() { return roadImportance; }
    public void setRoadImportance(String roadImportance) { this.roadImportance = roadImportance; }

    public String getTerrainType() { return terrainType; }
    public void setTerrainType(String terrainType) { this.terrainType = terrainType; }

    public String getRoadType() { return roadType; }
    public void setRoadType(String roadType) { this.roadType = roadType; }

    public Barangay getBarangay() { return barangay; }
    public void setBarangay(Barangay barangay) { this.barangay = barangay; }
}