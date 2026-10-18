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

    // --- BASELINE INVENTORY METRICS ---
    @Column(name = "road_length")
    private Double roadLength = 0.0;

    @Column(name = "road_width")
    private Double roadWidth = 0.0;

    @Column(name = "length_of_culverts")
    private Double lengthOfCulverts = 0.0;

    @Column(name = "number_of_bridges")
    private Integer numberOfBridges = 0;

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

    public Double getRoadLength() { return roadLength; }
    public void setRoadLength(Double roadLength) { this.roadLength = roadLength; }

    public Double getRoadWidth() { return roadWidth; }
    public void setRoadWidth(Double roadWidth) { this.roadWidth = roadWidth; }

    public Double getLengthOfCulverts() { return lengthOfCulverts; }
    public void setLengthOfCulverts(Double lengthOfCulverts) { this.lengthOfCulverts = lengthOfCulverts; }

    public Integer getNumberOfBridges() { return numberOfBridges; }
    public void setNumberOfBridges(Integer numberOfBridges) { this.numberOfBridges = numberOfBridges; }

    public Barangay getBarangay() { return barangay; }
    public void setBarangay(Barangay barangay) { this.barangay = barangay; }
}