package com.roadwise.backend.controller;

import com.roadwise.backend.model.Barangay;
import com.roadwise.backend.model.RoadReport;
import com.roadwise.backend.model.User;
import com.roadwise.backend.repository.BarangayRepository;
import com.roadwise.backend.repository.RoadReportRepository;
import com.roadwise.backend.repository.UserRepository;
import com.roadwise.backend.service.ActivityLogService;
import com.roadwise.backend.service.EmailService;
import com.roadwise.backend.service.NotificationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

@RestController
@RequestMapping("/api/reports")
@CrossOrigin(origins = "${frontend.url}")
public class RoadReportController {

    @Autowired
    private RoadReportRepository repository;

    @Autowired
    private BarangayRepository barangayRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EmailService emailService;

    @Autowired
    private NotificationService notificationService;

    // 🚀 INJECTED ACTIVITY LOG AUDIT SERVICE
    @Autowired
    private ActivityLogService activityLogService;

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    private static final String UPLOAD_DIR = "uploads/";

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
    // 🚀 HELPER: DYNAMICALLY FIND CEO ID
    // ==========================================
    private Long getCeoId() {
        return userRepository.findAll().stream()
                .filter(user -> user.getRole() != null &&
                        (user.getRole().equalsIgnoreCase("ENGINEER") || user.getRole().equalsIgnoreCase("City Engineer")))
                .map(User::getId)
                .findFirst()
                .orElse(null);
    }

    // ==========================================
    // 🚀 HELPER: HYBRID ACTOR RESOLVER
    // ==========================================
    private User resolveActor(Long explicitUserId, Long fallbackRoleId) {
        if (explicitUserId != null) {
            Optional<User> u = userRepository.findById(explicitUserId);
            if (u.isPresent()) return u.get();
        }
        if (fallbackRoleId != null) {
            return userRepository.findById(fallbackRoleId).orElse(null);
        }
        return null;
    }

