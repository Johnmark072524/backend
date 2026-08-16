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

    // 🚀 INJECTED NOTIFICATION SERVICE
    @Autowired
    private com.roadwise.backend.service.NotificationService notificationService;

    private static final String UPLOAD_DIR = "uploads/";

    // ==========================================
    // 🚀 HELPER: DYNAMICALLY FIND ADMIN ID
    // ==========================================
    private Long getAdminId() {
        return userRepository.findAll().stream()
                .filter(user -> user.getRole() != null && (user.getRole().equalsIgnoreCase("CPDO Admin") || user.getRole().equalsIgnoreCase("Admin")))
                .map(user -> user.getId())
                .findFirst()
                .orElse(1L); // Fallback to 1 if no admin is found
    }

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

            // Save the report first to get the ID
            RoadReport savedReport = repository.save(report);

            // ==========================================
            // 🔔 SMART NOTIFICATION TRIGGER: ADMIN & BARANGAY
            // ==========================================
            Long adminId = getAdminId();

            // 1. Notify the Admin
            if (savedReport.getSeverity() != null && savedReport.getSeverity().equalsIgnoreCase("High")) {
                notificationService.sendNotification(
                        adminId,
                        "🚨 CRITICAL HAZARD DETECTED",
                        "System flagged a HIGH severity road damage reported by " + savedReport.getReportedBy() + ". Immediate CPDO review required!",
                        "CRITICAL"
                );
            } else {
                notificationService.sendNotification(
                        adminId,
                        "New Road Damage Report",
                        "A new report has been submitted by " + savedReport.getReportedBy() + " and is awaiting CPDO review.",
                        "REPORT"
                );
            }

            // 2. Notify the Barangay Official (Submitter)
            if (savedReport.getUser() != null) {
                notificationService.sendNotification(
                        savedReport.getUser().getId(),
                        "Report Submitted Successfully",
                        "Your damage report for " + savedReport.getCityRoadName() + " has been received by the CPDO and is awaiting initial validation.",
                        "REPORT"
                );
            }

            return savedReport;

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

            // ==========================================
            // 🔔 NOTIFICATION TRIGGER: STATUS UPDATES
            // ==========================================
            if (newStatus != null) {
                Long adminId = getAdminId();

                // 1. Admin Notifications (if CEO updates status)
                if (newStatus.equalsIgnoreCase("In Progress") || newStatus.equalsIgnoreCase("Completed")) {
                    notificationService.sendNotification(
                            adminId,
                            "Report Status Update",
                            "Report ID PRJ-" + report.getId() + " status has been updated to: " + newStatus,
                            "REPORT"
                    );
                }

                // 2. 🚀 Barangay Official Notifications (Strictly curated)
                if (report.getUser() != null) {
                    Long brgyUserId = report.getUser().getId();
                    String safeRoadName = report.getCityRoadName() != null ? report.getCityRoadName() : "a road";

                    if (newStatus.equalsIgnoreCase("Validated")) {
                        notificationService.sendNotification(brgyUserId, "Report Validated", "Good news! Report #PRJ-" + report.getId() + " for " + safeRoadName + " has been validated by the CPDO.", "REPORT");
                    }
                    else if (newStatus.equalsIgnoreCase("Rejected")) {
                        String reason = (adminRemarks != null && !adminRemarks.isEmpty()) ? adminRemarks : "Review remarks for details.";
                        notificationService.sendNotification(brgyUserId, "Report Rejected", "Report #PRJ-" + report.getId() + " requires corrections. Reason: " + reason, "REPORT");
                    }
                    else if (newStatus.equalsIgnoreCase("In Progress")) {
                        // 🚀 ADDED: Now notifies them when physical repairs start!
                        notificationService.sendNotification(brgyUserId, "Repair In Progress", "The City Engineering Office (CEO) has officially started repairs on " + safeRoadName + " (ID: PRJ-" + report.getId() + ").", "REPORT");
                    }
                    else if (newStatus.equalsIgnoreCase("Closed") || newStatus.equalsIgnoreCase("Resolved")) {
                        notificationService.sendNotification(brgyUserId, "Project Officially Closed", "Success! The repair for #PRJ-" + report.getId() + " on " + safeRoadName + " has been verified and officially closed.", "REPORT");
                    }
                    // NOTE: "Archived" and "Dispatched to CEO" have been intentionally omitted to prevent notification spam.
                }
            }
            return org.springframework.http.ResponseEntity.ok("SUCCESS");

        }).orElse(org.springframework.http.ResponseEntity.notFound().build());
    }

    // ==========================================
    // 3. UPDATE REPORT (RESUBMISSION)
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

            // (Data updates kept exactly as they were...)
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
            boolean isResubmitted = false;

            if (currentStatus != null && currentStatus.equalsIgnoreCase("Rejected")) {
                existingReport.setStatus("Resubmitted");
                isResubmitted = true;
            } else {
                existingReport.setStatus("Pending Validation");
            }

            existingReport.setAdminRemarks(null);
            repository.save(existingReport);

            if (isResubmitted) {
                Long adminId = getAdminId();
                notificationService.sendNotification(
                        adminId,
                        "Report Resubmitted",
                        "A previously rejected report by " + existingReport.getReportedBy() + " has been revised and resubmitted.",
                        "REPORT"
                );
            }

            return ResponseEntity.ok().body("Report updated successfully");

        } catch (Exception e) {
            return ResponseEntity.status(500).body("Error updating report: " + e.getMessage());
        }
    }

    // ==========================================
    // 4. BATCH DISPATCH TO CEO
    // ==========================================
    @PutMapping("/dispatch-masterlist")
    public ResponseEntity<String> dispatchMasterlistToCEO() {
        try {
            List<RoadReport> allReports = repository.findAll();
            int dispatchedCount = 0;

            for (RoadReport report : allReports) {
                if ("Validated".equalsIgnoreCase(report.getStatus().trim())) {
                    report.setStatus("Dispatched to CEO");
                    dispatchedCount++;
                }
            }

            if (dispatchedCount > 0) {
                repository.saveAll(allReports);
                // 🚀 REMOVED: Batch Dispatch emails & notifications to keep Barangay Official's inbox spam-free!
                return ResponseEntity.ok("Successfully dispatched " + dispatchedCount + " prioritized reports to the CEO!");
            } else {
                return ResponseEntity.badRequest().body("No 'Validated' reports found to dispatch.");
            }
        } catch (Exception e) {
            return ResponseEntity.status(500).body("Error dispatching masterlist: " + e.getMessage());
        }
    }

    // ==========================================
    // 5B. CEO BATCH DEFER REPAIRS (PENDING BUDGET)
    // ==========================================
    @PostMapping("/batch/defer")
    public ResponseEntity<?> batchDeferReports(@RequestBody java.util.Map<String, Object> payload) {
        try {
            String reason = (String) payload.get("repairRemarks");
            Object idsObj = payload.get("reportIds");

            if (reason == null || reason.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(java.util.Map.of("error", "Deferral reason is required."));
            }
            if (idsObj == null) {
                return ResponseEntity.badRequest().body(java.util.Map.of("error", "No reports selected for deferral."));
            }

            java.util.List<Long> reportIds = new java.util.ArrayList<>();
            if (idsObj instanceof java.util.List) {
                for (Object id : (java.util.List<?>) idsObj) {
                    reportIds.add(Long.valueOf(id.toString()));
                }
            }

            if (reportIds.isEmpty()) return ResponseEntity.badRequest().body(java.util.Map.of("error", "No valid reports selected."));

            List<RoadReport> reportsToDefer = repository.findAllById(reportIds);

            if (reportsToDefer.isEmpty()) return ResponseEntity.status(404).body(java.util.Map.of("error", "Could not find the selected reports in the database."));

            for (RoadReport r : reportsToDefer) {
                r.setStatus("Pending Budget");
                r.setRepairRemarks(reason);
            }

            repository.saveAll(reportsToDefer);

            // ==========================================
            // 🔔 SINGLE NOTIFICATION TRIGGER: ADMIN BUDGET ALERT
            // ==========================================
            Long adminId = getAdminId();
            notificationService.sendNotification(
                    adminId,
                    "Batch Budget Alert",
                    "The CEO has deferred " + reportsToDefer.size() + " selected reports due to budget constraints. Reason: " + reason,
                    "BUDGET"
            );

            // ==========================================
            // 📧 GROUP EMAILS & 🔔 SEND INDIVIDUAL NOTIFICATIONS
            // ==========================================
            java.util.Map<com.roadwise.backend.model.User, java.util.List<RoadReport>> deferredByUser = new java.util.HashMap<>();

            for (RoadReport report : reportsToDefer) {
                if (report.getUser() != null) {
                    // Group it for the single batch email
                    deferredByUser.computeIfAbsent(report.getUser(), k -> new java.util.ArrayList<>()).add(report);

                    // 🚀 THE FIX: Fire an INDIVIDUAL Bell Notification for every single report deferred!
                    if (report.getUser().getId() != null) {
                        String safeRoadName = report.getCityRoadName() != null ? report.getCityRoadName() : "a road";
                        notificationService.sendNotification(
                                report.getUser().getId(),
                                "Repair Deferred (Pending Budget)",
                                "The CEO has deferred the repair for " + safeRoadName + " (ID: PRJ-" + report.getId() + ") due to budget constraints. Reason: " + reason,
                                "BUDGET"
                        );
                    }
                }
            }

            // Send the Grouped Email (so we don't spam their inbox with 10 emails)
            for (java.util.Map.Entry<com.roadwise.backend.model.User, java.util.List<RoadReport>> entry : deferredByUser.entrySet()) {
                sendBatchDeferEmail(entry.getKey(), entry.getValue(), reason);
            }

            return ResponseEntity.ok().body(java.util.Map.of("message", "Successfully deferred " + reportsToDefer.size() + " selected reports."));

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body(java.util.Map.of("error", "Error processing batch deferral: " + e.getMessage()));
        }
    }

    // ==========================================
    // 5. CEO MARK AS COMPLETED
    // ==========================================
    @PostMapping(value = "/{id}/complete", consumes = {"multipart/form-data"})
    public ResponseEntity<?> completeReport(
            @PathVariable Long id,
            @RequestParam(value = "repairRemarks", required = false) String repairRemarks,
            @RequestParam(value = "proofImage", required = true) MultipartFile proofImage) {

        try {
            RoadReport report = repository.findById(id).orElseThrow(() -> new RuntimeException("Report not found"));

            if (proofImage != null && !proofImage.isEmpty()) {
                Path uploadPath = Paths.get(UPLOAD_DIR);
                if (!Files.exists(uploadPath)) Files.createDirectories(uploadPath);

                String fileName = UUID.randomUUID().toString() + "_" + proofImage.getOriginalFilename();
                Path filePath = uploadPath.resolve(fileName);
                Files.copy(proofImage.getInputStream(), filePath);

                report.setProofOfRepairImage(fileName);
            }

            if (repairRemarks != null) report.setRepairRemarks(repairRemarks);

            report.setStatus("Completed");
            repository.save(report);

            sendStatusUpdateEmail(report, "Completed", repairRemarks);

            Long adminId = getAdminId();
            notificationService.sendNotification(
                    adminId,
                    "Repair Completed",
                    "The CEO has marked Report ID " + report.getId() + " as completed and uploaded proof. Awaiting your final QA.",
                    "REPORT"
            );

            return ResponseEntity.ok().body(java.util.Map.of("message", "Project marked as Completed!"));

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body(java.util.Map.of("error", "Error completing repair: " + e.getMessage()));
        }
    }

    // ==========================================
    // 5B. CEO DEFER REPAIR (SINGLE)
    // ==========================================
    @PutMapping("/{id}/defer")
    public ResponseEntity<?> deferReport(@PathVariable Long id, @RequestBody java.util.Map<String, String> payload) {
        try {
            RoadReport report = repository.findById(id).orElseThrow(() -> new RuntimeException("Report not found"));

            String reason = payload.get("repairRemarks");

            report.setRepairRemarks(reason);
            report.setStatus("Pending Budget");
            repository.save(report);

            sendStatusUpdateEmail(report, "Pending Budget", reason);

            // Notify Admin
            Long adminId = getAdminId();
            notificationService.sendNotification(
                    adminId,
                    "Budget Alert: Repair Deferred",
                    "The CEO has deferred the repair for " + report.getCityRoadName() + " (ID: " + report.getId() + ") due to budget constraints. Reason: " + reason,
                    "BUDGET"
            );

            // 🚀 FIXED: Notify Barangay Official
            if (report.getUser() != null && report.getUser().getId() != null) {
                notificationService.sendNotification(
                        report.getUser().getId(),
                        "Repair Deferred (Pending Budget)",
                        "The CEO has deferred the repair for " + report.getCityRoadName() + " (ID: PRJ-" + report.getId() + ") due to budget constraints. Reason: " + reason,
                        "BUDGET"
                );
            }

            return ResponseEntity.ok().body(java.util.Map.of("message", "Project marked as Pending Budget. CPDO Admin notified."));

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body(java.util.Map.of("error", "Error deferring repair: " + e.getMessage()));
        }
    }

    // ==========================================
    // 6. EMAIL HELPER: SINGLE STATUS UPDATE
    // ==========================================
    private void sendStatusUpdateEmail(RoadReport report, String newStatus, String remarks) {
        if (report.getUser() == null || report.getUser().getEmail() == null || report.getUser().getEmail().isEmpty()) {
            return;
        }

        String officialEmail = report.getUser().getEmail();
        String officialName = report.getUser().getFirstName();

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
                subject = "RoadWise Update: Repair In Progress";
                body.append("The City Engineering Office (CEO) is now actively working on this road repair! Please monitor the dashboard for the completion update.\n");
                break;
            case "pending budget":
                subject = "RoadWise Update: Repair Deferred (Pending Budget)";
                body.append("The CEO has reviewed the priority list. Due to current budget constraints, immediate repair for this road has been deferred. It remains securely in our system for future fiscal allocation.\n");
                break;
            case "completed":
                subject = "RoadWise Update: Repair Pending Admin QA";
                body.append("The City Engineering Office (CEO) has marked this repair as completed! The CPDO Admin is now reviewing the final proof of repair.\n");
                break;
            case "closed":
            case "resolved":
                // 🚀 ADDED: Specific email wording for successfully closed projects
                subject = "RoadWise Update: Project Officially Closed";
                body.append("Success! The repair for this road has been verified and officially closed by the CPDO Admin. Thank you for keeping your barangay safe.\n");
                break;
            default:
                body.append("The status of your report has been updated.\n");
        }

        // NOTE: "Archived" status intentionally omitted so no email is sent when Admin archives deferred projects.

        if (remarks != null && !remarks.trim().isEmpty()) {
            body.append("\nRemarks: ").append(remarks).append("\n");
        }

        body.append("\nPlease log into your RoadWise Dashboard to view full details.\n\n");
        body.append("Best regards,\nRoadWise SJDM System");

        emailService.sendEmail(officialEmail, subject, body.toString());
    }

    // ==========================================
    // 7. EMAIL HELPER: BATCH DEFERRAL LIST
    // ==========================================
    private void sendBatchDeferEmail(com.roadwise.backend.model.User official, List<RoadReport> deferredReports, String reason) {
        if (official.getEmail() == null || official.getEmail().isEmpty()) return;

        String subject = "RoadWise Update: " + deferredReports.size() + " Reports Deferred (Pending Budget)";
        StringBuilder body = new StringBuilder();

        body.append("Hello ").append(official.getFirstName()).append(",\n\n");
        body.append("The City Engineering Office (CEO) has reviewed the priority list and deferred ").append(deferredReports.size())
                .append(" of your road reports due to budget constraints.\n\n");

        body.append("CEO Remarks: ").append(reason).append("\n\n");

        body.append("Deferred Roads:\n");
        for (RoadReport r : deferredReports) {
            body.append("- ").append(r.getCityRoadName()).append(" (ID: RPT-").append(r.getId()).append(")\n");
        }

        body.append("\nThese reports remain securely in the system for future fiscal allocation.\n\n");
        body.append("Best regards,\nRoadWise SJDM System");

        emailService.sendEmail(official.getEmail(), subject, body.toString());
    }
}