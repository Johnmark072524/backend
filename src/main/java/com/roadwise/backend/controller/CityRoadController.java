package com.roadwise.backend.controller;

import com.roadwise.backend.model.Barangay;
import com.roadwise.backend.model.CityRoad;
import com.roadwise.backend.model.User;
import com.roadwise.backend.repository.BarangayRepository;
import com.roadwise.backend.repository.CityRoadRepository;
import com.roadwise.backend.repository.UserRepository;
import com.roadwise.backend.service.ActivityLogService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/roads")
@CrossOrigin(origins = "${frontend.url}")
public class CityRoadController {

    @Autowired
    private CityRoadRepository cityRoadRepository;

    @Autowired
    private BarangayRepository barangayRepository;

    @Autowired
    private UserRepository userRepository;

    // 🚀 INJECTED ACTIVITY LOG AUDIT SERVICE
    @Autowired
    private ActivityLogService activityLogService;

    // ==========================================
    // 🛡️ CONTROL 6 RBAC: CPDO ADMIN VERIFICATION
    // ==========================================
    private Long getAdminId() {
        return userRepository.findAll().stream()
                .filter(user -> user.getRole() != null && (user.getRole().equalsIgnoreCase("CPDO Admin") || user.getRole().equalsIgnoreCase("Admin")))
                .map(User::getId)
                .findFirst()
                .orElse(1L);
    }

    private boolean isCpdoAdmin(User user) {
        if (user == null || user.getRole() == null) return false;
        String r = user.getRole().trim();
        return r.equalsIgnoreCase("CPDO Admin") || r.equalsIgnoreCase("Admin");
    }

    // ==========================================
    // 1. GET ALL ROADS (City-wide CPDO & CEO Oversight)
    // ==========================================
    @GetMapping
    public List<CityRoad> getAllRoads() {
        return cityRoadRepository.findAll();
    }

    // ==========================================
    // 2. GET ROADS BY BARANGAY (Scoped to Assigned Barangay)
    // ==========================================
    @GetMapping("/barangay/{barangayId}")
    public ResponseEntity<List<CityRoad>> getRoadsByBarangay(@PathVariable Long barangayId) {
        List<CityRoad> roads = cityRoadRepository.findByBarangayId(barangayId);

        if (roads == null || roads.isEmpty()) {
            return ResponseEntity.ok(Collections.emptyList());
        }

        return ResponseEntity.ok(roads);
    }

