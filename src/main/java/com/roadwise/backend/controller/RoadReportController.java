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
// 🛡️ CONTROL 15: FILE UPLOAD SIZE & MIME ALLOW-LIST CONSTRAINTS
// ==========================================
    private static final long MAX_FILE_SIZE = 5L * 1024 * 1024; // Strict 5MB limit
    private static final Set<String> ALLOWED_IMAGE_TYPES = Set.of(
            "image/jpeg",
            "image/png",
            "image/webp",
            "image/jpg"
    );

    // ==========================================
    // 🛡️ CONTROL 12: SAN JOSE DEL MONTE GEOGRAPHIC BOUNDING BOX
    // ==========================================
    private static final double CSJDM_MIN_LAT = 14.7500;
    private static final double CSJDM_MAX_LAT = 14.9200;
    private static final double CSJDM_MIN_LNG = 121.0000;
    private static final double CSJDM_MAX_LNG = 121.1800;

    private boolean isWithinCsjdmBounds(Double lat, Double lng) {
        if (lat == null || lng == null) return true; // Coordinates optional or unpinned
        if (lat == 0.0 && lng == 0.0) return true;  // Unpinned fallback
        return lat >= CSJDM_MIN_LAT && lat <= CSJDM_MAX_LAT &&
                lng >= CSJDM_MIN_LNG && lng <= CSJDM_MAX_LNG;
    }

    // 🛡️ CONTROL 12: NON-NEGATIVE NUMERIC VALIDATOR
    private String validateNonNegativeDimensions(Double length, Double width, Double depth,
                                                 Double rLength, Double rWidth,
                                                 Double culverts, Integer bridges) {
        if (length != null && length < 0) return "Damage length cannot be negative.";
        if (width != null && width < 0) return "Damage width cannot be negative.";
        if (depth != null && depth < 0) return "Damage depth cannot be negative.";
        if (rLength != null && rLength < 0) return "Road length cannot be negative.";
        if (rWidth != null && rWidth < 0) return "Road width cannot be negative.";
        if (culverts != null && culverts < 0) return "Length of culverts cannot be negative.";
        if (bridges != null && bridges < 0) return "Number of bridges cannot be negative.";
        return null;
    }

    // ==========================================
    // 🛡️ CONTROL 6: SERVER-SIDE RBAC ROLE CHECKERS
    // ==========================================
    private User resolveUser(Long userId) {
        if (userId == null) return null;
        return userRepository.findById(userId).orElse(null);
    }

    private boolean isCpdoAdmin(User user) {
        if (user == null || user.getRole() == null) return false;
        String r = user.getRole().trim();
        return r.equalsIgnoreCase("CPDO Admin") || r.equalsIgnoreCase("Admin");
    }

    private boolean isCityEngineer(User user) {
        if (user == null || user.getRole() == null) return false;
        String r = user.getRole().trim();
        return r.equalsIgnoreCase("ENGINEER") || r.equalsIgnoreCase("City Engineer");
    }

    private boolean isBarangayOfficial(User user) {
        if (user == null || user.getRole() == null) return false;
        String r = user.getRole().trim();
        return r.equalsIgnoreCase("BARANGAY") || r.equalsIgnoreCase("Barangay Official");
    }

    // ==========================================
    // 🛡️ CONTROL 7: TERRITORIAL JURISDICTION CHECKER
    // ==========================================
    private String validateRoadTerritory(String roadId, Long targetBarangayId, User callingUser) {
        if (callingUser == null) return "User identity required.";

        if (isBarangayOfficial(callingUser)) {
            if (callingUser.getBarangay() == null || !callingUser.getBarangay().getId().equals(targetBarangayId)) {
                return "Security Violation: You are not authorized to submit reports outside your assigned barangay jurisdiction.";
            }
        }

        if (roadId != null && !roadId.trim().isEmpty()) {
            String cleanId = roadId.replace(" ", "").trim();
            Optional<CityRoad> matchedRoad = cityRoadRepository.findAll().stream()
                    .filter(cr -> cr.getRoadId() != null && cr.getRoadId().replace(" ", "").trim().equalsIgnoreCase(cleanId))
                    .findFirst();

            if (matchedRoad.isPresent()) {
                CityRoad road = matchedRoad.get();
                if (road.getBarangay() != null && !road.getBarangay().getId().equals(targetBarangayId)) {
                    return "Security Violation: Road '" + road.getRoadName() + "' belongs to " +
                            road.getBarangay().getBarangayName() + ", not your selected barangay.";
                }
            }
        }
        return null;
    }

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

        if (type.contains("sinkhole") || type.contains("washout")) {
            return "High";
        }

        if (type.contains("pothole") && dDepth >= 0.08 && isCoreRoad) {
            return "High";
        }

        int score = 0;
        if (type.contains("pothole")) {
            score += 4;
        } else if (type.contains("edge") || type.contains("rutting")) {
            score += 3;
        } else if (type.contains("crack")) {
            score += 2;
        } else {
            score += 2;
        }

        if (dDepth >= 0.12) {
            score += 3;
        } else if (dDepth >= 0.06) {
            score += 2;
        }

        if (area >= 3.0) {
            score += 2;
        } else if (area >= 1.0) {
            score += 1;
        }

        if (isCoreRoad) {
            score += 2;
        }

        if (score >= 7) {
            return "High";
        } else if (score >= 4) {
            return "Medium";
        } else {
            return "Low";
        }
    }

    // ==========================================
    // 🛡️ CONTROL 15: IMAGE VALIDATION HELPER (SIZE & ALLOW-LIST)
    // ==========================================
    private String validateImageFile(MultipartFile file) {
        if (file == null || file.isEmpty()) return null;

        // 1. Strict 5MB file size limit
        if (file.getSize() > MAX_FILE_SIZE) {
            String filename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "Upload";
            return "Image '" + filename + "' exceeds the strict 5MB size limit!";
        }

        // 2. Format allow-list check
        String contentType = file.getContentType() != null ? file.getContentType().toLowerCase().trim() : "";
        if (!ALLOWED_IMAGE_TYPES.contains(contentType)) {
            String filename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "Upload";
            return "Invalid file format for '" + filename + "'! Please upload JPG, PNG, or WebP.";
        }

        return null;
    }

    // ==========================================
    // 1. CREATE REPORT (BARANGAY OFFICIAL)
    // ==========================================
    @PostMapping(consumes = {"multipart/form-data"})
    public ResponseEntity<?> createReport(
            @ModelAttribute RoadReport report,
            @RequestParam(value = "barangayId", required = false) Long barangayId,
            @RequestParam(value = "userId", required = false) Long userId,
            // 📸 5 Perspective Photos
            @RequestParam(value = "imageWhole", required = false) MultipartFile imageWhole,
            @RequestParam(value = "imageTop", required = false) MultipartFile imageTop,
            @RequestParam(value = "imageLeft", required = false) MultipartFile imageLeft,
            @RequestParam(value = "imageRight", required = false) MultipartFile imageRight,
            @RequestParam(value = "imageClose", required = false) MultipartFile imageClose,
            // Legacy single image parameter
            @RequestParam(value = "imageFile", required = false) MultipartFile imageFile,
            HttpServletRequest request) {

        try {
            // 🛡️ CONTROL 6 RBAC: Ensure caller has valid user credentials
            User callingUser = resolveUser(userId);
            if (callingUser == null) {
                return ResponseEntity.status(403).body(Map.of("error", "Access Denied: Valid user identity is required to submit road reports."));
            }

            // 🛡️ CONTROL 12: Positive Numeric & Dimension Validation
            String dimError = validateNonNegativeDimensions(
                    report.getDamageLength(), report.getDamageWidth(), report.getDamageDepth(),
                    report.getLength(), report.getWidth(), report.getLengthOfCulverts(), report.getNumberOfBridges()
            );
            if (dimError != null) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "INVALID_NUMERIC_INPUT",
                        "message", dimError
                ));
            }

            // 🛡️ CONTROL 12: Geographic Boundary Validation (San Jose del Monte Bounds)
            if (!isWithinCsjdmBounds(report.getLatitude(), report.getLongitude())) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "OUT_OF_BOUNDS_COORDINATES",
                        "message", "Validation Error: GPS coordinates (" + report.getLatitude() + ", " + report.getLongitude() +
                                ") fall outside the territorial jurisdiction boundaries of San Jose del Monte City."
                ));
            }

            // 🛡️ CONTROL 7 SCOPING CHECK: Prevent cross-barangay record pollution
            if (barangayId != null) {
                String territoryError = validateRoadTerritory(report.getCityRoadId(), barangayId, callingUser);
                if (territoryError != null) {
                    return ResponseEntity.badRequest().body(Map.of(
                            "error", "TERRITORIAL_MISMATCH",
                            "message", territoryError
                    ));
                }
            }

            String severity;
            String status;
            double confidence;

            if (report.getDamageType() == null || report.getDamageType().trim().isEmpty()) {
                report.setDamageType("Pending Inspection");
            }
            if (report.getDamageLength() == null) report.setDamageLength(0.0);
            if (report.getDamageWidth() == null) report.setDamageWidth(0.0);
            if (report.getDamageDepth() == null) report.setDamageDepth(0.0);

            // 🔍 CHECK PERSPECTIVE COMBINATIONS
            boolean hasAnyAngle = (imageWhole != null && !imageWhole.isEmpty()) ||
                    (imageTop != null && !imageTop.isEmpty()) ||
                    (imageLeft != null && !imageLeft.isEmpty()) ||
                    (imageRight != null && !imageRight.isEmpty()) ||
                    (imageClose != null && !imageClose.isEmpty());

            boolean hasBatchPhotos = (imageWhole != null && !imageWhole.isEmpty()) && (imageTop != null && !imageTop.isEmpty());
            boolean hasLegacyPhoto = imageFile != null && !imageFile.isEmpty();

            // 🛡️ GUARD: Both Whole and Top-Down angles are mandatory when photos are submitted
            if (hasAnyAngle && !hasBatchPhotos) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "PHOTO_REJECTED",
                        "reason", "MISSING_MANDATORY_ANGLES",
                        "message", "Both the Road Context (Whole View) and the Top-Down View are mandatory when uploading photos."
                ));
            }

            if (hasBatchPhotos || hasLegacyPhoto) {
                MultipartFile[] filesToValidate = hasBatchPhotos
                        ? new MultipartFile[]{imageWhole, imageTop, imageLeft, imageRight, imageClose}
                        : new MultipartFile[]{imageFile};

                // 🛡️ CONTROL 15: Validate each photo against 5MB and image allow-list
                for (MultipartFile f : filesToValidate) {
                    String validationErr = validateImageFile(f);
                    if (validationErr != null) {
                        return ResponseEntity.badRequest().body(Map.of(
                                "error", "PHOTO_REJECTED",
                                "reason", "INVALID_FILE",
                                "message", validationErr
                        ));
                    }
                }

                RoadInspectionService.InspectionResult aiResult;
                try {
                    if (hasBatchPhotos) {
                        aiResult = aiService.analyzeBatchSeverity(imageWhole, imageTop, imageLeft, imageRight, imageClose);
                    } else {
                        aiResult = aiService.analyzeSeverity(imageFile);
                    }
                } catch (Exception e) {
                    System.err.println(">>> [AI WARNING] Computer Vision analysis failed: " + e.getMessage());
                    return ResponseEntity.badRequest().body(Map.of(
                            "error", "PHOTO_REJECTED",
                            "reason", "AI_UNAVAILABLE",
                            "message", "AI service is currently unable to analyze the photo(s). Please ensure clear road distress photos are uploaded."
                    ));
                }

                severity = aiResult != null ? aiResult.getSeverity() : "Unassessed";
                status = aiResult != null ? aiResult.getStatus() : "UNKNOWN";
                confidence = aiResult != null ? aiResult.getConfidence() : 0.0;

                // 🚫 REJECTION GATES
                if ("Undamaged".equalsIgnoreCase(severity) || "UNDAMAGED".equalsIgnoreCase(status)) {
                    return ResponseEntity.badRequest().body(Map.of(
                            "error", "PHOTO_REJECTED",
                            "reason", "UNDAMAGED",
                            "message", "No road damage detected in the photos. Please upload photos with visible distress."
                    ));
                }

                if ("Unassessed".equalsIgnoreCase(severity) || "LOW_CONFIDENCE".equalsIgnoreCase(status) || "INVALID_SURFACE".equalsIgnoreCase(status)) {
                    return ResponseEntity.badRequest().body(Map.of(
                            "error", "PHOTO_REJECTED",
                            "reason", "INVALID_OR_UNCLEAR",
                            "message", "Photos do not show clear road damage or are not a road surface. Please upload clear photos of road damage."
                    ));
                }

                // ☁️ UPLOAD CONFIRMED PHOTOS TO SUPABASE
                if (hasBatchPhotos) {
                    String urlWhole = supabaseStorageService.uploadImage(imageWhole);
                    String urlTop = supabaseStorageService.uploadImage(imageTop);
                    String urlLeft = (imageLeft != null && !imageLeft.isEmpty()) ? supabaseStorageService.uploadImage(imageLeft) : null;
                    String urlRight = (imageRight != null && !imageRight.isEmpty()) ? supabaseStorageService.uploadImage(imageRight) : null;
                    String urlClose = (imageClose != null && !imageClose.isEmpty()) ? supabaseStorageService.uploadImage(imageClose) : null;

                    report.setDamageImageWhole(urlWhole);
                    report.setDamageImageTop(urlTop);
                    report.setDamageImageLeft(urlLeft);
                    report.setDamageImageRight(urlRight);
                    report.setDamageImageClose(urlClose);
                    report.setDamageImage(urlWhole);
                } else {
                    String cloudImageUrl = supabaseStorageService.uploadImage(imageFile);
                    report.setDamageImage(cloudImageUrl);
                    report.setDamageImageWhole(cloudImageUrl);
                    report.setDamageImageTop(cloudImageUrl);
                }

                report.setAiSeverity(severity);
                report.setSeverity(severity);
                report.setCvConfidenceScore(confidence);
                if (aiResult != null && aiResult.getDetectedDamageType() != null && "Pending Inspection".equalsIgnoreCase(report.getDamageType())) {
                    report.setDamageType(aiResult.getDetectedDamageType());
                }

                System.out.println(">>> [AI SUCCESS] Evaluated multi-angle submission as " + severity + " (" + confidence + "%)");
            } else {
                report.setDamageImage(null);
                report.setDamageImageWhole(null);
                report.setDamageImageTop(null);
                report.setDamageImageLeft(null);
                report.setDamageImageRight(null);
                report.setDamageImageClose(null);
                report.setAiSeverity("None");
                report.setSeverity("None");
                report.setCvConfidenceScore(0.0);
                report.setDamageType("None");
            }

            if (barangayId != null) {
                Barangay foundBarangay = barangayRepository.findById(barangayId).orElse(null);
                report.setBarangay(foundBarangay);
            }

            report.setUser(callingUser);
            report.setReportedBy(callingUser.getFirstName() + " " + callingUser.getLastName());
            report.setStatus("Pending Validation");

            // 💾 COMMIT TO DATABASE
            RoadReport savedReport = repository.save(report);

            // 📋 LOG LIFECYCLE EVENT
            reportStatusLogService.log(
                    savedReport,
                    "SUBMITTED",
                    null,
                    "Pending Validation",
                    "Road damage report submitted for '" + savedReport.getCityRoadName() + "'.",
                    callingUser,
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

            // ⏱️ AUDIT LOG
            activityLogService.log(
                    callingUser,
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
            return ResponseEntity.status(500).body(Map.of("error", "Failed to upload images or submit report: " + e.getMessage()));
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
    public ResponseEntity<?> updateReportStatus(
            @PathVariable Long id,
            @RequestBody Map<String, Object> payload,
            HttpServletRequest request) {

        String passedUserIdStr = payload.get("userId") != null ? payload.get("userId").toString() : null;
        Long explicitUserId = passedUserIdStr != null ? Long.valueOf(passedUserIdStr) : null;
        User callingUser = resolveUser(explicitUserId);

        // 🛡️ CONTROL 6 RBAC: Verify authenticated caller identity
        if (callingUser == null) {
            return ResponseEntity.status(403).body("Access Denied: Missing or invalid user identity.");
        }

        String newStatus = payload.get("status") != null ? payload.get("status").toString() : null;
        String adminRemarks = payload.get("adminRemarks") != null ? payload.get("adminRemarks").toString() : null;

        // 🛡️ CONTROL 6 RBAC: Separation of Office Duties Check
        boolean isCeoStartRepair = newStatus != null && newStatus.equalsIgnoreCase("In Progress") &&
                (adminRemarks == null || adminRemarks.trim().isEmpty());

        if (isCeoStartRepair) {
            if (!isCityEngineer(callingUser) && !isCpdoAdmin(callingUser)) {
                return ResponseEntity.status(403).body("Access Denied: Only the City Engineering Office (CEO) can deploy crews and mark repairs as In Progress.");
            }
        } else {
            if (!isCpdoAdmin(callingUser)) {
                return ResponseEntity.status(403).body("Access Denied: Only CPDO Administrators are authorized to validate, reject, or officially close reports.");
            }
        }

        Optional<RoadReport> reportOpt = repository.findById(id);
        if (reportOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        RoadReport report = reportOpt.get();
        String previousStatus = report.getStatus();

        // 🎯 CAPTURE TARGET COMPLETION DATE
        Object targetDateObj = payload.get("targetCompletionDate");
        if (targetDateObj != null && !targetDateObj.toString().trim().isEmpty()) {
            try {
                report.setTargetCompletionDate(LocalDate.parse(targetDateObj.toString().trim()));
            } catch (Exception e) {
                System.err.println("Failed to parse target completion date: " + targetDateObj);
            }
        }

        // 📐 CAPTURE CPDO TECHNICAL DAMAGE ASSESSMENT & ENFORCE NON-NEGATIVE VALUES
        Double dLength = null, dWidth = null, dDepth = null;
        if (payload.get("damageLength") != null) {
            try { dLength = Double.valueOf(payload.get("damageLength").toString()); } catch (Exception ignored) {}
        }
        if (payload.get("damageWidth") != null) {
            try { dWidth = Double.valueOf(payload.get("damageWidth").toString()); } catch (Exception ignored) {}
        }
        if (payload.get("damageDepth") != null) {
            try { dDepth = Double.valueOf(payload.get("damageDepth").toString()); } catch (Exception ignored) {}
        }

        Double rWidth = null, rLength = null, culverts = null;
        Integer bridges = null;
        if (payload.get("width") != null) {
            try { rWidth = Double.valueOf(payload.get("width").toString()); } catch (Exception ignored) {}
        }
        if (payload.get("length") != null) {
            try { rLength = Double.valueOf(payload.get("length").toString()); } catch (Exception ignored) {}
        }
        if (payload.get("lengthOfCulverts") != null) {
            try { culverts = Double.valueOf(payload.get("lengthOfCulverts").toString()); } catch (Exception ignored) {}
        }
        if (payload.get("numberOfBridges") != null) {
            try { bridges = Integer.valueOf(payload.get("numberOfBridges").toString()); } catch (Exception ignored) {}
        }

        // 🛡️ CONTROL 12: Positive Numeric Validator
        String dimError = validateNonNegativeDimensions(dLength, dWidth, dDepth, rLength, rWidth, culverts, bridges);
        if (dimError != null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "INVALID_NUMERIC_INPUT",
                    "message", dimError
            ));
        }

        if (payload.get("damageType") != null) {
            report.setDamageType(payload.get("damageType").toString());
        }
        if (dLength != null) report.setDamageLength(dLength);
        if (dWidth != null) report.setDamageWidth(dWidth);
        if (dDepth != null) report.setDamageDepth(dDepth);

        // 🛣️ SYNC ROAD METRICS WITH MASTER INVENTORY
        if (rWidth != null) report.setWidth(rWidth);
        if (rLength != null) report.setLength(rLength);
        if (culverts != null) report.setLengthOfCulverts(culverts);
        if (bridges != null) report.setNumberOfBridges(bridges);

        if (rWidth != null && rWidth > 0 && report.getCityRoadId() != null) {
            String cleanTargetRoadId = report.getCityRoadId().replace(" ", "").trim();
            List<CityRoad> matchedRoads = cityRoadRepository.findAll().stream()
                    .filter(cr -> cr.getRoadId() != null && cr.getRoadId().replace(" ", "").trim().equalsIgnoreCase(cleanTargetRoadId))
                    .toList();

            for (CityRoad cr : matchedRoads) {
                cr.setRoadWidth(rWidth);
                if (report.getLength() != null && report.getLength() > 0) cr.setRoadLength(report.getLength());
                if (report.getLengthOfCulverts() != null) cr.setLengthOfCulverts(report.getLengthOfCulverts());
                if (report.getNumberOfBridges() != null) cr.setNumberOfBridges(report.getNumberOfBridges());
            }
            if (!matchedRoads.isEmpty()) {
                cityRoadRepository.saveAll(matchedRoads);
                System.out.println(">>> [SYNC SUCCESS] Master CityRoad record '" + report.getCityRoadId() + "' updated with Road Width: " + rWidth + "m");
            }
        }

        // 📐 RECALCULATE OFFICIAL ENGINEERING SEVERITY
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

        // 🛡️ AUTO-ARCHIVE REPORTS WITHOUT DAMAGE EVIDENCE
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
                    Optional<User> ceoOpt = userRepository.findById(ceoId);
                    if (ceoOpt.isPresent()) {
                        User ceo = ceoOpt.get();
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
                    }
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
        } else if (newStatus != null && (newStatus.equalsIgnoreCase("Validated") || newStatus.equalsIgnoreCase("Rejected"))) {
            logCategory = "QA";
            logAction = newStatus.equalsIgnoreCase("Validated") ? "REPORT_VALIDATED" : "REPORT_REJECTED";
            lifecycleAction = newStatus.equalsIgnoreCase("Validated") ? "VALIDATED" : "REJECTED";
        } else if (newStatus != null && newStatus.equalsIgnoreCase("In Progress") && adminRemarks != null && !adminRemarks.trim().isEmpty()) {
            logCategory = "QA";
            logAction = "REPAIR_REWORK_REQUESTED";
            lifecycleAction = "REPAIR_REWORK_REQUESTED";
        } else if (newStatus != null && (newStatus.equalsIgnoreCase("In Progress") || newStatus.equalsIgnoreCase("Completed"))) {
            logCategory = "PROJECT";
            logAction = newStatus.equalsIgnoreCase("In Progress") ? "REPAIR_IN_PROGRESS" : "REPAIR_COMPLETED";
            lifecycleAction = newStatus.equalsIgnoreCase("In Progress") ? "IN_PROGRESS" : "COMPLETED";
        } else if (newStatus != null && (newStatus.equalsIgnoreCase("Closed") || newStatus.equalsIgnoreCase("Resolved") || newStatus.equalsIgnoreCase("Archived"))) {
            logCategory = "QA";
            logAction = "PROJECT_OFFICIALLY_CLOSED";
            lifecycleAction = "CLOSED";
        }

        // 📋 LOG LIFECYCLE EVENT INTO AUDIT TRAIL
        reportStatusLogService.log(
                report,
                lifecycleAction,
                previousStatus,
                newStatus,
                adminRemarks,
                callingUser,
                callingUser.getRole() != null ? callingUser.getRole() : "CPDO Admin",
                isCeoStartRepair ? "ENGINEER" : "ADMIN",
                report.getProofOfRepairImage()
        );

        activityLogService.log(
                callingUser,
                logCategory,
                logAction,
                "#PRJ-" + String.format("%04d", report.getId()),
                logDesc,
                "SUCCESS",
                request
        );

        sendLiveUpdate();
        return ResponseEntity.ok(isAutoArchivedNoDamage ? "ARCHIVED_NO_IMAGE" : "SUCCESS");
    }

    // ==========================================
    // 3. UPDATE REPORT (RESUBMISSION BY BARANGAY OFFICIAL)
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
                                          // 📸 5 Perspective Photos
                                          @RequestParam(value = "imageWhole", required = false) MultipartFile imageWhole,
                                          @RequestParam(value = "imageTop", required = false) MultipartFile imageTop,
                                          @RequestParam(value = "imageLeft", required = false) MultipartFile imageLeft,
                                          @RequestParam(value = "imageRight", required = false) MultipartFile imageRight,
                                          @RequestParam(value = "imageClose", required = false) MultipartFile imageClose,
                                          @RequestParam(value = "imageFile", required = false) MultipartFile imageFile,
                                          HttpServletRequest request) {
        try {
            User callingUser = resolveUser(userId);

            // 🛡️ CONTROL 6 RBAC: Ensure caller has valid user credentials
            if (callingUser == null) {
                return ResponseEntity.status(403).body(Map.of("error", "Access Denied: Valid user identity is required to edit reports."));
            }

            RoadReport existingReport = repository.findById(id).orElseThrow(() -> new RuntimeException("Report not found"));

            // 🛡️ CONTROL 6 RBAC: Only Barangay Officials or CPDO Admins can edit/resubmit
            if (!isBarangayOfficial(callingUser) && !isCpdoAdmin(callingUser)) {
                return ResponseEntity.status(403).body(Map.of("error", "Access Denied: Only Barangay Officials can revise and resubmit damage reports."));
            }

            // 🛡️ CONTROL 7 SCOPING CHECK: Ensure Barangay Official owns the report being edited
            if (isBarangayOfficial(callingUser)) {
                if (existingReport.getBarangay() != null && callingUser.getBarangay() != null) {
                    if (!existingReport.getBarangay().getId().equals(callingUser.getBarangay().getId())) {
                        return ResponseEntity.status(403).body(Map.of(
                                "error", "ACCESS_DENIED",
                                "message", "Security Violation: You cannot edit inspection reports belonging to another barangay."
                        ));
                    }
                }
            }

            // 🛡️ CONTROL 12: Positive Numeric & Dimension Validation
            String dimError = validateNonNegativeDimensions(null, null, null, length, width, lengthOfCulverts, numberOfBridges);
            if (dimError != null) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "INVALID_NUMERIC_INPUT",
                        "message", dimError
                ));
            }

            // 🛡️ CONTROL 12: Geographic Boundary Validation
            if (!isWithinCsjdmBounds(latitude, longitude)) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "OUT_OF_BOUNDS_COORDINATES",
                        "message", "Validation Error: GPS coordinates fall outside the territorial jurisdiction boundaries of San Jose del Monte City."
                ));
            }

            String previousStatus = existingReport.getStatus();

            if (description != null) existingReport.setDamageDescription(description);
            if (length != null) existingReport.setLength(length);
            if (width != null) existingReport.setWidth(width);
            if (lengthOfCulverts != null) existingReport.setLengthOfCulverts(lengthOfCulverts);
            if (numberOfBridges != null) existingReport.setNumberOfBridges(numberOfBridges);
            if (latitude != null) existingReport.setLatitude(latitude);
            if (longitude != null) existingReport.setLongitude(longitude);

            // 🔍 CHECK PERSPECTIVE COMBINATIONS
            boolean hasAnyAngle = (imageWhole != null && !imageWhole.isEmpty()) ||
                    (imageTop != null && !imageTop.isEmpty()) ||
                    (imageLeft != null && !imageLeft.isEmpty()) ||
                    (imageRight != null && !imageRight.isEmpty()) ||
                    (imageClose != null && !imageClose.isEmpty());

            boolean hasBatchPhotos = (imageWhole != null && !imageWhole.isEmpty()) && (imageTop != null && !imageTop.isEmpty());
            boolean hasLegacyPhoto = imageFile != null && !imageFile.isEmpty();

            // 🛡️ GUARD: Both Whole and Top-Down angles are mandatory when photos are updated
            if (hasAnyAngle && !hasBatchPhotos) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "PHOTO_REJECTED",
                        "reason", "MISSING_MANDATORY_ANGLES",
                        "message", "Both the Road Context (Whole View) and the Top-Down View are required when updating photos."
                ));
            }

            if (hasBatchPhotos || hasLegacyPhoto) {
                MultipartFile[] filesToValidate = hasBatchPhotos
                        ? new MultipartFile[]{imageWhole, imageTop, imageLeft, imageRight, imageClose}
                        : new MultipartFile[]{imageFile};

                // 🛡️ CONTROL 15: Validate each photo against 5MB and image allow-list
                for (MultipartFile f : filesToValidate) {
                    String validationErr = validateImageFile(f);
                    if (validationErr != null) {
                        return ResponseEntity.badRequest().body(Map.of(
                                "error", "PHOTO_REJECTED",
                                "reason", "INVALID_FILE",
                                "message", validationErr
                        ));
                    }
                }

                RoadInspectionService.InspectionResult aiResult;
                try {
                    if (hasBatchPhotos) {
                        aiResult = aiService.analyzeBatchSeverity(imageWhole, imageTop, imageLeft, imageRight, imageClose);
                    } else {
                        aiResult = aiService.analyzeSeverity(imageFile);
                    }
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

                if ("Undamaged".equalsIgnoreCase(newSeverity) || "UNDAMAGED".equalsIgnoreCase(newStatus)) {
                    return ResponseEntity.badRequest().body(Map.of(
                            "error", "PHOTO_REJECTED",
                            "reason", "UNDAMAGED",
                            "message", "No road damage detected in updated photos. Please upload photos with visible damage."
                    ));
                }
                if ("Unassessed".equalsIgnoreCase(newSeverity) || "LOW_CONFIDENCE".equalsIgnoreCase(newStatus) || "INVALID_SURFACE".equalsIgnoreCase(newStatus)) {
                    return ResponseEntity.badRequest().body(Map.of(
                            "error", "PHOTO_REJECTED",
                            "reason", "INVALID_OR_UNCLEAR",
                            "message", "Updated images do not show road distress. Please upload clear photos of road damage."
                    ));
                }

                if (hasBatchPhotos) {
                    String urlWhole = supabaseStorageService.uploadImage(imageWhole);
                    String urlTop = supabaseStorageService.uploadImage(imageTop);
                    String urlLeft = (imageLeft != null && !imageLeft.isEmpty()) ? supabaseStorageService.uploadImage(imageLeft) : null;
                    String urlRight = (imageRight != null && !imageRight.isEmpty()) ? supabaseStorageService.uploadImage(imageRight) : null;
                    String urlClose = (imageClose != null && !imageClose.isEmpty()) ? supabaseStorageService.uploadImage(imageClose) : null;

                    existingReport.setDamageImageWhole(urlWhole);
                    existingReport.setDamageImageTop(urlTop);
                    existingReport.setDamageImageLeft(urlLeft);
                    existingReport.setDamageImageRight(urlRight);
                    existingReport.setDamageImageClose(urlClose);
                    existingReport.setDamageImage(urlWhole);
                } else {
                    String cloudImageUrl = supabaseStorageService.uploadImage(imageFile);
                    existingReport.setDamageImage(cloudImageUrl);
                    existingReport.setDamageImageWhole(cloudImageUrl);
                    existingReport.setDamageImageTop(cloudImageUrl);
                }

                existingReport.setAiSeverity(newSeverity);
                existingReport.setSeverity(newSeverity);
                existingReport.setCvConfidenceScore(aiResult.getConfidence());
                if (aiResult.getDetectedDamageType() != null) {
                    existingReport.setDamageType(aiResult.getDetectedDamageType());
                }
                System.out.println(">>> [AI SUCCESS - RESUBMISSION] Re-evaluated as " + newSeverity + " (" + aiResult.getConfidence() + "%)");
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

            // 📋 LOG LIFECYCLE EVENT INTO AUDIT TRAIL
            reportStatusLogService.log(
                    existingReport,
                    isResubmitted ? "RESUBMITTED" : "REPORT_UPDATED",
                    previousStatus,
                    existingReport.getStatus(),
                    isResubmitted ? "Report revised and resubmitted with updated survey details." : "Report specifications updated.",
                    callingUser,
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
                    callingUser,
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
    // 4. BATCH DISPATCH TO CEO (CPDO ADMIN ONLY)
    // ==========================================
    @PutMapping("/dispatch-masterlist")
    public ResponseEntity<String> dispatchMasterlistToCEO(
            @RequestParam(value = "userId", required = false) Long userId,
            HttpServletRequest request) {
        try {
            User callingUser = resolveUser(userId);

            // 🛡️ CONTROL 6 RBAC: Strictly restricted to CPDO Admin
            if (!isCpdoAdmin(callingUser)) {
                return ResponseEntity.status(403).body("Access Denied: Only CPDO Administrators can dispatch the masterlist to the CEO priority pool.");
            }

            List<RoadReport> allReports = repository.findAll();
            int dispatchedCount = 0;

            for (RoadReport report : allReports) {
                if ("Validated".equalsIgnoreCase(report.getStatus().trim())) {
                    boolean hasValidImage = report.getDamageImage() != null &&
                            !report.getDamageImage().trim().isEmpty() &&
                            !report.getDamageImage().trim().equalsIgnoreCase("no_image.jpg");

                    if (hasValidImage) {
                        String prev = report.getStatus();
                        report.setStatus("Dispatched to CEO");
                        dispatchedCount++;

                        reportStatusLogService.log(
                                report,
                                "DISPATCHED",
                                prev,
                                "Dispatched to CEO",
                                "Dispatched by CPDO to the City Engineer priority pool.",
                                callingUser,
                                "CPDO Admin",
                                "ADMIN",
                                null
                        );
                    } else {
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
                                callingUser,
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

                    Optional<User> ceoOpt = userRepository.findById(ceoId);
                    if (ceoOpt.isPresent()) {
                        User ceo = ceoOpt.get();
                        if (ceo.getEmail() != null && !ceo.getEmail().isEmpty()) {
                            String subject = "RoadWise: Masterlist Dispatched";
                            String body = "Hello " + ceo.getFirstName() + ",\n\n" +
                                    "The CPDO has officially dispatched " + finalDispatchedCount + " validated road reports to your priority pool.\n" +
                                    "Please review the Engineering Dashboard.\n\n" +
                                    "Best regards,\nRoadWise SJDM System";
                            emailService.sendEmail(ceo.getEmail(), subject, body);
                        }
                    }
                }

                activityLogService.log(
                        callingUser,
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
    // 5B. CEO BATCH DEFER REPAIRS (PENDING BUDGET) - CEO ONLY
    // ==========================================
    @PostMapping("/batch/defer")
    public ResponseEntity<?> batchDeferReports(@RequestBody Map<String, Object> payload, HttpServletRequest request) {
        try {
            Long explicitUserId = payload.get("userId") != null ? Long.valueOf(payload.get("userId").toString()) : null;
            User callingUser = resolveUser(explicitUserId);

            // 🛡️ CONTROL 6 RBAC: Strictly restricted to City Engineer (CEO)
            if (!isCityEngineer(callingUser)) {
                return ResponseEntity.status(403).body(Map.of("error", "Access Denied: Only the City Engineering Office (CEO) is authorized to defer project repairs."));
            }

            String reason = (String) payload.get("repairRemarks");
            Object idsObj = payload.get("reportIds");

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
                String prev = r.getStatus();
                r.setStatus("Pending Budget");
                r.setRepairRemarks(reason);

                reportStatusLogService.log(
                        r,
                        "PENDING_BUDGET",
                        prev,
                        "Pending Budget",
                        reason,
                        callingUser,
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
                    callingUser,
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
    // 5. CEO MARK AS COMPLETED (WITH PROOF) - CEO ONLY
    // ==========================================
    @PostMapping(value = "/{id}/complete", consumes = {"multipart/form-data"})
    public ResponseEntity<?> completeReport(
            @PathVariable Long id,
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "repairRemarks", required = false) String repairRemarks,
            @RequestParam(value = "proofImage", required = true) MultipartFile proofImage,
            HttpServletRequest request) {

        try {
            User callingUser = resolveUser(userId);

            // 🛡️ CONTROL 6 RBAC: Strictly restricted to City Engineer (CEO)
            if (!isCityEngineer(callingUser)) {
                return ResponseEntity.status(403).body(Map.of("error", "Access Denied: Only the City Engineering Office (CEO) can submit repair proof and mark projects as Completed."));
            }

            // 🛡️ CONTROL 15: Validate Proof of Repair Photo (Size <= 5MB and Image Allow-list)
            if (proofImage == null || proofImage.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "PHOTO_REJECTED",
                        "message", "Proof of repair photo is mandatory."
                ));
            }

            String proofValidationErr = validateImageFile(proofImage);
            if (proofValidationErr != null) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "PHOTO_REJECTED",
                        "reason", "INVALID_FILE",
                        "message", proofValidationErr
                ));
            }

            RoadReport report = repository.findById(id).orElseThrow(() -> new RuntimeException("Report not found"));
            String previousStatus = report.getStatus();

            String cloudProofUrl = supabaseStorageService.uploadImage(proofImage);
            report.setProofOfRepairImage(cloudProofUrl);

            if (repairRemarks != null) report.setRepairRemarks(repairRemarks);

            report.setStatus("Completed");

            LocalDateTime completionTime = LocalDateTime.now(ZoneId.of("Asia/Manila"));
            report.setDateArchived(completionTime);
            report.setActualCompletionDate(completionTime);
            repository.save(report);

            sendStatusUpdateEmail(report, "Completed", repairRemarks);

            reportStatusLogService.log(
                    report,
                    "COMPLETED",
                    previousStatus,
                    "Completed",
                    repairRemarks,
                    callingUser,
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
                    callingUser,
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
    // 5B. CEO DEFER REPAIR (SINGLE) - CEO ONLY
    // ==========================================
    @PutMapping("/{id}/defer")
    public ResponseEntity<?> deferReport(
            @PathVariable Long id,
            @RequestBody Map<String, String> payload,
            HttpServletRequest request) {
        try {
            String passedUserIdStr = payload.get("userId");
            Long explicitUserId = passedUserIdStr != null ? Long.valueOf(passedUserIdStr) : null;
            User callingUser = resolveUser(explicitUserId);

            // 🛡️ CONTROL 6 RBAC: Strictly restricted to City Engineer (CEO)
            if (!isCityEngineer(callingUser)) {
                return ResponseEntity.status(403).body(Map.of("error", "Access Denied: Only the City Engineering Office (CEO) is authorized to defer project repairs."));
            }

            RoadReport report = repository.findById(id).orElseThrow(() -> new RuntimeException("Report not found"));
            String previousStatus = report.getStatus();

            String reason = payload.get("repairRemarks");

            report.setRepairRemarks(reason);
            report.setStatus("Pending Budget");
            repository.save(report);

            sendStatusUpdateEmail(report, "Pending Budget", reason);

            reportStatusLogService.log(
                    report,
                    "PENDING_BUDGET",
                    previousStatus,
                    "Pending Budget",
                    reason,
                    callingUser,
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
                    callingUser,
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
    // 8. ADMIN BATCH ARCHIVE (CPDO ADMIN ONLY)
    // ==========================================
    @PostMapping("/batch/archive")
    public ResponseEntity<?> batchArchiveReports(
            @RequestBody Map<String, Object> payload,
            HttpServletRequest request) {
        try {
            Long explicitUserId = payload.get("userId") != null ? Long.valueOf(payload.get("userId").toString()) : null;
            User callingUser = resolveUser(explicitUserId);

            // 🛡️ CONTROL 6 RBAC: Strictly restricted to CPDO Admin
            if (!isCpdoAdmin(callingUser)) {
                return ResponseEntity.status(403).body(Map.of("error", "Access Denied: Only CPDO Administrators are authorized to batch archive project records."));
            }

            Object idsObj = payload.get("reportIds");
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
                    String prev = r.getStatus();
                    r.setStatus("Archived");
                    r.setDateArchived(LocalDateTime.now(ZoneId.of("Asia/Manila")));
                    archivedCount++;

                    reportStatusLogService.log(
                            r,
                            "ARCHIVED",
                            prev,
                            "Archived",
                            "Archived during CPDO batch housekeeping.",
                            callingUser,
                            "CPDO Admin",
                            "ADMIN",
                            null
                    );
                }
            }

            repository.saveAll(reportsToArchive);

            activityLogService.log(
                    callingUser,
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
    // 9. ENTERPRISE ANNUAL AUDIT ROLLOVER (CPDO ADMIN ONLY)
    // ==========================================
    @PostMapping("/rollover-annual-cycle")
    public ResponseEntity<?> executeAnnualRollover(
            @RequestParam(value = "userId", required = false) Long userId,
            HttpServletRequest request) {
        try {
            User callingUser = resolveUser(userId);

            // 🛡️ CONTROL 6 RBAC: Strictly restricted to CPDO Admin
            if (!isCpdoAdmin(callingUser)) {
                return ResponseEntity.status(403).body(Map.of("error", "Access Denied: Only CPDO Administrators can execute the annual inventory rollover cycle."));
            }

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

            activityLogService.log(
                    callingUser,
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
    // 11. CPDO ADMIN: ISSUE DEADLINE REMINDER / EXPEDITE NOTICE (CPDO ONLY)
    // ==========================================
    @PostMapping("/{id}/remind-due")
    public ResponseEntity<?> sendDeadlineReminder(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, Object> payload,
            HttpServletRequest request) {
        try {
            Long explicitUserId = (payload != null && payload.get("userId") != null)
                    ? Long.valueOf(payload.get("userId").toString())
                    : null;
            User callingUser = resolveUser(explicitUserId);

            // 🛡️ CONTROL 6 RBAC: Strictly restricted to CPDO Admin
            if (!isCpdoAdmin(callingUser)) {
                return ResponseEntity.status(403).body(Map.of("error", "Access Denied: Only CPDO Administrators can issue deadline reminders to the City Engineer."));
            }

            RoadReport report = repository.findById(id)
                    .orElseThrow(() -> new RuntimeException("Report not found"));

            if (report.getTargetCompletionDate() == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "This project has no scheduled target completion date."));
            }

            LocalDateTime nowPst = LocalDateTime.now(ZoneId.of("Asia/Manila"));
            LocalDate todayPst = nowPst.toLocalDate();

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

            Long ceoId = getCeoId();
            String safeRoadName = report.getCityRoadName() != null ? report.getCityRoadName() : "a road";
            String notifMsg = "CPDO Admin has flagged PRJ-" + String.format("%04d", report.getId()) + " (" + safeRoadName + "). Status: " + urgencyLabel + ". Please prioritize site completion or report progress.";

            if (ceoId != null) {
                notificationService.sendNotification(ceoId, notifTitle, notifMsg, notifType);

                Optional<User> ceoOpt = userRepository.findById(ceoId);
                if (ceoOpt.isPresent()) {
                    User ceo = ceoOpt.get();
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
                }
            }

            reportStatusLogService.log(
                    report,
                    "DEADLINE_REMINDER_ISSUED",
                    report.getStatus(),
                    report.getStatus(),
                    "CPDO Admin issued an expedite/deadline reminder to the City Engineer (" + urgencyLabel + ").",
                    callingUser,
                    "CPDO Admin",
                    "ADMIN",
                    null
            );

            activityLogService.log(
                    callingUser,
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
    // 12. CEO: SUBMIT REQUEST FOR TIME EXTENSION (CEO ONLY)
    // ==========================================
    @PostMapping("/{id}/request-extension")
    public ResponseEntity<?> requestExtension(
            @PathVariable Long id,
            @RequestBody Map<String, Object> payload,
            HttpServletRequest request) {
        try {
            Long explicitUserId = payload.get("userId") != null ? Long.valueOf(payload.get("userId").toString()) : null;
            User callingUser = resolveUser(explicitUserId);

            // 🛡️ CONTROL 6 RBAC: Strictly restricted to City Engineer (CEO)
            if (!isCityEngineer(callingUser)) {
                return ResponseEntity.status(403).body(Map.of("error", "Access Denied: Only the City Engineering Office (CEO) can file schedule extension requests."));
            }

            RoadReport report = repository.findById(id)
                    .orElseThrow(() -> new RuntimeException("Report not found"));

            String targetDateStr = (String) payload.get("extensionTargetDate");
            String reason = (String) payload.get("extensionReason");

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
                    callingUser,
                    "City Engineer",
                    "ENGINEER",
                    null
            );

            activityLogService.log(
                    callingUser,
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
    // 13. CPDO ADMIN: REVIEW TIME EXTENSION (APPROVE / REJECT) - CPDO ONLY
    // ==========================================
    @PutMapping("/{id}/review-extension")
    public ResponseEntity<?> reviewExtension(
            @PathVariable Long id,
            @RequestBody Map<String, Object> payload,
            HttpServletRequest request) {
        try {
            Long explicitUserId = payload.get("userId") != null ? Long.valueOf(payload.get("userId").toString()) : null;
            User callingUser = resolveUser(explicitUserId);

            // 🛡️ CONTROL 6 RBAC: Strictly restricted to CPDO Admin
            if (!isCpdoAdmin(callingUser)) {
                return ResponseEntity.status(403).body(Map.of("error", "Access Denied: Only CPDO Administrators are authorized to review and approve/reject extension requests."));
            }

            RoadReport report = repository.findById(id)
                    .orElseThrow(() -> new RuntimeException("Report not found"));

            String decision = (String) payload.get("decision");
            String adminRemarks = (String) payload.get("adminRemarks");

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

            Long ceoId = getCeoId();
            String safeRoadName = report.getCityRoadName() != null ? report.getCityRoadName() : "a road";

            if (ceoId != null) {
                String title = isApproved ? "✅ Time Extension Approved" : "❌ Time Extension Rejected";
                String msg = isApproved
                        ? "CPDO Admin approved your extension request for PRJ-" + String.format("%04d", report.getId()) + ". New target date: " + newTargetDate + "."
                        : "CPDO Admin rejected your extension request for PRJ-" + String.format("%04d", report.getId()) + "." + (adminRemarks != null && !adminRemarks.trim().isEmpty() ? " Remarks: " + adminRemarks : "");

                notificationService.sendNotification(ceoId, title, msg, isApproved ? "REPORT" : "WARNING");

                Optional<User> ceoOpt = userRepository.findById(ceoId);
                if (ceoOpt.isPresent()) {
                    User ceo = ceoOpt.get();
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
                }
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
                    callingUser,
                    "CPDO Admin",
                    "ADMIN",
                    null
            );

            activityLogService.log(
                    callingUser,
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