package com.roadwise.backend.controller;

import com.roadwise.backend.model.Barangay;
import com.roadwise.backend.model.RoadReport;
import com.roadwise.backend.model.User;
import com.roadwise.backend.repository.BarangayRepository;
import com.roadwise.backend.repository.CityRoadRepository;
import com.roadwise.backend.repository.RoadReportRepository;
import com.roadwise.backend.repository.UserRepository;
import com.roadwise.backend.service.ActivityLogService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/barangays")
@CrossOrigin(origins = "${frontend.url}")
public class BarangayController {

    @Autowired
    private BarangayRepository barangayRepository;

    @Autowired
    private CityRoadRepository cityRoadRepository;

    @Autowired
    private RoadReportRepository roadReportRepository;

    @Autowired
    private UserRepository userRepository;

    // 🚀 INJECTED ACTIVITY LOG AUDIT SERVICE
    @Autowired
    private ActivityLogService activityLogService;

    // ==========================================
    // 🚀 HELPER: DYNAMICALLY FIND ADMIN ID
    // ==========================================
    private Long getAdminId() {
        return userRepository.findAll().stream()
                .filter(user -> user.getRole() != null && (user.getRole().equalsIgnoreCase("CPDO Admin") || user.getRole().equalsIgnoreCase("Admin")))
                .map(User::getId)
                .findFirst()
                .orElse(1L);
    }

    // ==========================================
    // 1. ADD NEW BARANGAY (WITH VALIDATION)
    // ==========================================
    @PostMapping
    public ResponseEntity<?> createBarangay(
            @RequestBody Barangay barangay,
            @RequestParam(value = "userId", required = false) Long userId,
            HttpServletRequest request) {

        String cleanedName = barangay.getBarangayName().trim();

        if (barangayRepository.existsByBarangayNameIgnoreCase(cleanedName)) {
            return ResponseEntity.badRequest().body("A Barangay named '" + cleanedName + "' already exists in the system.");
        }

        barangay.setBarangayName(cleanedName);
        Barangay savedBarangay = barangayRepository.save(barangay);

        // ⏱️ AUDIT LOG: Attributed to Admin
        Long adminId = (userId != null) ? userId : getAdminId();
        User adminActor = userRepository.findById(adminId).orElse(null);

        activityLogService.log(
                adminActor,
                "USER",
                "BARANGAY_CREATED",
                "#BRGY-" + savedBarangay.getId(),
                "Registered new Barangay territorial unit: '" + cleanedName + "'.",
                "SUCCESS",
                request
        );

        return ResponseEntity.ok(savedBarangay);
    }

    @GetMapping
    public ResponseEntity<List<Barangay>> getAllBarangays() {
        List<Barangay> barangays = barangayRepository.findAll();
        return ResponseEntity.ok(barangays);
    }

    // ==========================================
    // FETCH SPECIFIC BARANGAY DETAILS & ATTACH USER INFO
    // ==========================================
    @GetMapping("/{id}")
    public ResponseEntity<Barangay> getBarangayById(@PathVariable Long id) {
        Optional<Barangay> brgyOpt = barangayRepository.findById(id);

        if (brgyOpt.isPresent()) {
            Barangay brgy = brgyOpt.get();

            List<User> officials = userRepository.findByBarangayId(id);

            if (!officials.isEmpty()) {
                User official = officials.get(0);
                brgy.setBrgyCaptain(official.getFirstName() + " " + official.getLastName());
                brgy.setContactNumber(official.getPhoneNumber());
                brgy.setEmailAddress(official.getEmail());
            } else {
                brgy.setBrgyCaptain("Unassigned");
                brgy.setContactNumber("N/A");
                brgy.setEmailAddress("N/A");
            }

            return ResponseEntity.ok(brgy);
        }
        return ResponseEntity.notFound().build();
    }

    // ==========================================
    // DASHBOARD SUMMARY (WITH USER INFO)
    // ==========================================
    @GetMapping("/dashboard-summary")
    public ResponseEntity<List<Map<String, Object>>> getDashboardSummary() {
        List<Barangay> barangays = barangayRepository.findAll();
        List<Map<String, Object>> summaryList = new ArrayList<>();

        for (Barangay brgy : barangays) {
            Map<String, Object> map = new HashMap<>();
            map.put("id", brgy.getId());
            map.put("name", brgy.getBarangayName());

            List<User> officials = userRepository.findByBarangayId(brgy.getId());
            if (!officials.isEmpty()) {
                User official = officials.get(0);
                map.put("contactName", official.getFirstName() + " " + official.getLastName());
            } else {
                map.put("contactName", "Unassigned");
            }

            int roadCount = cityRoadRepository.findByBarangayId(brgy.getId()).size();
            map.put("roadCount", roadCount);

            List<RoadReport> reports = roadReportRepository.findByBarangay_Id(brgy.getId());
            long activeCount = 0;
            for (RoadReport r : reports) {
                String status = r.getStatus() != null ? r.getStatus().toLowerCase() : "";
                if (!status.equals("closed") && !status.equals("completed") && !status.equals("rejected")) {
                    activeCount++;
                }
            }
            map.put("activeReportCount", activeCount);

            summaryList.add(map);
        }

        return ResponseEntity.ok(summaryList);
    }

    // ==========================================
    // UPDATE / RENAME A BARANGAY
    // ==========================================
    @PutMapping("/{id}")
    public ResponseEntity<?> updateBarangay(
            @PathVariable Long id,
            @RequestBody Barangay updatedInfo,
            @RequestParam(value = "userId", required = false) Long userId,
            HttpServletRequest request) {

        Optional<Barangay> existingOpt = barangayRepository.findById(id);
        if (existingOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Barangay existingBarangay = existingOpt.get();
        String cleanedName = updatedInfo.getBarangayName().trim();

        if (barangayRepository.existsByBarangayNameIgnoreCaseAndIdNot(cleanedName, id)) {
            return ResponseEntity.badRequest().body("A Barangay named '" + cleanedName + "' already exists.");
        }

        existingBarangay.setBarangayName(cleanedName);
        Barangay savedBarangay = barangayRepository.save(existingBarangay);

        // ⏱️ AUDIT LOG: Attributed to Admin
        Long adminId = (userId != null) ? userId : getAdminId();
        User adminActor = userRepository.findById(adminId).orElse(null);

        activityLogService.log(
                adminActor,
                "USER",
                "BARANGAY_UPDATED",
                "#BRGY-" + savedBarangay.getId(),
                "Updated Barangay territorial details/name to '" + cleanedName + "'.",
                "SUCCESS",
                request
        );

        return ResponseEntity.ok(savedBarangay);
    }
}