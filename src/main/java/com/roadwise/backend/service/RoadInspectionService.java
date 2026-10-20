package com.roadwise.backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

@Service
public class RoadInspectionService {

    @Value("${ai.service.url:https://roadwise-ai-service.onrender.com}")
    private String aiServiceUrl;

    private final RestTemplate restTemplate = new RestTemplate();

    public static class InspectionResult {
        private final String severity;
        private final double confidence;
        private final String status;
        private final String detectedDamageType;
        private final String message;
        private final Map<String, Object> angleBreakdown;

        public InspectionResult(String severity, double confidence, String status, String detectedDamageType, String message) {
            this(severity, confidence, status, detectedDamageType, message, null);
        }

        public InspectionResult(String severity, double confidence, String status, String detectedDamageType, String message, Map<String, Object> angleBreakdown) {
            this.severity = severity;
            this.confidence = confidence;
            this.status = status;
            this.detectedDamageType = detectedDamageType;
            this.message = message;
            this.angleBreakdown = angleBreakdown;
        }

        public String getSeverity() { return severity; }
        public double getConfidence() { return confidence; }
        public String getStatus() { return status; }
        public String getDetectedDamageType() { return detectedDamageType; }
        public String getMessage() { return message; }
        public Map<String, Object> getAngleBreakdown() { return angleBreakdown; }
    }

    // =========================================================================
    // 🚀 NEW: MULTI-ANGLE BATCH PREDICTION (/predict/batch)
    // =========================================================================
    public InspectionResult analyzeBatchSeverity(
            MultipartFile imageWhole,
            MultipartFile imageTop,
            MultipartFile imageLeft,
            MultipartFile imageRight,
            MultipartFile imageClose
    ) throws Exception {

        // Validate mandatory perspectives
        if (imageWhole == null || imageWhole.isEmpty()) {
            throw new IllegalArgumentException("Mandatory 'imageWhole' (road context) is missing or empty.");
        }
        if (imageTop == null || imageTop.isEmpty()) {
            throw new IllegalArgumentException("Mandatory 'imageTop' (bird's-eye defect) is missing or empty.");
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();

        // 1. Mandatory Perspectives
        body.add("image_whole", toFileResource(imageWhole, "whole_view.jpg"));
        body.add("image_top", toFileResource(imageTop, "top_view.jpg"));

        // 2. Optional Perspectives (Attach only if uploaded)
        if (imageLeft != null && !imageLeft.isEmpty()) {
            body.add("image_left", toFileResource(imageLeft, "left_view.jpg"));
        }
        if (imageRight != null && !imageRight.isEmpty()) {
            body.add("image_right", toFileResource(imageRight, "right_view.jpg"));
        }
        if (imageClose != null && !imageClose.isEmpty()) {
            body.add("image_close", toFileResource(imageClose, "close_view.jpg"));
        }

        HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

        // POST to FastAPI /predict/batch endpoint
        ResponseEntity<Map> response = restTemplate.postForEntity(
                aiServiceUrl + "/predict/batch",
                requestEntity,
                Map.class
        );

        return parseInspectionResponse(response, "BATCH");
    }

    // =========================================================================
    // 🔄 LEGACY SINGLE-IMAGE PREDICTION (/predict)
    // =========================================================================
    public InspectionResult analyzeSeverity(MultipartFile file) throws Exception {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Image file is missing or empty.");
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", toFileResource(file, "damage_image.jpg"));

        HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

        // POST to FastAPI /predict endpoint
        ResponseEntity<Map> response = restTemplate.postForEntity(
                aiServiceUrl + "/predict",
                requestEntity,
                Map.class
        );

        return parseInspectionResponse(response, "SINGLE");
    }

    // =========================================================================
    // HELPER METHODS
    // =========================================================================
    private ByteArrayResource toFileResource(MultipartFile file, String fallbackName) throws IOException {
        return new ByteArrayResource(file.getBytes()) {
            @Override
            public String getFilename() {
                return (file.getOriginalFilename() != null && !file.getOriginalFilename().trim().isEmpty())
                        ? file.getOriginalFilename()
                        : fallbackName;
            }
        };
    }

    @SuppressWarnings("unchecked")
    private InspectionResult parseInspectionResponse(ResponseEntity<Map> response, String callType) {
        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
            Map<String, Object> respBody = response.getBody();

            String severity = respBody.getOrDefault("severity", "Unassessed").toString();

            double confidence = 0.0;
            Object confObj = respBody.get("confidence");
            if (confObj instanceof Number) {
                confidence = ((Number) confObj).doubleValue();
            } else if (confObj != null) {
                try {
                    confidence = Double.parseDouble(confObj.toString());
                } catch (NumberFormatException ignored) {}
            }

            String status = respBody.getOrDefault("status", "UNKNOWN").toString();
            String detectedDamageType = respBody.get("detected_damage_type") != null
                    ? respBody.get("detected_damage_type").toString()
                    : null;
            String message = respBody.getOrDefault("message", "").toString();
            Map<String, Object> angleBreakdown = respBody.get("angle_breakdown") instanceof Map
                    ? (Map<String, Object>) respBody.get("angle_breakdown")
                    : null;

            System.out.println(">>> [AI " + callType + " SUCCESS] Severity: " + severity + " (" + confidence + "%) | Status: " + status);

            return new InspectionResult(severity, confidence, status, detectedDamageType, message, angleBreakdown);
        }

        throw new RuntimeException("AI inference service returned status: " + response.getStatusCode());
    }
}