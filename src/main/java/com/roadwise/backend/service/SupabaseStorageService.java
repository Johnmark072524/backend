package com.roadwise.backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

@Service
public class SupabaseStorageService {

    @Value("${supabase.url}")
    private String supabaseUrl;

    @Value("${supabase.service-key}")
    private String serviceKey;

    @Value("${supabase.bucket-name}")
    private String bucketName;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    public String uploadImage(MultipartFile file) throws Exception {
        String originalName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "image.jpg";
        String cleanFileName = UUID.randomUUID() + "_" + originalName.replaceAll("\\s+", "_");
        String uploadEndpoint = String.format("%s/storage/v1/object/%s/%s", supabaseUrl, bucketName, cleanFileName);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(uploadEndpoint))
                .header("Authorization", "Bearer " + serviceKey)
                .header("Content-Type", file.getContentType() != null ? file.getContentType() : "image/jpeg")
                .header("x-upsert", "true")
                .POST(HttpRequest.BodyPublishers.ofByteArray(file.getBytes()))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200 && response.statusCode() != 201) {
            throw new RuntimeException("Supabase upload failed [" + response.statusCode() + "]: " + response.body());
        }

        // Returns the permanent, cloud-hosted public CDN URL
        return String.format("%s/storage/v1/object/public/%s/%s", supabaseUrl, bucketName, cleanFileName);
    }
}