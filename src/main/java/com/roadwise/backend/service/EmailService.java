package com.roadwise.backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Service
public class EmailService {

    @Value("${brevo.api.key:}")
    private String apiKey;

    @Value("${brevo.sender.email:csjdmroadwise@gmail.com}")
    private String senderEmail;

    @Value("${brevo.sender.name:RoadWise CSJDM}")
    private String senderName;

    private final RestTemplate restTemplate = new RestTemplate();
    private static final String BREVO_API_URL = "https://api.brevo.com/v3/smtp/email";

    /**
     * Dispatches transactional emails via Brevo HTTPS REST API (Port 443).
     * Bypasses cloud SMTP port blocking.
     */
    public void sendEmail(String toEmail, String subject, String body) {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            System.err.println("⚠️ [BREVO] API key is missing. Email dispatch skipped.");
            return;
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));
            headers.set("api-key", apiKey.trim());

            // Brevo v3 Transactional Email Payload
            Map<String, Object> payload = Map.of(
                    "sender", Map.of("name", senderName, "email", senderEmail),
                    "to", List.of(Map.of("email", toEmail)),
                    "subject", subject,
                    "textContent", body
            );

            HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(payload, headers);

            ResponseEntity<String> response = restTemplate.postForEntity(BREVO_API_URL, requestEntity, String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                System.out.println("✅ [BREVO] Email successfully dispatched to: " + toEmail);
            }
        } catch (HttpStatusCodeException ex) {
            System.err.println("❌ [BREVO API ERROR " + ex.getStatusCode() + "]: " + ex.getResponseBodyAsString());
        } catch (Exception e) {
            System.err.println("❌ [BREVO] Connection failed: " + e.getMessage());
        }
    }
}