    // ==========================================
    // 3. ADD NEW CITY ROAD (WITH RBAC, VALIDATION & SMART AUTO-ID)
    // ==========================================
    @PostMapping
    public ResponseEntity<?> createCityRoad(
            @RequestBody CityRoad cityRoad,
            @RequestParam(value = "userId", required = false) Long userId,
            HttpServletRequest request) {

        // 🛡️ CONTROL 6 RBAC: Admin Verification
        Long adminId = (userId != null) ? userId : getAdminId();
        User adminActor = userRepository.findById(adminId).orElse(null);
        if (!isCpdoAdmin(adminActor)) {
            return ResponseEntity.status(403).body("Access Denied: Only CPDO Administrators can register new city roads.");
        }

        if (cityRoad == null || cityRoad.getRoadName() == null || cityRoad.getRoadName().trim().isEmpty()) {
            return ResponseEntity.badRequest().body("Road name is mandatory.");
        }

        if (cityRoad.getBarangay() == null || cityRoad.getBarangay().getId() == null) {
            return ResponseEntity.badRequest().body("A valid Barangay ID is required to register a road.");
        }

        String cleanedName = cityRoad.getRoadName().trim();
        Long brgyId = cityRoad.getBarangay().getId();

        if (cityRoadRepository.existsByRoadNameIgnoreCaseAndBarangayId(cleanedName, brgyId)) {
            return ResponseEntity.badRequest().body("A road named '" + cleanedName + "' is already registered in this Barangay.");
        }

        cityRoad.setRoadName(cleanedName);

        // Ensure full entity mapping
        Barangay assignedBarangay = barangayRepository.findById(brgyId).orElse(null);
        if (assignedBarangay != null) {
            cityRoad.setBarangay(assignedBarangay);
        }

        // Smart Auto-Generator Logic
        CityRoad lastRoad = cityRoadRepository.findFirstByOrderByIdDesc();
        String newRoadId = "3142000000 01";

        if (lastRoad != null && lastRoad.getRoadId() != null) {
            String lastId = lastRoad.getRoadId().trim();

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
        CityRoad savedRoad = cityRoadRepository.save(cityRoad);

        // ⏱️ AUDIT LOG: Attributed to Admin under SYSTEM category
        String brgyName = (assignedBarangay != null) ? assignedBarangay.getBarangayName() : "Barangay #" + brgyId;

        activityLogService.log(
                adminActor,
                "SYSTEM",
                "CITY_ROAD_CREATED",
                savedRoad.getRoadId(),
                "Registered new city road: '" + cleanedName + "' under " + brgyName + ".",
                "SUCCESS",
                request
        );

        return ResponseEntity.ok(savedRoad);
    }

    // ==========================================
    // 4. GET A SINGLE CITY ROAD
    // ==========================================
    @GetMapping("/{id}")
    public ResponseEntity<CityRoad> getCityRoadById(@PathVariable Long id) {
        return cityRoadRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // ==========================================
    // 5. UPDATE AN EXISTING CITY ROAD (WITH RBAC & AUDIT LOG)
    // ==========================================
    @PutMapping("/{id}")
    public ResponseEntity<?> updateCityRoad(
            @PathVariable Long id,
            @RequestBody CityRoad updatedInfo,
            @RequestParam(value = "userId", required = false) Long userId,
            HttpServletRequest request) {

        // 🛡️ CONTROL 6 RBAC: Admin Verification
        Long adminId = (userId != null) ? userId : getAdminId();
        User adminActor = userRepository.findById(adminId).orElse(null);
        if (!isCpdoAdmin(adminActor)) {
            return ResponseEntity.status(403).body("Access Denied: Only CPDO Administrators can update city road details.");
        }

        Optional<CityRoad> existingOpt = cityRoadRepository.findById(id);
        if (existingOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        if (updatedInfo == null || updatedInfo.getRoadName() == null || updatedInfo.getRoadName().trim().isEmpty()) {
            return ResponseEntity.badRequest().body("Road name cannot be empty.");
        }

        CityRoad existingRoad = existingOpt.get();
        String cleanedName = updatedInfo.getRoadName().trim();
        Long brgyId = (updatedInfo.getBarangay() != null && updatedInfo.getBarangay().getId() != null)
                ? updatedInfo.getBarangay().getId()
                : (existingRoad.getBarangay() != null ? existingRoad.getBarangay().getId() : null);

        if (brgyId != null && cityRoadRepository.existsByRoadNameIgnoreCaseAndBarangayIdAndIdNot(cleanedName, brgyId, id)) {
            return ResponseEntity.badRequest().body("Another road named '" + cleanedName + "' already exists in this Barangay.");
        }

        existingRoad.setRoadName(cleanedName);
        existingRoad.setRoadImportance(updatedInfo.getRoadImportance());
        existingRoad.setRoadType(updatedInfo.getRoadType());
        existingRoad.setTerrainType(updatedInfo.getTerrainType());

        CityRoad savedRoad = cityRoadRepository.save(existingRoad);

        // ⏱️ AUDIT LOG
        activityLogService.log(
                adminActor,
                "SYSTEM",
                "CITY_ROAD_UPDATED",
                savedRoad.getRoadId() != null ? savedRoad.getRoadId() : ("#ROAD-" + savedRoad.getId()),
                "Updated specifications for road: '" + cleanedName + "'.",
                "SUCCESS",
                request
        );

        return ResponseEntity.ok(savedRoad);
    }
}