    // ==========================================
    // 1. CREATE REPORT (BARANGAY OFFICIAL)
    // ==========================================
    @PostMapping(consumes = {"multipart/form-data"})
    public RoadReport createReport(
            @ModelAttribute RoadReport report,
            @RequestParam(value = "barangayId", required = false) Long barangayId,
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "imageFile", required = false) MultipartFile imageFile,
            HttpServletRequest request) {

        try {
            User foundUser = null;
            if (barangayId != null) {
                Barangay foundBarangay = barangayRepository.findById(barangayId).orElse(null);
                report.setBarangay(foundBarangay);
            }

            if (userId != null) {
                foundUser = userRepository.findById(userId).orElse(null);
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

            // 🔔 SMART NOTIFICATIONS
            Long adminId = getAdminId();
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

            if (savedReport.getUser() != null) {
                notificationService.sendNotification(
                        savedReport.getUser().getId(),
                        "Report Submitted Successfully",
                        "Your damage report for " + savedReport.getCityRoadName() + " has been received by the CPDO and is awaiting initial validation.",
                        "REPORT"
                );
            }

            // ⏱️ AUDIT LOG: NEW REPORT SUBMITTED (Attributed to submitter)
            activityLogService.log(
                    foundUser,
                    "PROJECT",
                    "REPORT_SUBMITTED",
                    "#PRJ-" + String.format("%04d", savedReport.getId()),
                    "Road damage report submitted for '" + savedReport.getCityRoadName() + "' (" + (savedReport.getDamageType() != null ? savedReport.getDamageType() : "Damage") + ").",
                    "SUCCESS",
                    request
            );

            sendLiveUpdate();
            return savedReport;

        } catch (IOException e) {
            e.printStackTrace();
            throw new RuntimeException("Failed to save image file to the server!");
        }
    }

    @GetMapping
    public List<RoadReport> getAllReports() {
        return repository.findAll(Sort.by(Sort.Direction.DESC, "id"));
    }

    @GetMapping("/{id}")
    public ResponseEntity<RoadReport> getReportById(@PathVariable Long id) {
        return repository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/barangay/{barangayId}")
    public List<RoadReport> getReportsByBarangay(@PathVariable Long barangayId) {
        List<RoadReport> reports = repository.findByBarangay_Id(barangayId);
        reports.sort((a, b) -> b.getId().compareTo(a.getId()));
        return reports;
    }

    // ==========================================
    // 2. THE EMAIL SWITCHBOARD & STATUS UPDATES (CPDO / CEO)
    // ==========================================
    @PutMapping("/{id}/status")
    public ResponseEntity<String> updateReportStatus(
            @PathVariable Long id,
            @RequestBody Map<String, String> payload,
            HttpServletRequest request) {

        return repository.findById(id).map(report -> {

            String newStatus = payload.get("status");
            String adminRemarks = payload.get("adminRemarks");
            String passedUserIdStr = payload.get("userId");
            Long explicitUserId = passedUserIdStr != null ? Long.valueOf(passedUserIdStr) : null;

            if (newStatus != null) {
                report.setStatus(newStatus);
            }

            if (adminRemarks != null) {
                report.setAdminRemarks(adminRemarks);
            }

            repository.save(report);

            sendStatusUpdateEmail(report, newStatus, adminRemarks);

            Long adminId = getAdminId();
            Long ceoId = getCeoId();

            // 🔔 NOTIFICATION TRIGGERS
            if (newStatus != null) {
                if (newStatus.equalsIgnoreCase("Completed") || (newStatus.equalsIgnoreCase("In Progress") && (adminRemarks == null || adminRemarks.trim().isEmpty()))) {
                    notificationService.sendNotification(
                            adminId,
                            "Report Status Update",
                            "Report ID PRJ-" + report.getId() + " status has been updated to: " + newStatus,
                            "REPORT"
                    );
                }

                if (newStatus.equalsIgnoreCase("In Progress") && adminRemarks != null && !adminRemarks.trim().isEmpty()) {
                    if (ceoId != null) {
                        notificationService.sendNotification(
                                ceoId,
                                "Repair Rework Required",
                                "The CPDO Admin has requested a rework for PRJ-" + report.getId() + ". Admin Remarks: " + adminRemarks,
                                "REPORT"
                        );

                        userRepository.findById(ceoId).ifPresent(ceo -> {
                            if (ceo.getEmail() != null && !ceo.getEmail().isEmpty()) {
                                String safeRoadName = report.getCityRoadName() != null ? report.getCityRoadName() : "a road";
                                String subject = "RoadWise Alert: Repair Rework Required for PRJ-" + report.getId();
                                String body = "Hello " + ceo.getFirstName() + ",\n\n" +
                                        "The CPDO Admin has reviewed the proof of repair for " + safeRoadName + " (PRJ-" + report.getId() + ") and has requested a rework.\n\n" +
                                        "Admin Remarks: " + adminRemarks + "\n\n" +
                                        "The project has been returned to your active queue. Please deploy the crew to address the feedback and upload new proof once completed.\n\n" +
                                        "Best regards,\nRoadWise SJDM System";
                                emailService.sendEmail(ceo.getEmail(), subject, body);
                            }
                        });
                    }
                }

                if (report.getUser() != null) {
                    Long brgyUserId = report.getUser().getId();
                    String safeRoadName = report.getCityRoadName() != null ? report.getCityRoadName() : "a road";

                    if (newStatus.equalsIgnoreCase("Validated")) {
                        notificationService.sendNotification(brgyUserId, "Report Validated", "Good news! Report #PRJ-" + report.getId() + " for " + safeRoadName + " has been validated by the CPDO.", "REPORT");
                    } else if (newStatus.equalsIgnoreCase("Rejected")) {
                        String reason = (adminRemarks != null && !adminRemarks.isEmpty()) ? adminRemarks : "Review remarks for details.";
                        notificationService.sendNotification(brgyUserId, "Report Rejected", "Report #PRJ-" + report.getId() + " requires corrections. Reason: " + reason, "REPORT");
                    } else if (newStatus.equalsIgnoreCase("In Progress")) {
                        notificationService.sendNotification(brgyUserId, "Repair In Progress", "The City Engineering Office (CEO) is actively working on " + safeRoadName + " (ID: PRJ-" + report.getId() + ").", "REPORT");
                    } else if (newStatus.equalsIgnoreCase("Closed") || newStatus.equalsIgnoreCase("Resolved")) {
                        notificationService.sendNotification(brgyUserId, "Project Officially Closed", "Success! The repair for #PRJ-" + report.getId() + " on " + safeRoadName + " has been verified and officially closed.", "REPORT");
                    }
                }
            }

            // ⏱️ RESOLVE ACTOR & LOG ACTION (Hybrid Resolution)
            User actor;
            String logCategory = "PROJECT";
            String logAction = "STATUS_UPDATED_" + (newStatus != null ? newStatus.toUpperCase().replace(" ", "_") : "UNKNOWN");
            String logDesc = "Project status transitioned to '" + newStatus + "' on " + report.getCityRoadName() + "." +
                    (adminRemarks != null && !adminRemarks.trim().isEmpty() ? " Remarks: " + adminRemarks : "");

            if (newStatus != null && (newStatus.equalsIgnoreCase("Validated") || newStatus.equalsIgnoreCase("Rejected"))) {
                logCategory = "QA";
                logAction = newStatus.equalsIgnoreCase("Validated") ? "REPORT_VALIDATED" : "REPORT_REJECTED";
                actor = resolveActor(explicitUserId, adminId); // Attributed to CPDO Admin
            } else if (newStatus != null && newStatus.equalsIgnoreCase("In Progress") && adminRemarks != null && !adminRemarks.trim().isEmpty()) {
                logCategory = "QA";
                logAction = "REPAIR_REWORK_REQUESTED";
                actor = resolveActor(explicitUserId, adminId); // Attributed to CPDO Admin
            } else if (newStatus != null && (newStatus.equalsIgnoreCase("In Progress") || newStatus.equalsIgnoreCase("Completed"))) {
                logCategory = "PROJECT";
                logAction = newStatus.equalsIgnoreCase("In Progress") ? "REPAIR_IN_PROGRESS" : "REPAIR_COMPLETED";
                actor = resolveActor(explicitUserId, ceoId); // Attributed to CEO Engineer
            } else if (newStatus != null && (newStatus.equalsIgnoreCase("Closed") || newStatus.equalsIgnoreCase("Resolved"))) {
                logCategory = "QA";
                logAction = "PROJECT_OFFICIALLY_CLOSED";
                actor = resolveActor(explicitUserId, adminId); // Attributed to CPDO Admin
            } else {
                actor = resolveActor(explicitUserId, adminId);
            }

            activityLogService.log(
                    actor,
                    logCategory,
                    logAction,
                    "#PRJ-" + String.format("%04d", report.getId()),
                    logDesc,
                    "SUCCESS",
                    request
            );

            sendLiveUpdate();
            return ResponseEntity.ok("SUCCESS");

        }).orElse(ResponseEntity.notFound().build());
    }

    // ==========================================
    // 3. UPDATE REPORT (RESUBMISSION BY OFFICIAL)
    // ==========================================
    @PutMapping("/update/{id}")
    public ResponseEntity<?> updateReport(@PathVariable Long id,
                                          @RequestParam(value = "userId", required = false) Long userId,
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
                                          @RequestParam(value = "imageFile", required = false) MultipartFile imageFile,
                                          HttpServletRequest request) {
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
                String fileName = UUID.randomUUID().toString() + "_" + imageFile.getOriginalFilename();
                Path filePath = Paths.get("uploads", fileName);
                Files.copy(imageFile.getInputStream(), filePath);
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

            // ⏱️ AUDIT LOG: Attributed to Barangay Submitter
            User actor = (userId != null) ? userRepository.findById(userId).orElse(existingReport.getUser()) : existingReport.getUser();
            activityLogService.log(
                    actor,
                    "PROJECT",
                    isResubmitted ? "REPORT_RESUBMITTED" : "REPORT_UPDATED",
                    "#PRJ-" + String.format("%04d", existingReport.getId()),
                    "Report details and measurements updated for " + existingReport.getCityRoadName() + ".",
                    "SUCCESS",
                    request
            );

            sendLiveUpdate();
            return ResponseEntity.ok().body("Report updated successfully");

        } catch (Exception e) {
            return ResponseEntity.status(500).body("Error updating report: " + e.getMessage());
        }
    }

    // ==========================================
    // 4. BATCH DISPATCH TO CEO (CPDO ADMIN)
    // ==========================================
    @PutMapping("/dispatch-masterlist")
    public ResponseEntity<String> dispatchMasterlistToCEO(
            @RequestParam(value = "userId", required = false) Long userId,
            HttpServletRequest request) {
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

                final int finalDispatchedCount = dispatchedCount;

                Long ceoId = getCeoId();
                if (ceoId != null) {
                    notificationService.sendNotification(
                            ceoId,
                            "Masterlist Dispatched",
                            "The CPDO has officially dispatched " + finalDispatchedCount + " validated road reports to your priority pool. Please review for budget allocation and deployment.",
                            "REPORT"
                    );

                    userRepository.findById(ceoId).ifPresent(ceo -> {
                        if (ceo.getEmail() != null && !ceo.getEmail().isEmpty()) {
                            String subject = "RoadWise: Masterlist Dispatched";
                            String body = "Hello " + ceo.getFirstName() + ",\n\n" +
                                    "The CPDO has officially dispatched " + finalDispatchedCount + " validated road reports to your priority pool.\n" +
                                    "Please review the Engineering Dashboard.\n\n" +
                                    "Best regards,\nRoadWise SJDM System";
                            emailService.sendEmail(ceo.getEmail(), subject, body);
                        }
                    });
                }

                // ⏱️ AUDIT LOG: Attributed to CPDO Admin
                User adminActor = resolveActor(userId, getAdminId());
                activityLogService.log(
                        adminActor,
                        "PROJECT",
                        "MASTERLIST_DISPATCHED",
                        "CEO_PRIORITY_QUEUE",
                        "CPDO dispatched " + finalDispatchedCount + " validated road reports to the CEO engineering priority queue.",
                        "SUCCESS",
                        request
                );

                sendLiveUpdate();
                return ResponseEntity.ok("Successfully dispatched " + finalDispatchedCount + " prioritized reports to the CEO!");
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
    public ResponseEntity<?> batchDeferReports(@RequestBody Map<String, Object> payload, HttpServletRequest request) {
        try {
            String reason = (String) payload.get("repairRemarks");
            Object idsObj = payload.get("reportIds");
            Long explicitUserId = payload.get("userId") != null ? Long.valueOf(payload.get("userId").toString()) : null;

            if (reason == null || reason.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Deferral reason is required."));
            }
            if (idsObj == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "No reports selected for deferral."));
            }

            List<Long> reportIds = new ArrayList<>();
            if (idsObj instanceof List) {
                for (Object id : (List<?>) idsObj) {
                    reportIds.add(Long.valueOf(id.toString()));
                }
            }

            if (reportIds.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "No valid reports selected."));

            List<RoadReport> reportsToDefer = repository.findAllById(reportIds);

            if (reportsToDefer.isEmpty()) return ResponseEntity.status(404).body(Map.of("error", "Could not find the selected reports in the database."));

            for (RoadReport r : reportsToDefer) {
                r.setStatus("Pending Budget");
                r.setRepairRemarks(reason);
            }

            repository.saveAll(reportsToDefer);

            Long adminId = getAdminId();
            notificationService.sendNotification(
                    adminId,
                    "Batch Budget Alert",
                    "The CEO has deferred " + reportsToDefer.size() + " selected reports due to budget constraints. Reason: " + reason,
                    "BUDGET"
            );

            Map<User, List<RoadReport>> deferredByUser = new HashMap<>();

            for (RoadReport report : reportsToDefer) {
                if (report.getUser() != null) {
                    deferredByUser.computeIfAbsent(report.getUser(), k -> new ArrayList<>()).add(report);

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

            for (Map.Entry<User, List<RoadReport>> entry : deferredByUser.entrySet()) {
                sendBatchDeferEmail(entry.getKey(), entry.getValue(), reason);
            }

            // ⏱️ AUDIT LOG: Attributed to CEO Engineer
            User ceoActor = resolveActor(explicitUserId, getCeoId());
            activityLogService.log(
                    ceoActor,
                    "PROJECT",
                    "REPAIRS_BATCH_DEFERRED",
                    "BATCH_DEFERRAL",
                    "CEO deferred " + reportsToDefer.size() + " reports to Pending Budget. Reason: " + reason,
                    "SUCCESS",
                    request
            );

            sendLiveUpdate();
            return ResponseEntity.ok().body(Map.of("message", "Successfully deferred " + reportsToDefer.size() + " selected reports."));

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body(Map.of("error", "Error processing batch deferral: " + e.getMessage()));
        }
    }

    // ==========================================
    // 5. CEO MARK AS COMPLETED (WITH PROOF)
    // ==========================================
    @PostMapping(value = "/{id}/complete", consumes = {"multipart/form-data"})
    public ResponseEntity<?> completeReport(
            @PathVariable Long id,
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "repairRemarks", required = false) String repairRemarks,
            @RequestParam(value = "proofImage", required = true) MultipartFile proofImage,
            HttpServletRequest request) {

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

            // ⏱️ AUDIT LOG: Attributed to CEO Engineer
            User ceoActor = resolveActor(userId, getCeoId());
            activityLogService.log(
                    ceoActor,
                    "PROJECT",
                    "REPAIR_COMPLETED",
                    "#PRJ-" + String.format("%04d", report.getId()),
                    "CEO marked project as Completed and submitted proof of repair." + (repairRemarks != null ? " Remarks: " + repairRemarks : ""),
                    "SUCCESS",
                    request
            );

            sendLiveUpdate();
            return ResponseEntity.ok().body(Map.of("message", "Project marked as Completed!"));

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body(Map.of("error", "Error completing repair: " + e.getMessage()));
        }
    }

    // ==========================================
    // 5B. CEO DEFER REPAIR (SINGLE)
    // ==========================================
    @PutMapping("/{id}/defer")
    public ResponseEntity<?> deferReport(
            @PathVariable Long id,
            @RequestBody Map<String, String> payload,
            HttpServletRequest request) {
        try {
            RoadReport report = repository.findById(id).orElseThrow(() -> new RuntimeException("Report not found"));

            String reason = payload.get("repairRemarks");
            String passedUserIdStr = payload.get("userId");
            Long explicitUserId = passedUserIdStr != null ? Long.valueOf(passedUserIdStr) : null;

            report.setRepairRemarks(reason);
            report.setStatus("Pending Budget");
            repository.save(report);

            sendStatusUpdateEmail(report, "Pending Budget", reason);

            Long adminId = getAdminId();
            notificationService.sendNotification(
                    adminId,
                    "Budget Alert: Repair Deferred",
                    "The CEO has deferred the repair for " + report.getCityRoadName() + " (ID: " + report.getId() + ") due to budget constraints. Reason: " + reason,
                    "BUDGET"
            );

            if (report.getUser() != null && report.getUser().getId() != null) {
                notificationService.sendNotification(
                        report.getUser().getId(),
                        "Repair Deferred (Pending Budget)",
                        "The CEO has deferred the repair for " + report.getCityRoadName() + " (ID: PRJ-" + report.getId() + ") due to budget constraints. Reason: " + reason,
                        "BUDGET"
                );
            }

            // ⏱️ AUDIT LOG: Attributed to CEO Engineer
            User ceoActor = resolveActor(explicitUserId, getCeoId());
            activityLogService.log(
                    ceoActor,
                    "PROJECT",
                    "REPAIR_DEFERRED",
                    "#PRJ-" + String.format("%04d", report.getId()),
                    "Repair deferred by CEO to Pending Budget. Reason: " + reason,
                    "SUCCESS",
                    request
            );

            sendLiveUpdate();
            return ResponseEntity.ok().body(Map.of("message", "Project marked as Pending Budget. CPDO Admin notified."));

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body(Map.of("error", "Error deferring repair: " + e.getMessage()));
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
                subject = "RoadWise Update: Project Officially Closed";
                body.append("Success! The repair for this road has been verified and officially closed by the CPDO Admin. Thank you for keeping your barangay safe.\n");
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
    // 7. EMAIL HELPER: BATCH DEFERRAL LIST
    // ==========================================
    private void sendBatchDeferEmail(User official, List<RoadReport> deferredReports, String reason) {
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

    // ==========================================
    // 8. ADMIN BATCH ARCHIVE (SILENT HOUSEKEEPING)
    // ==========================================
    @PostMapping("/batch/archive")
    public ResponseEntity<?> batchArchiveReports(
            @RequestBody Map<String, Object> payload,
            HttpServletRequest request) {
        try {
            Object idsObj = payload.get("reportIds");
            Long explicitUserId = payload.get("userId") != null ? Long.valueOf(payload.get("userId").toString()) : null;

            if (idsObj == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "No reports selected for archiving."));
            }

            List<Long> reportIds = new ArrayList<>();
            if (idsObj instanceof List) {
                for (Object id : (List<?>) idsObj) {
                    reportIds.add(Long.valueOf(id.toString()));
                }
            }

            if (reportIds.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "No valid reports selected."));

            List<RoadReport> reportsToArchive = repository.findAllById(reportIds);

            if (reportsToArchive.isEmpty()) return ResponseEntity.status(404).body(Map.of("error", "Could not find the selected reports in the database."));

            int archivedCount = 0;
            for (RoadReport r : reportsToArchive) {
                if ("Pending Budget".equalsIgnoreCase(r.getStatus())) {
                    r.setStatus("Archived");
                    archivedCount++;
                }
            }

            repository.saveAll(reportsToArchive);

            // ⏱️ AUDIT LOG: Attributed to CPDO Admin
            User adminActor = resolveActor(explicitUserId, getAdminId());
            activityLogService.log(
                    adminActor,
                    "PROJECT",
                    "BATCH_ARCHIVED",
                    "ARCHIVE_POOL",
                    "CPDO Admin archived " + archivedCount + " deferred project reports.",
                    "SUCCESS",
                    request
            );

            sendLiveUpdate();

            return ResponseEntity.ok().body(Map.of("message", "Successfully archived " + archivedCount + " deferred reports."));

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body(Map.of("error", "Error processing batch archive: " + e.getMessage()));
        }
    }

    // ==========================================
    // 🚀 WEBSOCKET BROADCASTER (LIVE REFRESH)
    // ==========================================
    private void sendLiveUpdate() {
        try {
            messagingTemplate.convertAndSend("/topic/updates", "REFRESH_DASHBOARDS");
        } catch (Exception e) {
            System.err.println("WebSocket Broadcast Failed: " + e.getMessage());
        }
    }

    // ==========================================
    // 9. ENTERPRISE ANNUAL AUDIT ROLLOVER
    // ==========================================
    @PostMapping("/rollover-annual-cycle")
    public ResponseEntity<?> executeAnnualRollover(
            @RequestParam(value = "userId", required = false) Long userId,
            HttpServletRequest request) {
        try {
            int archivedCount = repository.archiveAnnualCycleReports();

            List<User> allUsers = userRepository.findAll();

            String notifTitle = "📅 Annual Road Inventory Cycle Initialized";
            String notifMsg = "The CPDO has initialized the new annual audit cycle. Completed and validated reports have been archived. Active repairs remain in progress.";

            String emailSubject = "RoadWise SJDM: New Annual Road Inventory Cycle Initialized";

            for (User user : allUsers) {
                if (user.getId() != null) {
                    notificationService.sendNotification(user.getId(), notifTitle, notifMsg, "SYSTEM");
                }

                if (user.getEmail() != null && !user.getEmail().trim().isEmpty()) {
                    String personalizedBody = "Hello " + user.getFirstName() + ",\n\n" +
                            "The City Planning and Development Office (CPDO) has officially initialized the new Annual Road Inventory cycle for San Jose Del Monte.\n\n" +
                            "Cycle Summary:\n" +
                            "- Past validated, rejected, and officially closed records have been archived for audit records.\n" +
                            "- Active repair projects ('Dispatched', 'In Progress', and 'Completed Pending QA') remain active in the engineering queue.\n" +
                            "- Barangay Officials may now conduct visual surveys and submit fresh road inspection reports.\n\n" +
                            "Please log into your RoadWise dashboard for updated project lists.\n\n" +
                            "Best regards,\nRoadWise CPDO Administration";

                    emailService.sendEmail(user.getEmail(), emailSubject, personalizedBody);
                }
            }

            // ⏱️ AUDIT LOG: Attributed to CPDO Admin
            User adminActor = resolveActor(userId, getAdminId());
            activityLogService.log(
                    adminActor,
                    "SYSTEM",
                    "ANNUAL_ROLLOVER_EXECUTED",
                    "ANNUAL_CYCLE",
                    "CPDO Admin initialized annual rollover cycle. Archived " + archivedCount + " reports across the city database.",
                    "SUCCESS",
                    request
            );

            sendLiveUpdate();

            return ResponseEntity.ok(Map.of(
                    "message", "Successfully archived " + archivedCount + " reports and notified all users.",
                    "archivedCount", archivedCount
            ));

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body(Map.of(
                    "error", "Failed to complete annual rollover: " + e.getMessage()
            ));
        }
    }

    // ==========================================
    // SERVE UPLOADED IMAGES
    // ==========================================
    @GetMapping("/image/{filename:.+}")
    public ResponseEntity<Resource> serveImage(@PathVariable String filename) {
        try {
            Path file = Paths.get("uploads").resolve(filename);
            Resource resource = new UrlResource(file.toUri());

            if (resource.exists() || resource.isReadable()) {
                String contentType = Files.probeContentType(file);
                if (contentType == null) {
                    contentType = "application/octet-stream";
                }
                return ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_TYPE, contentType)
                        .body(resource);
            } else {
                return ResponseEntity.notFound().build();
            }
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }
}