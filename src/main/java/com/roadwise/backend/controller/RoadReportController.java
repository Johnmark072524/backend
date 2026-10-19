package com.roadwise.backend.controller;

import com.roadwise.backend.model.Barangay;
import com.roadwise.backend.model.CityRoad;
import com.roadwise.backend.model.ReportStatusLog;
import com.roadwise.backend.model.RoadReport;
import com.roadwise.backend.model.User;
import com.roadwise.backend.repository.BarangayRepository;
import com.roadwise.backend.repository.CityRoadRepository;
import com.roadwise.backend.repository.RoadReportRepository;
import com.roadwise.backend.repository.UserRepository;
import com.roadwise.backend.service.ActivityLogService;
import com.roadwise.backend.service.EmailService;
import com.roadwise.backend.service.NotificationService;
import com.roadwise.backend.service.ReportStatusLogService;
import com.roadwise.backend.service.RoadInspectionService;
import com.roadwise.backend.service.SupabaseStorageService;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

@RestController
@RequestMapping("/api/reports")
@CrossOrigin(origins = "${frontend.url}")
public class RoadReportController {

    @Autowired
    private RoadReportRepository repository;

    @Autowired
    private CityRoadRepository cityRoadRepository;

    @Autowired
    private BarangayRepository barangayRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EmailService emailService;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private ActivityLogService activityLogService;

    @Autowired
    private RoadInspectionService aiService;

    @Autowired
    private SupabaseStorageService supabaseStorageService;

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    // 🚀 INJECTED STATUS LIFECYCLE AUDIT TRAIL SERVICE
    @Autowired
    private ReportStatusLogService reportStatusLogService;

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
    // 📐 MULTI-CRITERIA ENGINEERING SEVERITY EVALUATOR
    // ==========================================
    private String calculateEngineeringSeverity(
            String damageType,
            Double length,
            Double width,
            Double depth,
            String roadImportance) {

        if (damageType == null || "None".equalsIgnoreCase(damageType.trim())) {
            return "None";
        }

        String type = damageType.trim().toLowerCase();
        double dLength = length != null ? length : 0.0;
        double dWidth = width != null ? width : 0.0;
        double dDepth = depth != null ? depth : 0.0;
        double area = dLength * dWidth;

        boolean isCoreRoad = roadImportance != null &&
                roadImportance.trim().equalsIgnoreCase("Core");

        // 🚨 OVERRIDE RULE 1: Catastrophic road cuts/sinkholes/washouts are immediate HIGH
        if (type.contains("sinkhole") || type.contains("washout")) {
            return "High";
        }

        // 🚨 OVERRIDE RULE 2: Deep potholes on Core arterial corridors are immediate HIGH
        if (type.contains("pothole") && dDepth >= 0.08 && isCoreRoad) {
            return "High";
        }

        // 1. BASE DAMAGE TYPE HAZARD WEIGHT
        int score = 0;
        if (type.contains("pothole")) {
            score += 4;
        } else if (type.contains("edge") || type.contains("rutting")) {
            score += 3;
        } else if (type.contains("crack")) {
            score += 2;
        } else {
            score += 2; // Custom / Other distress
        }

        // 2. DIMENSION MULTIPLIER (Depth & Surface Area Thresholds)
        if (dDepth >= 0.12) {
            score += 3; // Severe cavity (> 12 cm)
        } else if (dDepth >= 0.06) {
            score += 2; // Moderate cavity (6 cm to 12 cm)
        }

        if (area >= 3.0) {
            score += 2; // Extensive surface area
        } else if (area >= 1.0) {
            score += 1;
        }

        // 3. ROAD IMPORTANCE EXPOSURE FACTOR (Core vs. Non-Core)
        if (isCoreRoad) {
            score += 2; // High traffic volume / arterial multiplier
        }

        // 4. FINAL RESOLUTION MAPPING
        if (score >= 7) {
            return "High";
        } else if (score >= 4) {
            return "Medium";
        } else {
            return "Low";
        }
    }

