package com.roadwise.backend.controller;

import com.roadwise.backend.model.Barangay;
import com.roadwise.backend.model.RoadReport;
import com.roadwise.backend.model.User;
import com.roadwise.backend.repository.BarangayRepository;
import com.roadwise.backend.repository.CityRoadRepository;
import com.roadwise.backend.repository.RoadReportRepository;
import com.roadwise.backend.repository.UserRepository;
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

    // 🚀 NEW: We need this to get the Official's name from the User table!
    @Autowired
    private UserRepository userRepository;

    // ==========================================
    // 1. ADD NEW BARANGAY (WITH VALIDATION)
    // ==========================================
    @PostMapping
    public ResponseEntity<?> createBarangay(@RequestBody Barangay barangay) {
        // Clean the input (removes accidental spaces at the beginning or end)
        String cleanedName = barangay.getBarangayName().trim();

        // 🚀 VALIDATION: Check if it already exists in the database
        if (barangayRepository.existsByBarangayNameIgnoreCase(cleanedName)) {
            // Return an HTTP 400 Bad Request with our custom error message
            return ResponseEntity.badRequest().body("A Barangay named '" + cleanedName + "' already exists in the system.");
        }

        // If it passes the check, save it!
        barangay.setBarangayName(cleanedName);
        Barangay savedBarangay = barangayRepository.save(barangay);
        return ResponseEntity.ok(savedBarangay);
    }

    @GetMapping
    public ResponseEntity<List<Barangay>> getAllBarangays() {
        List<Barangay> barangays = barangayRepository.findAll();
        return ResponseEntity.ok(barangays);
    }

    // ==========================================
    // 🚀 THE FIX: FETCH SPECIFIC BARANGAY DETAILS & ATTACH USER INFO
    // ==========================================
    @GetMapping("/{id}")
    public ResponseEntity<Barangay> getBarangayById(@PathVariable Long id) {
        Optional<Barangay> brgyOpt = barangayRepository.findById(id);

        if (brgyOpt.isPresent()) {
            Barangay brgy = brgyOpt.get();

            // Search the users table for whoever is assigned to this Barangay
            List<User> officials = userRepository.findByBarangayId(id);

            if (!officials.isEmpty()) {
                // If we found an official, temporarily attach their User details to the Barangay object for the frontend!
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
    // 🚀 THE FIX: SMART DASHBOARD SUMMARY (WITH USER INFO)
    // ==========================================
    @GetMapping("/dashboard-summary")
    public ResponseEntity<List<Map<String, Object>>> getDashboardSummary() {
        List<Barangay> barangays = barangayRepository.findAll();
        List<Map<String, Object>> summaryList = new ArrayList<>();

        for (Barangay brgy : barangays) {
            Map<String, Object> map = new HashMap<>();
            map.put("id", brgy.getId());
            map.put("name", brgy.getBarangayName());

            // Search for the Official in the User table
            List<User> officials = userRepository.findByBarangayId(brgy.getId());
            if (!officials.isEmpty()) {
                User official = officials.get(0);
                map.put("contactName", official.getFirstName() + " " + official.getLastName());
            } else {
                map.put("contactName", "Unassigned");
            }

            // Count Registered Roads
            int roadCount = cityRoadRepository.findByBarangayId(brgy.getId()).size();
            map.put("roadCount", roadCount);

            // Count Active Reports
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
    public ResponseEntity<?> updateBarangay(@PathVariable Long id, @RequestBody Barangay updatedInfo) {
        Optional<Barangay> existingOpt = barangayRepository.findById(id);
        if (existingOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Barangay existingBarangay = existingOpt.get();
        String cleanedName = updatedInfo.getBarangayName().trim();

        // Check if ANOTHER barangay already has this exact name
        if (barangayRepository.existsByBarangayNameIgnoreCaseAndIdNot(cleanedName, id)) {
            return ResponseEntity.badRequest().body("A Barangay named '" + cleanedName + "' already exists.");
        }

        // Apply changes and save
        existingBarangay.setBarangayName(cleanedName);
        Barangay savedBarangay = barangayRepository.save(existingBarangay);

        return ResponseEntity.ok(savedBarangay);
    }
}