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

    @Autowired
    private com.roadwise.backend.repository.UserRepository userRepository;

    @Autowired
    private com.roadwise.backend.service.EmailService emailService;

    private static final String UPLOAD_DIR = "uploads/";

    // ==========================================
    // 1. CREATE REPORT
    // ==========================================
    @PostMapping(consumes = {"multipart/form-data"})
    public RoadReport createReport(
            @ModelAttribute RoadReport report,
            @RequestParam(value = "barangayId", required = false) Long barangayId,
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "imageFile", required = false) MultipartFile imageFile) {

        try {
            if (barangayId != null) {
                com.roadwise.backend.model.Barangay foundBarangay = barangayRepository.findById(barangayId).orElse(null);
                report.setBarangay(foundBarangay);
            }

            if (userId != null) {
                com.roadwise.backend.model.User foundUser = userRepository.findById(userId).orElse(null);
                report.setUser(foundUser);
                if (foundUser != null) {
                    report.setReportedBy(foundUser.getFirstName() + " " + foundUser.getLastName());
                }
            }

            Path uploadPath = Paths.get(UPLOAD_DIR);
            if (!Files.exists(uploadPath)) {
                Files.createDirectories(uploadPath);
            }

            if (imageFile != null && !imageFile.isEmpty()) {
                String originalFilename = imageFile.getOriginalFilename();
                String uniqueFilename = UUID.randomUUID().toString() + "_" + originalFilename;
                Path filePath = uploadPath.resolve(uniqueFilename);
                Files.copy(imageFile.getInputStream(), filePath);
                report.setDamageImage(uniqueFilename);
            } else {
                report.setDamageImage("no_image.jpg");
            }

            report.setStatus("Pending Validation");
            return repository.save(report);

        } catch (IOException e) {
            e.printStackTrace();
            throw new RuntimeException("Failed to save image file to the server!");
        }
    }

    @GetMapping
    public List<RoadReport> getAllReports() {
        return repository.findAll(org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "id"));
    }

    @GetMapping("/{id}")
    public org.springframework.http.ResponseEntity<RoadReport> getReportById(@PathVariable Long id) {
        return repository.findById(id)
                .map(org.springframework.http.ResponseEntity::ok)
                .orElse(org.springframework.http.ResponseEntity.notFound().build());
    }

    @GetMapping("/barangay/{barangayId}")
    public java.util.List<RoadReport> getReportsByBarangay(@PathVariable Long barangayId) {
        java.util.List<RoadReport> reports = repository.findByBarangay_Id(barangayId);
        reports.sort((a, b) -> b.getId().compareTo(a.getId()));
        return reports;
    }

    // ==========================================
    // 2. 🚀 THE EMAIL SWITCHBOARD (STATUS UPDATES)
    // ==========================================
    @PutMapping("/{id}/status")
    public org.springframework.http.ResponseEntity<String> updateReportStatus(
            @PathVariable Long id,
            @RequestBody java.util.Map<String, String> payload) {

        return repository.findById(id).map(report -> {

            String newStatus = payload.get("status");
            String adminRemarks = payload.get("adminRemarks");

            if (newStatus != null) {
                report.setStatus(newStatus);
            }

            if (adminRemarks != null) {
                report.setAdminRemarks(adminRemarks);
            }

            repository.save(report);

            // 🚀 FIRE THE AUTOMATED EMAIL HELPER
            sendStatusUpdateEmail(report, newStatus, adminRemarks);

            return org.springframework.http.ResponseEntity.ok("SUCCESS");

        }).orElse(org.springframework.http.ResponseEntity.notFound().build());
    }

    // ==========================================
    // 3. UPDATE REPORT
    // ==========================================
    @PutMapping("/update/{id}")
    public ResponseEntity<?> updateReport(@PathVariable Long id,
                                          @RequestParam(value = "damageDescription", required = false) String description,
                                          @RequestParam(value = "length", required = false) Double length,
                                          @RequestParam(value = "width", required = false) Double width,
                                          @RequestParam(value = "lengthOfCulverts", required = false) Double lengthOfCulverts,
                                          @RequestParam(value = "numberOfBridges", required = false) Integer numberOfBridges,
                                          @RequestParam(value = "latitude", required = false) Double latitude,
                                          @RequestParam(value = "longitude", required = false) Double longitude,
                                          @RequestParam(value = "damageType", required = false) String damageType,
                                          @RequestParam(value = "damageLength", required = false) Double damageLength,
                                          @RequestParam(value = "damageWidth", required = false) Double damageWidth,
                                          @RequestParam(value = "imageFile", required = false) org.springframework.web.multipart.MultipartFile imageFile) {
        try {
            RoadReport existingReport = repository.findById(id).orElseThrow(() -> new RuntimeException("Report not found"));

            if (description != null) existingReport.setDamageDescription(description);
            if (length != null) existingReport.setLength(length);
            if (width != null) existingReport.setWidth(width);
            if (lengthOfCulverts != null) existingReport.setLengthOfCulverts(lengthOfCulverts);
            if (numberOfBridges != null) existingReport.setNumberOfBridges(numberOfBridges);
            if (latitude != null) existingReport.setLatitude(latitude);
            if (longitude != null) existingReport.setLongitude(longitude);
            if (damageType != null) existingReport.setDamageType(damageType);
            if (damageLength != null) existingReport.setDamageLength(damageLength);
            if (damageWidth != null) existingReport.setDamageWidth(damageWidth);

            if (imageFile != null && !imageFile.isEmpty()) {
                String fileName = java.util.UUID.randomUUID().toString() + "_" + imageFile.getOriginalFilename();
                java.nio.file.Path filePath = java.nio.file.Paths.get("uploads", fileName);
                java.nio.file.Files.copy(imageFile.getInputStream(), filePath);
                existingReport.setDamageImage(fileName);
            }

            String currentStatus = existingReport.getStatus();
            if (currentStatus != null && currentStatus.equalsIgnoreCase("Rejected")) {
                existingReport.setStatus("Resubmitted");
            } else {
                existingReport.setStatus("Pending Validation");
            }

            existingReport.setAdminRemarks(null);
            repository.save(existingReport);

            return ResponseEntity.ok().body("Report updated successfully");

        } catch (Exception e) {
            return ResponseEntity.status(500).body("Error updating report: " + e.getMessage());
        }
    }

    // ==========================================
    // 4. 🚀 UPGRADED: BATCH DISPATCH TO CEO
    // ==========================================
    @PutMapping("/dispatch-masterlist")
    public ResponseEntity<String> dispatchMasterlistToCEO() {
        try {
            List<RoadReport> allReports = repository.findAll();
            int dispatchedCount = 0;

            // 🚀 SMART GROUPING: Group reports by the Official who submitted them
            java.util.Map<com.roadwise.backend.model.User, java.util.List<RoadReport>> dispatchedByUser = new java.util.HashMap<>();

            for (RoadReport report : allReports) {
                if ("Validated".equalsIgnoreCase(report.getStatus().trim())) {
                    report.setStatus("Dispatched to CEO");
                    dispatchedCount++;

                    if (report.getUser() != null) {
                        dispatchedByUser.computeIfAbsent(report.getUser(), k -> new java.util.ArrayList<>()).add(report);
                    }
                }
            }

            if (dispatchedCount > 0) {
                repository.saveAll(allReports);

                // 🚀 TRIGGER BATCH EMAILS
                for (java.util.Map.Entry<com.roadwise.backend.model.User, java.util.List<RoadReport>> entry : dispatchedByUser.entrySet()) {
                    sendBatchDispatchEmail(entry.getKey(), entry.getValue());
                }

                return ResponseEntity.ok("Successfully dispatched " + dispatchedCount + " prioritized reports to the CEO!");
            } else {
                return ResponseEntity.badRequest().body("No 'Validated' reports found to dispatch.");
            }
        } catch (Exception e) {
            return ResponseEntity.status(500).body("Error dispatching masterlist: " + e.getMessage());
        }
    }

    // ==========================================
    // 5. 🚀 CEO MARK AS COMPLETED
    // ==========================================
    @PostMapping(value = "/{id}/complete", consumes = {"multipart/form-data"})
    public ResponseEntity<?> completeReport(
            @PathVariable Long id,
            @RequestParam(value = "repairRemarks", required = false) String repairRemarks,
            @RequestParam(value = "proofImage", required = true) MultipartFile proofImage) {

        try {
            RoadReport report = repository.findById(id)
                    .orElseThrow(() -> new RuntimeException("Report not found"));

            if (proofImage != null && !proofImage.isEmpty()) {
                Path uploadPath = Paths.get(UPLOAD_DIR);
                if (!Files.exists(uploadPath)) Files.createDirectories(uploadPath);

                String fileName = UUID.randomUUID().toString() + "_" + proofImage.getOriginalFilename();
                Path filePath = uploadPath.resolve(fileName);
                Files.copy(proofImage.getInputStream(), filePath);

                report.setProofOfRepairImage(fileName);
            }

            if (repairRemarks != null) {
                report.setRepairRemarks(repairRemarks);
            }

            report.setStatus("Completed");
            repository.save(report);

            // 🚀 FIRE THE AUTOMATED EMAIL HELPER
            sendStatusUpdateEmail(report, "Completed", repairRemarks);

            return ResponseEntity.ok().body(java.util.Map.of("message", "Project marked as Completed!"));

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body(java.util.Map.of("error", "Error completing repair: " + e.getMessage()));
        }
    }

    // ==========================================
    // 6. 🚀 EMAIL HELPER: SINGLE STATUS UPDATE
    // ==========================================
    private void sendStatusUpdateEmail(RoadReport report, String newStatus, String remarks) {
        if (report.getUser() == null || report.getUser().getEmail() == null || report.getUser().getEmail().isEmpty()) {
            return;
        }

        String officialEmail = report.getUser().getEmail();
        String officialName = report.getUser().getFirstName();

        // 🚀 FIXED: Using getCityRoadName() and getInventoryYear()
        String trackingId = "RPT-" + report.getId() + " (Year: " + report.getInventoryYear() + ")";

        String subject = "RoadWise Update: Status changed to " + newStatus;
        StringBuilder body = new StringBuilder();

        body.append("Hello ").append(officialName).append(",\n\n");
        body.append("There is an update regarding your road report [").append(trackingId).append("] for ").append(report.getCityRoadName()).append(".\n\n");
        body.append("Current Status: ").append(newStatus.toUpperCase()).append("\n\n");

        switch (newStatus.toLowerCase()) {
            case "rejected":
                subject = "RoadWise Alert: Report Requires Revision";
                body.append("The CPDO has reviewed your report but it requires corrections. Please log in, review the remarks below, and click 'Edit & Resubmit'.\n");
                break;
            case "validated":
                body.append("Great job! The CPDO has successfully validated your report. It is now awaiting dispatch to the CEO Priority List.\n");
                break;
            case "in progress":
                body.append("The City Engineering Office (CEO) is now actively working on this road repair!\n");
                break;
            case "pending budget":
                subject = "RoadWise Update: Repair Deferred (Pending Budget)";
                body.append("The CEO has reviewed the priority list. Due to current budget constraints, immediate repair for this road has been deferred. It remains securely in our system for future fiscal allocation.\n");
                break;
            case "completed":
                subject = "RoadWise Update: Repair Pending Admin QA";
                body.append("The City Engineering Office (CEO) has marked this repair as completed!.\n");
                break;
            default:
                body.append("The status of your report has been updated.\n");
        }

        if (remarks != null && !remarks.trim().isEmpty()) {
            body.append("\nRemarks: ").append(remarks).append("\n");
        }

        body.append("\nPlease log into your RoadWise Dashboard to view full details.\n\n");
        body.append("Best regards,\nRoadWise SJDM System");

        emailService.sendEmail(officialEmail, subject, body.toString());
    }

    // ==========================================
    // 7. 🚀 EMAIL HELPER: BATCH DISPATCH LIST
    // ==========================================
    private void sendBatchDispatchEmail(com.roadwise.backend.model.User official, List<RoadReport> dispatchedReports) {
        if (official.getEmail() == null || official.getEmail().isEmpty()) return;

        String subject = "RoadWise: " + dispatchedReports.size() + " Reports Dispatched to CEO Priority List";
        StringBuilder body = new StringBuilder();

        body.append("Hello ").append(official.getFirstName()).append(",\n\n");
        body.append("Good news! The CPDO has officially forwarded ").append(dispatchedReports.size())
                .append(" of your validated road reports to the City Engineering Office (CEO) Priority List.\n\n");

        body.append("Dispatched Roads:\n");
        for (RoadReport r : dispatchedReports) {
            // Safely fetch severity or default to "Unassigned"
            String severity = r.getSeverity() != null ? r.getSeverity() : "Unassigned";
            body.append("- ").append(r.getCityRoadName()).append(" (Severity: ").append(severity).append(")\n");
        }

        body.append("\nThe CEO will review this masterlist and allocate repair budgets accordingly. You will receive further updates once physical repairs begin.\n\n");
        body.append("Best regards,\nRoadWise SJDM System");

        emailService.sendEmail(official.getEmail(), subject, body.toString());
    }
}