    // ==========================================
    // 1. CREATE REPORT (BARANGAY OFFICIAL)
    // ==========================================
    @PostMapping(consumes = {"multipart/form-data"})
    public ResponseEntity<?> createReport(
            @ModelAttribute RoadReport report,
            @RequestParam(value = "barangayId", required = false) Long barangayId,
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "imageFile", required = false) MultipartFile imageFile,
            HttpServletRequest request) {

        try {
            // Default values if no image is provided
            String severity = "None";
            String status = "UNDAMAGED";
            double confidence = 0.0;

            if (report.getDamageType() == null || report.getDamageType().trim().isEmpty()) {
                report.setDamageType("Pending Inspection");
            }
            if (report.getDamageLength() == null) report.setDamageLength(0.0);
            if (report.getDamageWidth() == null) report.setDamageWidth(0.0);
            if (report.getDamageDepth() == null) report.setDamageDepth(0.0);

            // 🧠 PROCESS IMAGE & RUN AI INFERENCE ONLY IF A PHOTO IS PROVIDED
            if (imageFile != null && !imageFile.isEmpty()) {
                // Backend MIME & 5MB size check
                String contentType = imageFile.getContentType() != null ? imageFile.getContentType().toLowerCase() : "";
                if (!contentType.equals("image/jpeg") && !contentType.equals("image/png") && !contentType.equals("image/webp") && !contentType.equals("image/jpg")) {
                    return ResponseEntity.badRequest().body(Map.of(
                            "error", "PHOTO_REJECTED",
                            "reason", "INVALID_FORMAT",
                            "message", "Invalid file format! Please upload a JPG, PNG, or WebP photo."
                    ));
                }
                if (imageFile.getSize() > 5 * 1024 * 1024) {
                    return ResponseEntity.badRequest().body(Map.of(
                            "error", "PHOTO_REJECTED",
                            "reason", "FILE_TOO_LARGE",
                            "message", "Image exceeds the 5MB size limit! Please upload a smaller photo."
                    ));
                }

                RoadInspectionService.InspectionResult aiResult;
                try {
                    aiResult = aiService.analyzeSeverity(imageFile);
                } catch (Exception e) {
                    System.err.println(">>> [AI WARNING] Computer Vision analysis failed: " + e.getMessage());
                    return ResponseEntity.badRequest().body(Map.of(
                            "error", "PHOTO_REJECTED",
                            "reason", "AI_UNAVAILABLE",
                            "message", "AI service is currently unable to analyze this photo. Please ensure it is a clear image of road damage."
                    ));
                }

                severity = aiResult != null ? aiResult.getSeverity() : "Unassessed";
                status = aiResult != null ? aiResult.getStatus() : "UNKNOWN";
                confidence = aiResult != null ? aiResult.getConfidence() : 0.0;

                // 🚫 2. REJECTION GATE: BLOCK UNDAMAGED ROADS (WHEN A PHOTO IS SUBMITTED)
                if ("Undamaged".equalsIgnoreCase(severity) || "UNDAMAGED".equalsIgnoreCase(status)) {
                    return ResponseEntity.badRequest().body(Map.of(
                            "error", "PHOTO_REJECTED",
                            "reason", "UNDAMAGED",
                            "message", "No road damage detected in the photo. Please upload a photo with visible distress."
                    ));
                }

                // 🚫 3. REJECTION GATE: BLOCK NON-ROAD / LOW CONFIDENCE ARTIFACTS
                if ("Unassessed".equalsIgnoreCase(severity) || "LOW_CONFIDENCE".equalsIgnoreCase(status) || "INVALID_SURFACE".equalsIgnoreCase(status)) {
                    return ResponseEntity.badRequest().body(Map.of(
                            "error", "PHOTO_REJECTED",
                            "reason", "INVALID_OR_UNCLEAR",
                            "message", "Image does not show clear road damage or is not a road surface. Please upload a clear photo of road damage."
                    ));
                }

                // ☁️ 4. PERSIST PHOTO TO SUPABASE STORAGE ONLY IF DAMAGE IS CONFIRMED
                String cloudImageUrl = supabaseStorageService.uploadImage(imageFile);
                report.setDamageImage(cloudImageUrl);
                report.setAiSeverity(severity); // 🧠 Initial Computer Vision screening
                report.setSeverity(severity);   // Baseline fallback until CPDO validates
                report.setCvConfidenceScore(confidence);
                System.out.println(">>> [AI SUCCESS] Evaluated " + imageFile.getOriginalFilename() + " as " + severity + " (" + confidence + "%)");
            } else {
                // No photo attached (Road surveyed in good condition / No physical distress)
                report.setDamageImage(null);
                report.setAiSeverity("None");
                report.setSeverity("None");
                report.setCvConfidenceScore(0.0);
                report.setDamageType("None");
            }

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

            report.setStatus("Pending Validation");

            // 💾 5. COMMIT TO DATABASE
            RoadReport savedReport = repository.save(report);

            // 📋 RECORD INITIAL LIFECYCLE EVENT
            reportStatusLogService.log(
                    savedReport,
                    "SUBMITTED",
                    null,
                    "Pending Validation",
                    "Road damage report submitted for '" + savedReport.getCityRoadName() + "'.",
                    foundUser,
                    savedReport.getReportedBy(),
                    "Barangay Official",
                    savedReport.getDamageImage()
            );

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

            // ⏱️ AUDIT LOG: NEW REPORT SUBMITTED
            activityLogService.log(
                    foundUser,
                    "PROJECT",
                    "REPORT_SUBMITTED",
                    "#PRJ-" + String.format("%04d", savedReport.getId()),
                    "Road damage report submitted for '" + savedReport.getCityRoadName() + "'.",
                    "SUCCESS",
                    request
            );

            sendLiveUpdate();
            return ResponseEntity.ok(savedReport);

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body(Map.of("error", "Failed to upload image or submit report: " + e.getMessage()));
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
            @RequestBody Map<String, Object> payload,
            HttpServletRequest request) {

        return repository.findById(id).map(report -> {
            String previousStatus = report.getStatus();
            String newStatus = payload.get("status") != null ? payload.get("status").toString() : null;
            String adminRemarks = payload.get("adminRemarks") != null ? payload.get("adminRemarks").toString() : null;
            String passedUserIdStr = payload.get("userId") != null ? payload.get("userId").toString() : null;
            Long explicitUserId = passedUserIdStr != null ? Long.valueOf(passedUserIdStr) : null;

            // 🎯 CAPTURE TARGET COMPLETION DATE (Logged during CEO dispatch)
            Object targetDateObj = payload.get("targetCompletionDate");
            if (targetDateObj != null && !targetDateObj.toString().trim().isEmpty()) {
                try {
                    report.setTargetCompletionDate(LocalDate.parse(targetDateObj.toString().trim()));
                } catch (Exception e) {
                    System.err.println("Failed to parse target completion date: " + targetDateObj);
                }
            }

            // 📐 CAPTURE CPDO TECHNICAL DAMAGE ASSESSMENT (When Validating)
            if (payload.get("damageType") != null) {
                report.setDamageType(payload.get("damageType").toString());
            }
            if (payload.get("damageLength") != null) {
                try { report.setDamageLength(Double.valueOf(payload.get("damageLength").toString())); } catch (Exception ignored) {}
            }
            if (payload.get("damageWidth") != null) {
                try { report.setDamageWidth(Double.valueOf(payload.get("damageWidth").toString())); } catch (Exception ignored) {}
            }
            if (payload.get("damageDepth") != null) {
                try { report.setDamageDepth(Double.valueOf(payload.get("damageDepth").toString())); } catch (Exception ignored) {}
            }

            // 🛣️ CAPTURE ROAD DIMENSIONS & SYNC WITH MASTER INVENTORY (city_roads)
            Double updatedWidth = null;
            if (payload.get("width") != null) {
                try {
                    updatedWidth = Double.valueOf(payload.get("width").toString());
                    report.setWidth(updatedWidth);
                } catch (Exception ignored) {}
            }
            if (payload.get("length") != null) {
                try { report.setLength(Double.valueOf(payload.get("length").toString())); } catch (Exception ignored) {}
            }
            if (payload.get("lengthOfCulverts") != null) {
                try { report.setLengthOfCulverts(Double.valueOf(payload.get("lengthOfCulverts").toString())); } catch (Exception ignored) {}
            }
            if (payload.get("numberOfBridges") != null) {
                try { report.setNumberOfBridges(Integer.valueOf(payload.get("numberOfBridges").toString())); } catch (Exception ignored) {}
            }

            // 🔄 SYNC TO MASTER INVENTORY: Save road_width to city_roads for future auto-fills
            if (updatedWidth != null && updatedWidth > 0 && report.getCityRoadId() != null) {
                String cleanTargetRoadId = report.getCityRoadId().replace(" ", "").trim();
                List<CityRoad> matchedRoads = cityRoadRepository.findAll().stream()
                        .filter(cr -> cr.getRoadId() != null && cr.getRoadId().replace(" ", "").trim().equalsIgnoreCase(cleanTargetRoadId))
                        .toList();

                for (CityRoad cr : matchedRoads) {
                    cr.setRoadWidth(updatedWidth);
                    if (report.getLength() != null && report.getLength() > 0) cr.setRoadLength(report.getLength());
                    if (report.getLengthOfCulverts() != null) cr.setLengthOfCulverts(report.getLengthOfCulverts());
                    if (report.getNumberOfBridges() != null) cr.setNumberOfBridges(report.getNumberOfBridges());
                }
                if (!matchedRoads.isEmpty()) {
                    cityRoadRepository.saveAll(matchedRoads);
                    System.out.println(">>> [SYNC SUCCESS] Master CityRoad record '" + report.getCityRoadId() + "' updated with Road Width: " + updatedWidth + "m");
                }
            }

            // 📐 RECALCULATE OFFICIAL ENGINEERING SEVERITY UPON CPDO VALIDATION
            if (newStatus != null && newStatus.equalsIgnoreCase("Validated")) {
                String calculatedSeverity = calculateEngineeringSeverity(
                        report.getDamageType(),
                        report.getDamageLength(),
                        report.getDamageWidth(),
                        report.getDamageDepth(),
                        report.getRoadImportance()
                );
                report.setSeverity(calculatedSeverity);
                System.out.println(">>> [SEVERITY RECALCULATED] Report #RPT-" + report.getId() +
                        " updated to Engineering Severity: " + calculatedSeverity +
                        " (Original AI triage: " + report.getAiSeverity() + ")");
            }

            // 🛡️ AUTO-ARCHIVE REPORTS WITHOUT DAMAGE EVIDENCE UPON VALIDATION
            boolean isAutoArchivedNoDamage = false;
            if (newStatus != null && newStatus.equalsIgnoreCase("Validated")) {
                boolean hasNoDamageImage = report.getDamageImage() == null ||
                        report.getDamageImage().trim().isEmpty() ||
                        report.getDamageImage().trim().equalsIgnoreCase("no_image.jpg");

                boolean isMarkedNone = "None".equalsIgnoreCase(report.getDamageType());

                if (hasNoDamageImage && isMarkedNone) {
                    newStatus = "Archived";
                    isAutoArchivedNoDamage = true;
                    if (adminRemarks == null || adminRemarks.trim().isEmpty()) {
                        adminRemarks = "Inspected by CPDO: Road surveyed in passable/good condition. Ticket archived as 'No Action Required'.";
                    }
                }
            }

            // 🛡️ IDEMPOTENCY GUARD
            if (newStatus != null && newStatus.equalsIgnoreCase(previousStatus)) {
                return ResponseEntity.ok("ALREADY_UPDATED");
            }

            if (newStatus != null) {
                report.setStatus(newStatus);

                if (newStatus.equalsIgnoreCase("Closed") ||
                        newStatus.equalsIgnoreCase("Resolved") ||
                        newStatus.equalsIgnoreCase("Archived") ||
                        newStatus.equalsIgnoreCase("Completed")) {
                    LocalDateTime nowPst = LocalDateTime.now(ZoneId.of("Asia/Manila"));
                    report.setDateArchived(nowPst);
                    report.setActualCompletionDate(nowPst);
                }
            }

            if (adminRemarks != null) {
                report.setAdminRemarks(adminRemarks);
            }

            repository.save(report);

            sendStatusUpdateEmail(report, newStatus, adminRemarks);

            Long adminId = getAdminId();
            Long ceoId = getCeoId();

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

                        final String reworkRemarks = adminRemarks;

                        userRepository.findById(ceoId).ifPresent(ceo -> {
                            if (ceo.getEmail() != null && !ceo.getEmail().isEmpty()) {
                                String safeRoadName = report.getCityRoadName() != null ? report.getCityRoadName() : "a road";
                                String subject = "RoadWise Alert: Repair Rework Required for PRJ-" + report.getId();
                                String body = "Hello " + ceo.getFirstName() + ",\n\n" +
                                        "The CPDO Admin has reviewed the proof of repair for " + safeRoadName + " (PRJ-" + report.getId() + ") and has requested a rework.\n\n" +
                                        "Admin Remarks: " + reworkRemarks + "\n\n" +
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

                    if (isAutoArchivedNoDamage) {
                        notificationService.sendNotification(
                                brgyUserId,
                                "Report Inspected & Archived",
                                "CPDO reviewed Report #PRJ-" + report.getId() + " for " + safeRoadName + ". Road confirmed in good condition; ticket has been archived.",
                                "REPORT"
                        );
                    } else if (newStatus.equalsIgnoreCase("Validated")) {
                        notificationService.sendNotification(brgyUserId, "Report Validated", "Good news! Report #PRJ-" + report.getId() + " for " + safeRoadName + " has been validated by the CPDO.", "REPORT");
                    } else if (newStatus.equalsIgnoreCase("Rejected")) {
                        String reason = (adminRemarks != null && !adminRemarks.isEmpty()) ? adminRemarks : "Review remarks for details.";
                        notificationService.sendNotification(brgyUserId, "Report Rejected", "Report #PRJ-" + report.getId() + " requires corrections. Reason: " + reason, "REPORT");
                    } else if (newStatus.equalsIgnoreCase("In Progress")) {
                        notificationService.sendNotification(brgyUserId, "Repair In Progress", "The City Engineering Office (CEO) is actively working on " + safeRoadName + " (ID: PRJ-" + report.getId() + ").", "REPORT");
                    } else if (newStatus.equalsIgnoreCase("Closed") || newStatus.equalsIgnoreCase("Resolved") || newStatus.equalsIgnoreCase("Archived")) {
                        notificationService.sendNotification(brgyUserId, "Project Officially Closed", "Success! The repair for #PRJ-" + report.getId() + " on " + safeRoadName + " has been verified and officially closed.", "REPORT");
                    }
                }
            }

            User actor;
            String logCategory = "PROJECT";
            String logAction = "STATUS_UPDATED_" + (newStatus != null ? newStatus.toUpperCase().replace(" ", "_") : "UNKNOWN");
            String logDesc = isAutoArchivedNoDamage
                    ? "CPDO inspected report without damage evidence. Ticket archived as 'No Action Required'."
                    : "Project status transitioned to '" + newStatus + "' on " + report.getCityRoadName() + "." +
                      (adminRemarks != null && !adminRemarks.trim().isEmpty() ? " Remarks: " + adminRemarks : "");

            String lifecycleAction = "STATUS_UPDATE";

            if (isAutoArchivedNoDamage) {
                logCategory = "QA";
                logAction = "REPORT_INSPECTED_NO_DAMAGE";
                lifecycleAction = "ARCHIVED_NO_DAMAGE";
                actor = resolveActor(explicitUserId, adminId);
            } else if (newStatus != null && (newStatus.equalsIgnoreCase("Validated") || newStatus.equalsIgnoreCase("Rejected"))) {
                logCategory = "QA";
                logAction = newStatus.equalsIgnoreCase("Validated") ? "REPORT_VALIDATED" : "REPORT_REJECTED";
                lifecycleAction = newStatus.equalsIgnoreCase("Validated") ? "VALIDATED" : "REJECTED";
                actor = resolveActor(explicitUserId, adminId);
            } else if (newStatus != null && newStatus.equalsIgnoreCase("In Progress") && adminRemarks != null && !adminRemarks.trim().isEmpty()) {
                logCategory = "QA";
                logAction = "REPAIR_REWORK_REQUESTED";
                lifecycleAction = "REPAIR_REWORK_REQUESTED";
                actor = resolveActor(explicitUserId, adminId);
            } else if (newStatus != null && (newStatus.equalsIgnoreCase("In Progress") || newStatus.equalsIgnoreCase("Completed"))) {
                logCategory = "PROJECT";
                logAction = newStatus.equalsIgnoreCase("In Progress") ? "REPAIR_IN_PROGRESS" : "REPAIR_COMPLETED";
                lifecycleAction = newStatus.equalsIgnoreCase("In Progress") ? "IN_PROGRESS" : "COMPLETED";
                actor = resolveActor(explicitUserId, ceoId);
            } else if (newStatus != null && (newStatus.equalsIgnoreCase("Closed") || newStatus.equalsIgnoreCase("Resolved") || newStatus.equalsIgnoreCase("Archived"))) {
                logCategory = "QA";
                logAction = "PROJECT_OFFICIALLY_CLOSED";
                lifecycleAction = "CLOSED";
                actor = resolveActor(explicitUserId, adminId);
            } else {
                actor = resolveActor(explicitUserId, adminId);
            }

            // 📋 LOG LIFECYCLE EVENT INTO AUDIT TRAIL
            reportStatusLogService.log(
                    report,
                    lifecycleAction,
                    previousStatus,
                    newStatus,
                    adminRemarks,
                    actor,
                    "CPDO Admin",
                    "ADMIN",
                    report.getProofOfRepairImage()
            );

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
            return ResponseEntity.ok(isAutoArchivedNoDamage ? "ARCHIVED_NO_IMAGE" : "SUCCESS");

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
                                          @RequestParam(value = "imageFile", required = false) MultipartFile imageFile,
                                          HttpServletRequest request) {
        try {
            RoadReport existingReport = repository.findById(id).orElseThrow(() -> new RuntimeException("Report not found"));
            String previousStatus = existingReport.getStatus();

            if (description != null) existingReport.setDamageDescription(description);
            if (length != null) existingReport.setLength(length);
            if (width != null) existingReport.setWidth(width);
            if (lengthOfCulverts != null) existingReport.setLengthOfCulverts(lengthOfCulverts);
            if (numberOfBridges != null) existingReport.setNumberOfBridges(numberOfBridges);
            if (latitude != null) existingReport.setLatitude(latitude);
            if (longitude != null) existingReport.setLongitude(longitude);

            // ☁️ UPLOAD TO SUPABASE & RE-RUN AI ANALYSIS IF A NEW PHOTO WAS PROVIDED
            if (imageFile != null && !imageFile.isEmpty()) {
                // Strict Format & 5MB Size Validation
                String contentType = imageFile.getContentType() != null ? imageFile.getContentType().toLowerCase() : "";
                if (!contentType.equals("image/jpeg") && !contentType.equals("image/png") && !contentType.equals("image/webp") && !contentType.equals("image/jpg")) {
                    return ResponseEntity.badRequest().body(Map.of(
                            "error", "PHOTO_REJECTED",
                            "reason", "INVALID_FORMAT",
                            "message", "Invalid file format! Please upload a JPG, PNG, or WebP photo."
                    ));
                }
                if (imageFile.getSize() > 5 * 1024 * 1024) {
                    return ResponseEntity.badRequest().body(Map.of(
                            "error", "PHOTO_REJECTED",
                            "reason", "FILE_TOO_LARGE",
                            "message", "Image exceeds the 5MB size limit! Please upload a smaller photo."
                    ));
                }

                RoadInspectionService.InspectionResult aiResult;
                try {
                    aiResult = aiService.analyzeSeverity(imageFile);
                } catch (Exception e) {
                    System.err.println(">>> [AI WARNING - RESUBMISSION] Analysis failed: " + e.getMessage());
                    return ResponseEntity.badRequest().body(Map.of(
                            "error", "PHOTO_REJECTED",
                            "reason", "AI_UNAVAILABLE",
                            "message", "AI service could not process this photo. Please upload a clear photo of road damage."
                    ));
                }

                String newSeverity = aiResult != null ? aiResult.getSeverity() : "Unassessed";
                String newStatus = aiResult != null ? aiResult.getStatus() : "UNKNOWN";

                // Rejection gates on resubmission
                if ("Undamaged".equalsIgnoreCase(newSeverity) || "UNDAMAGED".equalsIgnoreCase(newStatus)) {
                    return ResponseEntity.badRequest().body(Map.of(
                            "error", "PHOTO_REJECTED",
                            "reason", "UNDAMAGED",
                            "message", "No road damage detected in updated photo. Please upload a photo with visible damage."
                    ));
                }
                if ("Unassessed".equalsIgnoreCase(newSeverity) || "LOW_CONFIDENCE".equalsIgnoreCase(newStatus) || "INVALID_SURFACE".equalsIgnoreCase(newStatus)) {
                    return ResponseEntity.badRequest().body(Map.of(
                            "error", "PHOTO_REJECTED",
                            "reason", "INVALID_OR_UNCLEAR",
                            "message", "Updated image does not show road distress. Please upload a clear photo of road damage."
                    ));
                }

                String cloudImageUrl = supabaseStorageService.uploadImage(imageFile);
                existingReport.setDamageImage(cloudImageUrl);
                existingReport.setAiSeverity(newSeverity); // Update visual screening
                existingReport.setSeverity(newSeverity);
                existingReport.setCvConfidenceScore(aiResult.getConfidence());
                System.out.println(">>> [AI SUCCESS - RESUBMISSION] Re-evaluated " + imageFile.getOriginalFilename() + " as " + newSeverity + " (" + aiResult.getConfidence() + "%)");
            }

            boolean isResubmitted = false;

            if (previousStatus != null && previousStatus.equalsIgnoreCase("Rejected")) {
                existingReport.setStatus("Resubmitted");
                isResubmitted = true;
            } else {
                existingReport.setStatus("Pending Validation");
            }

            existingReport.setAdminRemarks(null);
            repository.save(existingReport);

            User actor = (userId != null) ? userRepository.findById(userId).orElse(existingReport.getUser()) : existingReport.getUser();

            // 📋 LOG LIFECYCLE EVENT INTO AUDIT TRAIL
            reportStatusLogService.log(
                    existingReport,
                    isResubmitted ? "RESUBMITTED" : "REPORT_UPDATED",
                    previousStatus,
                    existingReport.getStatus(),
                    isResubmitted ? "Report revised and resubmitted with updated survey details." : "Report specifications updated.",
                    actor,
                    existingReport.getReportedBy(),
                    "Barangay Official",
                    existingReport.getDamageImage()
            );

            if (isResubmitted) {
                Long adminId = getAdminId();
                notificationService.sendNotification(
                        adminId,
                        "Report Resubmitted",
                        "A previously rejected report by " + existingReport.getReportedBy() + " has been revised and resubmitted.",
                        "REPORT"
                );
            }

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
            return ResponseEntity.ok(existingReport);

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
            User adminActor = resolveActor(userId, getAdminId());

            for (RoadReport report : allReports) {
                if ("Validated".equalsIgnoreCase(report.getStatus().trim())) {
                    boolean hasValidImage = report.getDamageImage() != null &&
                            !report.getDamageImage().trim().isEmpty() &&
                            !report.getDamageImage().trim().equalsIgnoreCase("no_image.jpg");

                    if (hasValidImage) {
                        String prev = report.getStatus();
                        report.setStatus("Dispatched to CEO");
                        dispatchedCount++;

                        // 📋 LOG LIFECYCLE EVENT INTO AUDIT TRAIL
                        reportStatusLogService.log(
                                report,
                                "DISPATCHED",
                                prev,
                                "Dispatched to CEO",
                                "Dispatched by CPDO to the City Engineer priority pool.",
                                adminActor,
                                "CPDO Admin",
                                "ADMIN",
                                null
                        );
                    } else {
                        // 🛡️ Safety fallback: Auto-archive any legacy validated report lacking photo evidence
                        String prev = report.getStatus();
                        report.setStatus("Archived");
                        LocalDateTime nowPst = LocalDateTime.now(ZoneId.of("Asia/Manila"));
                        report.setDateArchived(nowPst);
                        report.setActualCompletionDate(nowPst);
                        if (report.getAdminRemarks() == null || report.getAdminRemarks().trim().isEmpty()) {
                            report.setAdminRemarks("Auto-archived during dispatch: No photographic damage evidence.");
                        }

                        reportStatusLogService.log(
                                report,
                                "ARCHIVED_NO_DAMAGE",
                                prev,
                                "Archived",
                                "Auto-archived during dispatch filter: No photographic damage evidence.",
                                adminActor,
                                "CPDO Admin",
                                "ADMIN",
                                null
                        );
                    }
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
                repository.saveAll(allReports);
                sendLiveUpdate();
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

            User ceoActor = resolveActor(explicitUserId, getCeoId());

            for (RoadReport r : reportsToDefer) {
                String prev = r.getStatus();
                r.setStatus("Pending Budget");
                r.setRepairRemarks(reason);

                // 📋 LOG LIFECYCLE EVENT INTO AUDIT TRAIL
                reportStatusLogService.log(
                        r,
                        "PENDING_BUDGET",
                        prev,
                        "Pending Budget",
                        reason,
                        ceoActor,
                        "City Engineer",
                        "ENGINEER",
                        null
                );
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
            String previousStatus = report.getStatus();

            // ☁️ UPLOAD PROOF IMAGE DIRECTLY TO SUPABASE
            if (proofImage != null && !proofImage.isEmpty()) {
                String cloudProofUrl = supabaseStorageService.uploadImage(proofImage);
                report.setProofOfRepairImage(cloudProofUrl);
            }

            if (repairRemarks != null) report.setRepairRemarks(repairRemarks);

            report.setStatus("Completed");

            // 🎯 STAMP DATE ARCHIVED & ACTUAL COMPLETION DATE IN PHILIPPINE STANDARD TIME (PST)
            LocalDateTime completionTime = LocalDateTime.now(ZoneId.of("Asia/Manila"));
            report.setDateArchived(completionTime);
            report.setActualCompletionDate(completionTime);
            repository.save(report);

            sendStatusUpdateEmail(report, "Completed", repairRemarks);

            User ceoActor = resolveActor(userId, getCeoId());

            // 📋 LOG LIFECYCLE EVENT INTO AUDIT TRAIL
            reportStatusLogService.log(
                    report,
                    "COMPLETED",
                    previousStatus,
                    "Completed",
                    repairRemarks,
                    ceoActor,
                    "City Engineer",
                    "ENGINEER",
                    report.getProofOfRepairImage()
            );

            Long adminId = getAdminId();
            notificationService.sendNotification(
                    adminId,
                    "Repair Completed",
                    "The CEO has marked Report ID " + report.getId() + " as completed and uploaded proof. Awaiting your final QA.",
                    "REPORT"
            );

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
            String previousStatus = report.getStatus();

            String reason = payload.get("repairRemarks");
            String passedUserIdStr = payload.get("userId");
            Long explicitUserId = passedUserIdStr != null ? Long.valueOf(passedUserIdStr) : null;

            report.setRepairRemarks(reason);
            report.setStatus("Pending Budget");
            repository.save(report);

            sendStatusUpdateEmail(report, "Pending Budget", reason);

            User ceoActor = resolveActor(explicitUserId, getCeoId());

            // 📋 LOG LIFECYCLE EVENT INTO AUDIT TRAIL
            reportStatusLogService.log(
                    report,
                    "PENDING_BUDGET",
                    previousStatus,
                    "Pending Budget",
                    reason,
                    ceoActor,
                    "City Engineer",
                    "ENGINEER",
                    null
            );

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
            case "archived":
                subject = "RoadWise Update: Report Inspected & Archived";
                body.append("The CPDO has inspected this road report. Because no physical road damage or hazards were detected, this ticket has been officially archived as 'Passable / Good Condition'.\n");
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

            User adminActor = resolveActor(explicitUserId, getAdminId());
            int archivedCount = 0;
            for (RoadReport r : reportsToArchive) {
                if ("Pending Budget".equalsIgnoreCase(r.getStatus())) {
                    String prev = r.getStatus();
                    r.setStatus("Archived");

                    // 🎯 STAMP DATE ARCHIVED IN PHILIPPINE STANDARD TIME (PST)
                    r.setDateArchived(LocalDateTime.now(ZoneId.of("Asia/Manila")));
                    archivedCount++;

                    // 📋 LOG LIFECYCLE EVENT INTO AUDIT TRAIL
                    reportStatusLogService.log(
                            r,
                            "ARCHIVED",
                            prev,
                            "Archived",
                            "Archived during CPDO batch housekeeping.",
                            adminActor,
                            "CPDO Admin",
                            "ADMIN",
                            null
                    );
                }
            }

            repository.saveAll(reportsToArchive);

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
    // 10. GET AUDIT TRAIL / TIMELINE (FOR ALL USERS)
    // ==========================================
    @GetMapping("/{id}/timeline")
    public ResponseEntity<List<ReportStatusLog>> getReportTimeline(@PathVariable Long id) {
        return ResponseEntity.ok(reportStatusLogService.getTimeline(id));
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
    // SERVE UPLOADED IMAGES (LEGACY LOCAL FALLBACK)
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

    // ==========================================
    // 11. CPDO ADMIN: ISSUE DEADLINE REMINDER / EXPEDITE NOTICE
    // ==========================================
    @PostMapping("/{id}/remind-due")
    public ResponseEntity<?> sendDeadlineReminder(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, Object> payload,
            HttpServletRequest request) {
        try {
            RoadReport report = repository.findById(id)
                    .orElseThrow(() -> new RuntimeException("Report not found"));

            if (report.getTargetCompletionDate() == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "This project has no scheduled target completion date."));
            }

            LocalDateTime nowPst = LocalDateTime.now(ZoneId.of("Asia/Manila"));
            LocalDate todayPst = nowPst.toLocalDate();

            // 🛡️ ANTI-SPAM 12-HOUR COOLDOWN CHECK (Option A)
            if (report.getLastReminderSent() != null) {
                LocalDateTime cooldownExpires = report.getLastReminderSent().plusHours(12);
                if (nowPst.isBefore(cooldownExpires)) {
                    return ResponseEntity.badRequest().body(Map.of(
                            "error", "A reminder was already issued recently today. Please wait for the 12-hour cooldown period.",
                            "lastReminderSent", report.getLastReminderSent().toString()
                    ));
                }
            }

            long daysDiff = report.getTargetCompletionDate().toEpochDay() - todayPst.toEpochDay();
            String urgencyLabel;
            String notifTitle;
            String notifType;

            if (daysDiff < 0) {
                long overdueDays = Math.abs(daysDiff);
                urgencyLabel = "🚨 OVERDUE NOTICE (" + overdueDays + " day" + (overdueDays > 1 ? "s" : "") + " late)";
                notifTitle = "🚨 Urgent: Repair Overdue Notice";
                notifType = "CRITICAL";
            } else if (daysDiff == 0) {
                urgencyLabel = "⚠️ DUE TODAY";
                notifTitle = "⚠️ Action Required: Repair Due Today";
                notifType = "WARNING";
            } else {
                urgencyLabel = "⏳ UPCOMING DEADLINE (" + daysDiff + " day" + (daysDiff > 1 ? "s" : "") + " remaining)";
                notifTitle = "⏳ Reminder: Impending Repair Deadline";
                notifType = "REPORT";
            }

            report.setLastReminderSent(nowPst);
            repository.save(report);

            Long explicitUserId = (payload != null && payload.get("userId") != null)
                    ? Long.valueOf(payload.get("userId").toString())
                    : null;
            User adminActor = resolveActor(explicitUserId, getAdminId());

            Long ceoId = getCeoId();
            String safeRoadName = report.getCityRoadName() != null ? report.getCityRoadName() : "a road";
            String notifMsg = "CPDO Admin has flagged PRJ-" + String.format("%04d", report.getId()) + " (" + safeRoadName + "). Status: " + urgencyLabel + ". Please prioritize site completion or report progress.";

            if (ceoId != null) {
                notificationService.sendNotification(ceoId, notifTitle, notifMsg, notifType);

                userRepository.findById(ceoId).ifPresent(ceo -> {
                    if (ceo.getEmail() != null && !ceo.getEmail().trim().isEmpty()) {
                        String emailSubject = "RoadWise Alert: " + notifTitle + " [PRJ-" + String.format("%04d", report.getId()) + "]";
                        String emailBody = "Hello " + ceo.getFirstName() + ",\n\n" +
                                "The CPDO Admin has reviewed project schedules and issued a deadline notification regarding:\n\n" +
                                "• Project ID: #PRJ-" + String.format("%04d", report.getId()) + "\n" +
                                "• Road Name: " + safeRoadName + "\n" +
                                "• Target Completion Date: " + report.getTargetCompletionDate() + "\n" +
                                "• Current Urgency: " + urgencyLabel + "\n\n" +
                                "Please ensure crews are deployed or provide updated progress remarks in your engineering dashboard.\n\n" +
                                "Best regards,\nRoadWise CPDO Administration";
                        emailService.sendEmail(ceo.getEmail(), emailSubject, emailBody);
                    }
                });
            }

            reportStatusLogService.log(
                    report,
                    "DEADLINE_REMINDER_ISSUED",
                    report.getStatus(),
                    report.getStatus(),
                    "CPDO Admin issued an expedite/deadline reminder to the City Engineer (" + urgencyLabel + ").",
                    adminActor,
                    "CPDO Admin",
                    "ADMIN",
                    null
            );

            activityLogService.log(
                    adminActor,
                    "PROJECT",
                    "DEADLINE_REMINDER_SENT",
                    "#PRJ-" + String.format("%04d", report.getId()),
                    "Admin sent " + urgencyLabel + " to CEO for " + safeRoadName + ".",
                    "SUCCESS",
                    request
            );

            sendLiveUpdate();

            return ResponseEntity.ok(Map.of(
                    "message", "Deadline reminder successfully issued to the City Engineer.",
                    "lastReminderSent", nowPst.toString()
            ));

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body(Map.of("error", "Error issuing deadline reminder: " + e.getMessage()));
        }
    }

    // ==========================================
    // 12. CEO: SUBMIT REQUEST FOR TIME EXTENSION
    // ==========================================
    @PostMapping("/{id}/request-extension")
    public ResponseEntity<?> requestExtension(
            @PathVariable Long id,
            @RequestBody Map<String, Object> payload,
            HttpServletRequest request) {
        try {
            RoadReport report = repository.findById(id)
                    .orElseThrow(() -> new RuntimeException("Report not found"));

            String targetDateStr = (String) payload.get("extensionTargetDate");
            String reason = (String) payload.get("extensionReason");
            Long explicitUserId = payload.get("userId") != null ? Long.valueOf(payload.get("userId").toString()) : null;

            if (targetDateStr == null || targetDateStr.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Proposed extension date is required."));
            }
            if (reason == null || reason.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Extension justification reason is required."));
            }

            LocalDate proposedDate;
            try {
                proposedDate = LocalDate.parse(targetDateStr.trim());
            } catch (Exception e) {
                return ResponseEntity.badRequest().body(Map.of("error", "Invalid date format. Expected YYYY-MM-DD."));
            }

            if (report.getTargetCompletionDate() != null && !proposedDate.isAfter(report.getTargetCompletionDate())) {
                return ResponseEntity.badRequest().body(Map.of("error", "The requested date must be after the current target completion date (" + report.getTargetCompletionDate() + ")."));
            }

            report.setExtensionTargetDate(proposedDate);
            report.setExtensionReason(reason.trim());
            report.setExtensionStatus("PENDING");
            repository.save(report);

            User ceoActor = resolveActor(explicitUserId, getCeoId());
            Long adminId = getAdminId();
            String safeRoadName = report.getCityRoadName() != null ? report.getCityRoadName() : "a road";

            notificationService.sendNotification(
                    adminId,
                    "⏱️ Extension Request: PRJ-" + String.format("%04d", report.getId()),
                    "The CEO requested a deadline extension to " + proposedDate + " for " + safeRoadName + ". Justification: " + reason,
                    "WARNING"
            );

            reportStatusLogService.log(
                    report,
                    "EXTENSION_REQUESTED",
                    report.getStatus(),
                    report.getStatus(),
                    "CEO filed a formal time extension request to " + proposedDate + ". Reason: " + reason,
                    ceoActor,
                    "City Engineer",
                    "ENGINEER",
                    null
            );

            activityLogService.log(
                    ceoActor,
                    "PROJECT",
                    "EXTENSION_REQUESTED",
                    "#PRJ-" + String.format("%04d", report.getId()),
                    "CEO requested schedule extension to " + proposedDate + " on " + safeRoadName + ". Reason: " + reason,
                    "SUCCESS",
                    request
            );

            sendLiveUpdate();

            return ResponseEntity.ok(Map.of(
                    "message", "Extension request successfully submitted for CPDO review.",
                    "extensionTargetDate", proposedDate.toString(),
                    "extensionStatus", "PENDING"
            ));

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body(Map.of("error", "Error submitting extension request: " + e.getMessage()));
        }
    }

    // ==========================================
    // 13. CPDO ADMIN: REVIEW TIME EXTENSION (APPROVE / REJECT)
    // ==========================================
    @PutMapping("/{id}/review-extension")
    public ResponseEntity<?> reviewExtension(
            @PathVariable Long id,
            @RequestBody Map<String, Object> payload,
            HttpServletRequest request) {
        try {
            RoadReport report = repository.findById(id)
                    .orElseThrow(() -> new RuntimeException("Report not found"));

            String decision = (String) payload.get("decision");
            String adminRemarks = (String) payload.get("adminRemarks");
            Long explicitUserId = payload.get("userId") != null ? Long.valueOf(payload.get("userId").toString()) : null;

            if (decision == null || (!decision.equalsIgnoreCase("APPROVED") && !decision.equalsIgnoreCase("REJECTED"))) {
                return ResponseEntity.badRequest().body(Map.of("error", "Decision must be either 'APPROVED' or 'REJECTED'."));
            }

            if (!"PENDING".equalsIgnoreCase(report.getExtensionStatus())) {
                return ResponseEntity.badRequest().body(Map.of("error", "There is no pending extension request for this project."));
            }

            boolean isApproved = decision.equalsIgnoreCase("APPROVED");
            LocalDate oldTargetDate = report.getTargetCompletionDate();
            LocalDate newTargetDate = report.getExtensionTargetDate();

            if (isApproved) {
                report.setTargetCompletionDate(newTargetDate);
                report.setExtensionStatus("APPROVED");
            } else {
                report.setExtensionStatus("REJECTED");
            }

            repository.save(report);

            User adminActor = resolveActor(explicitUserId, getAdminId());
            Long ceoId = getCeoId();
            String safeRoadName = report.getCityRoadName() != null ? report.getCityRoadName() : "a road";

            if (ceoId != null) {
                String title = isApproved ? "✅ Time Extension Approved" : "❌ Time Extension Rejected";
                String msg = isApproved
                        ? "CPDO Admin approved your extension request for PRJ-" + String.format("%04d", report.getId()) + ". New target date: " + newTargetDate + "."
                        : "CPDO Admin rejected your extension request for PRJ-" + String.format("%04d", report.getId()) + "." + (adminRemarks != null && !adminRemarks.trim().isEmpty() ? " Remarks: " + adminRemarks : "");

                notificationService.sendNotification(ceoId, title, msg, isApproved ? "REPORT" : "WARNING");

                userRepository.findById(ceoId).ifPresent(ceo -> {
                    if (ceo.getEmail() != null && !ceo.getEmail().trim().isEmpty()) {
                        String subject = "RoadWise: " + title + " [PRJ-" + String.format("%04d", report.getId()) + "]";
                        String body = "Hello " + ceo.getFirstName() + ",\n\n" +
                                "Your formal request for a time extension on " + safeRoadName + " (PRJ-" + String.format("%04d", report.getId()) + ") has been " + (isApproved ? "APPROVED" : "REJECTED") + " by the CPDO Admin.\n\n" +
                                (isApproved ? "• New Target Completion Date: " + newTargetDate + "\n" : "• Original Target Completion Date remains: " + oldTargetDate + "\n") +
                                (adminRemarks != null && !adminRemarks.trim().isEmpty() ? "• Admin Remarks: " + adminRemarks + "\n\n" : "\n") +
                                "Please log into the Engineering Dashboard for updated project tracking.\n\n" +
                                "Best regards,\nRoadWise CPDO Administration";
                        emailService.sendEmail(ceo.getEmail(), subject, body);
                    }
                });
            }

            String actionLog = isApproved ? "EXTENSION_APPROVED" : "EXTENSION_REJECTED";
            String logRemarks = isApproved
                    ? "CPDO Admin approved extension to " + newTargetDate + " (Previous: " + oldTargetDate + ")." + (adminRemarks != null ? " Remarks: " + adminRemarks : "")
                    : "CPDO Admin rejected extension request." + (adminRemarks != null ? " Remarks: " + adminRemarks : "");

            reportStatusLogService.log(
                    report,
                    actionLog,
                    report.getStatus(),
                    report.getStatus(),
                    logRemarks,
                    adminActor,
                    "CPDO Admin",
                    "ADMIN",
                    null
            );

            activityLogService.log(
                    adminActor,
                    "QA",
                    actionLog,
                    "#PRJ-" + String.format("%04d", report.getId()),
                    logRemarks,
                    "SUCCESS",
                    request
            );

            sendLiveUpdate();

            return ResponseEntity.ok(Map.of(
                    "message", "Extension request " + (isApproved ? "approved" : "rejected") + " successfully.",
                    "extensionStatus", report.getExtensionStatus(),
                    "targetCompletionDate", report.getTargetCompletionDate() != null ? report.getTargetCompletionDate().toString() : ""
            ));

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body(Map.of("error", "Error reviewing extension request: " + e.getMessage()));
        }
    }
}