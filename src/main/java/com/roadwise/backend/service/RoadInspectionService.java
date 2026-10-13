package com.roadwise.backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

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

        public InspectionResult(String severity, double confidence, String status, String detectedDamageType, String message) {
            this.severity = severity;
            this.confidence = confidence;
            this.status = status;
            this.detectedDamageType = detectedDamageType;
            this.message = message;
        }

        public String getSeverity() { return severity; }
        public double getConfidence() { return confidence; }
        public String getStatus() { return status; }
        public String getDetectedDamageType() { return detectedDamageType; }
        public String getMessage() { return message; }
    }

    public InspectionResult analyzeSeverity(MultipartFile file) throws Exception {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Image file is missing or empty.");
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        // Wrap file bytes in ByteArrayResource and preserve original filename
        ByteArrayResource fileResource = new ByteArrayResource(file.getBytes()) {
            @Override
            public String getFilename() {
                return file.getOriginalFilename() != null ? file.getOriginalFilename() : "damage_image.jpg";
            }
        };

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", fileResource);

        HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

        // POST to FastAPI /predict endpoint
        ResponseEntity<Map> response = restTemplate.postForEntity(
                aiServiceUrl + "/predict",
                requestEntity,
                Map.class
        );

        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
            Map<String, Object> respBody = response.getBody();

            // Extract severity (defaults to Unassessed if absent)
            String severity = respBody.getOrDefault("severity", "Unassessed").toString();

            // Safely parse confidence as numeric double
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

            System.out.println(">>> [AI SUCCESS] Predicted: " + severity + " (" + confidence + "%) | Status: " + status);

            return new InspectionResult(severity, confidence, status, detectedDamageType, message);
        }

        throw new RuntimeException("AI inference service returned status: " + response.getStatusCode());
    }
}