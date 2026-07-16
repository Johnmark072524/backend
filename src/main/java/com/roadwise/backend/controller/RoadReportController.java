package com.roadwise.backend.controller;

import com.roadwise.backend.model.RoadReport;
import com.roadwise.backend.repository.RoadReportRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;

@RestController
@RequestMapping("/api/reports")
@CrossOrigin(origins = "${frontend.url}")
public class RoadReportController {

    @Autowired
    private RoadReportRepository repository;

    @Autowired
    private com.roadwise.backend.repository.BarangayRepository barangayRepository;

    // We will save uploaded photos into a folder named "uploads" in your project folder
    private static final String UPLOAD_DIR = "uploads/";

    // Accepts 'multipart/form-data' (Files + Text) instead of JSON
    @PostMapping(consumes = {"multipart/form-data"})
    public RoadReport createReport(
            @ModelAttribute RoadReport report,
            // ⬇️ NEW: EXPLICITLY CATCH THE BARANGAY ID FROM THE FRONTEND ⬇️
            @RequestParam(value = "barangayId", required = false) Long barangayId,
            @RequestParam(value = "imageFile", required = false) MultipartFile imageFile) {

        try {
            // --- NEW: MANUALLY ATTACH THE BARANGAY ---
            // This searches the database for the ID (e.g., 64) and attaches "Minuyan Proper"
            if (barangayId != null) {
                com.roadwise.backend.model.Barangay foundBarangay = barangayRepository.findById(barangayId).orElse(null);
                report.setBarangay(foundBarangay);
            }

            // 1. Create the 'uploads' folder if it doesn't exist yet
            Path uploadPath = Paths.get(UPLOAD_DIR);
            if (!Files.exists(uploadPath)) {
                Files.createDirectories(uploadPath);
            }

            // 2. Handle the Image File
            if (imageFile != null && !imageFile.isEmpty()) {
                // Generate a unique ID for the image so two files named "pothole.jpg" don't overwrite each other!
                String originalFilename = imageFile.getOriginalFilename();
                String uniqueFilename = UUID.randomUUID().toString() + "_" + originalFilename;

                // Save the physical picture to your hard drive
                Path filePath = uploadPath.resolve(uniqueFilename);
                Files.copy(imageFile.getInputStream(), filePath);

                // Save JUST the unique name to PostgreSQL so we can find it later
                report.setDamageImage(uniqueFilename);
            } else {
                report.setDamageImage("no_image.jpg");
            }

            // 3. Save the text data to PostgreSQL
            report.setStatus("Pending Validation"); // Updated to match your frontend badge!
            return repository.save(report);

        } catch (IOException e) {
            e.printStackTrace();
            throw new RuntimeException("Failed to save image file to the server!");
        }
    }

    @GetMapping
    public List<RoadReport> getAllReports() {
        return repository.findAll();
    }

    @GetMapping("/{id}")
    public org.springframework.http.ResponseEntity<RoadReport> getReportById(@PathVariable Long id) {
        return repository.findById(id)
                .map(org.springframework.http.ResponseEntity::ok)
                .orElse(org.springframework.http.ResponseEntity.notFound().build());
    }
    // ⬇️ NEW ENDPOINT: Updates a report's status (Accept or Reject)
    // ⬇️ THE ULTIMATE BYPASS ENDPOINT ⬇️
    // ⬇️ UPGRADED BYPASS ENDPOINT: Now handles Rejection Remarks! ⬇️
    @PutMapping("/{id}/status")
    public org.springframework.http.ResponseEntity<String> updateReportStatus(
            @PathVariable Long id,
            @RequestBody java.util.Map<String, String> payload) {

        return repository.findById(id).map(report -> {

            // 1. Always update the status (e.g., "Validated" or "Rejected")
            if (payload.containsKey("status")) {
                report.setStatus(payload.get("status"));
            }

            // 2. If the frontend sent feedback remarks, save them!
            if (payload.containsKey("adminRemarks")) {
                report.setAdminRemarks(payload.get("adminRemarks"));
            }

            // 3. Save to database and return text
            repository.save(report);
            return org.springframework.http.ResponseEntity.ok("SUCCESS");

        }).orElse(org.springframework.http.ResponseEntity.notFound().build());
    }

    @GetMapping("/barangay/{barangayId}")
    public java.util.List<RoadReport> getReportsByBarangay(@PathVariable Long barangayId) {
        return repository.findByBarangay_Id(barangayId);
    }

    @PutMapping("/update/{id}")
    public ResponseEntity<?> updateReport(@PathVariable Long id,
                                          @RequestParam(value = "damageDescription", required = false) String description,
                                          @RequestParam(value = "length", required = false) Double length,
                                          @RequestParam(value = "width", required = false) Double width,
                                          @RequestParam(value = "lengthOfCulverts", required = false) Double lengthOfCulverts,
                                          @RequestParam(value = "numberOfBridges", required = false) Integer numberOfBridges,

                                          @RequestParam(value = "latitude", required = false) Double latitude,
                                          @RequestParam(value = "longitude", required = false) Double longitude,

                                          // ⬇️ CATCH THE 3 NEW DAMAGE FIELDS HERE ⬇️
                                          @RequestParam(value = "damageType", required = false) String damageType,
                                          @RequestParam(value = "damageLength", required = false) Double damageLength,
                                          @RequestParam(value = "damageWidth", required = false) Double damageWidth,

                                          @RequestParam(value = "imageFile", required = false) org.springframework.web.multipart.MultipartFile imageFile) {
        try {
            RoadReport existingReport = repository.findById(id).orElseThrow(() -> new RuntimeException("Report not found"));

            // Update all the original fields
            if (description != null) existingReport.setDamageDescription(description);
            if (length != null) existingReport.setLength(length);
            if (width != null) existingReport.setWidth(width);
            if (lengthOfCulverts != null) existingReport.setLengthOfCulverts(lengthOfCulverts);
            if (numberOfBridges != null) existingReport.setNumberOfBridges(numberOfBridges);

            if (latitude != null) existingReport.setLatitude(latitude);
            if (longitude != null) existingReport.setLongitude(longitude);

            // ⬇️ SAVE THE 3 NEW DAMAGE FIELDS TO THE DATABASE ⬇️
            if (damageType != null) existingReport.setDamageType(damageType);
            if (damageLength != null) existingReport.setDamageLength(damageLength);
            if (damageWidth != null) existingReport.setDamageWidth(damageWidth);

            // Handle Image Upload
            if (imageFile != null && !imageFile.isEmpty()) {
                String fileName = java.util.UUID.randomUUID().toString() + "_" + imageFile.getOriginalFilename();
                java.nio.file.Path filePath = java.nio.file.Paths.get("uploads", fileName);
                java.nio.file.Files.copy(imageFile.getInputStream(), filePath);
                existingReport.setDamageImage(fileName);
            }

            existingReport.setStatus("Pending Validation");
            existingReport.setAdminRemarks(null);

            repository.save(existingReport);
            return ResponseEntity.ok().body("Report updated successfully");

        } catch (Exception e) {
            return ResponseEntity.status(500).body("Error updating report: " + e.getMessage());
        }
    }

}