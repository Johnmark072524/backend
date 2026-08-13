package com.roadwise.backend.controller;

import com.roadwise.backend.model.Barangay;
import com.roadwise.backend.model.CityRoad;
import com.roadwise.backend.repository.BarangayRepository;
import com.roadwise.backend.repository.CityRoadRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/roads") // Kept your exact mapping!
@CrossOrigin(origins = "${frontend.url}")
public class CityRoadController {

    @Autowired
    private CityRoadRepository repository;

    @Autowired
    private CityRoadRepository cityRoadRepository;

    // 🚀 NEW: We need this to link the new road to the correct Barangay
    @Autowired
    private BarangayRepository barangayRepository;

    // ==========================================
    // 1. GET ALL ROADS (Original)
    // ==========================================
    @GetMapping
    public List<CityRoad> getAllRoads() {
        return repository.findAll();
    }

    // ==========================================
    // 2. GET ROADS BY BARANGAY (Original)
    // ==========================================
    @GetMapping("/barangay/{barangayId}")
    public ResponseEntity<List<CityRoad>> getRoadsByBarangay(@PathVariable Long barangayId) {
        // Using your exact repository method name
        List<CityRoad> roads = repository.findByBarangayId(barangayId);

        if (roads.isEmpty()) {
            return ResponseEntity.ok(java.util.Collections.emptyList());
        }

        return ResponseEntity.ok(roads);
    }

    // ==========================================
    // ADD NEW CITY ROAD (WITH VALIDATION & SMART AUTO-ID)
    // ==========================================
    @PostMapping
    public ResponseEntity<?> createCityRoad(@RequestBody CityRoad cityRoad) {
        // 1. Ensure a Barangay is attached
        if (cityRoad.getBarangay() == null || cityRoad.getBarangay().getId() == null) {
            return ResponseEntity.badRequest().body("A valid Barangay ID is required to register a road.");
        }

        // 2. Clean the input (removes accidental spaces)
        String cleanedName = cityRoad.getRoadName().trim();
        Long brgyId = cityRoad.getBarangay().getId();

        // 3. 🚀 VALIDATION: Prevent duplicate roads in the SAME barangay
        if (cityRoadRepository.existsByRoadNameIgnoreCaseAndBarangayId(cleanedName, brgyId)) {
            return ResponseEntity.badRequest().body("A road named '" + cleanedName + "' is already registered in this Barangay.");
        }

        cityRoad.setRoadName(cleanedName);

        // 4. THE SMART AUTO-GENERATOR LOGIC
        CityRoad lastRoad = cityRoadRepository.findFirstByOrderByIdDesc();

        // A fallback default just in case your database is completely empty
        String newRoadId = "3142000000 01";

        if (lastRoad != null && lastRoad.getRoadId() != null) {
            String lastId = lastRoad.getRoadId().trim();

            // Check if it follows your format with a space (e.g., "3142000004 06")
            if (lastId.contains(" ")) {
                try {
                    int spaceIndex = lastId.lastIndexOf(" ");
                    String prefix = lastId.substring(0, spaceIndex);
                    String suffix = lastId.substring(spaceIndex + 1);

                    int nextNumber = Integer.parseInt(suffix) + 1;
                    int paddingLength = suffix.length();
                    newRoadId = String.format("%s %0" + paddingLength + "d", prefix, nextNumber);

                } catch (Exception e) {
                    System.out.println("Could not parse road ID mathematically, falling back to default.");
                }
            }
        }

        cityRoad.setRoadId(newRoadId);

        // 5. Save to database
        CityRoad savedRoad = cityRoadRepository.save(cityRoad);
        return ResponseEntity.ok(savedRoad);
    }

    // ==========================================
    // GET A SINGLE CITY ROAD (For filling the Edit Modal)
    // ==========================================
    @GetMapping("/{id}")
    public ResponseEntity<CityRoad> getCityRoadById(@PathVariable Long id) {
        return cityRoadRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // ==========================================
    // UPDATE AN EXISTING CITY ROAD (With smart validation)
    // ==========================================
    @PutMapping("/{id}")
    public ResponseEntity<?> updateCityRoad(@PathVariable Long id, @RequestBody CityRoad updatedInfo) {
        // 1. Find the exact road in the database
        Optional<CityRoad> existingOpt = cityRoadRepository.findById(id);
        if (existingOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        CityRoad existingRoad = existingOpt.get();
        String cleanedName = updatedInfo.getRoadName().trim();
        Long brgyId = updatedInfo.getBarangay().getId();

        // 2. 🚀 SMART VALIDATION: Check if ANOTHER road in this barangay already has this name
        if (cityRoadRepository.existsByRoadNameIgnoreCaseAndBarangayIdAndIdNot(cleanedName, brgyId, id)) {
            return ResponseEntity.badRequest().body("Another road named '" + cleanedName + "' already exists in this Barangay.");
        }

        // 3. Update the fields
        existingRoad.setRoadName(cleanedName);
        existingRoad.setRoadImportance(updatedInfo.getRoadImportance());
        existingRoad.setRoadType(updatedInfo.getRoadType());
        existingRoad.setTerrainType(updatedInfo.getTerrainType());

        // Note: We strictly DO NOT update the Road Sequence ID here. It remains securely locked.

        // 4. Save and return
        CityRoad savedRoad = cityRoadRepository.save(existingRoad);
        return ResponseEntity.ok(savedRoad);
    }

}