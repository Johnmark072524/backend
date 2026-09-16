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

        public InspectionResult(String severity, double confidence) {
            this.severity = severity;
            this.confidence = confidence;
        }

        public String getSeverity() { return severity; }
        public double getConfidence() { return confidence; }
    }

    public InspectionResult analyzeSeverity(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return new InspectionResult("Low", 100.0);
        }

        try {
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

            ResponseEntity<Map> response = restTemplate.postForEntity(
                    aiServiceUrl + "/predict",
                    requestEntity,
                    Map.class
            );

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Map<String, Object> respBody = response.getBody();
                String severity = respBody.getOrDefault("severity", "Medium").toString();
                double confidence = Double.parseDouble(respBody.getOrDefault("confidence", 75.0).toString());

                System.out.println(">>> [AI SUCCESS] Predicted: " + severity + " (" + confidence + "%)");
                return new InspectionResult(severity, confidence);
            } else {
                System.err.println(">>> [AI HTTP ERROR] Microservice returned status: " + response.getStatusCode());
            }
        } catch (Exception e) {
            System.err.println(">>> [AI CLIENT ERROR] Microservice communication failed: " + e.getMessage());
        }

        // Safe fallback triage if the microservice is temporarily waking up from cold sleep
        return new InspectionResult("Medium", 75.0);
    }
}