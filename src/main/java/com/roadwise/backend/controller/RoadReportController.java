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

@RestController
@RequestMapping("/api/reports")
@CrossOrigin(origins = "${frontend.url}")
public class RoadReportController {

    @Autowired
    private RoadReportRepository repository;

    @Autowired
    private com.roadwise.backend.repository.BarangayRepository barangayRepository;

    // We will save uploaded photos into a folder named "uploads" in your project folder
    private static final String UPLOAD_DIR = "uploads/";

    // Accepts 'multipart/form-data' (Files + Text) instead of JSON
    @PostMapping(consumes = {"multipart/form-data"})
    public RoadReport createReport(
            @ModelAttribute RoadReport report,
            // ⬇️ NEW: EXPLICITLY CATCH THE BARANGAY ID FROM THE FRONTEND ⬇️
            @RequestParam(value = "barangayId", required = false) Long barangayId,
            @RequestParam(value = "imageFile", required = false) MultipartFile imageFile) {

        try {
            // --- NEW: MANUALLY ATTACH THE BARANGAY ---
            // This searches the database for the ID (e.g., 64) and attaches "Minuyan Proper"
            if (barangayId != null) {
                com.roadwise.backend.model.Barangay foundBarangay = barangayRepository.findById(barangayId).orElse(null);
                report.setBarangay(foundBarangay);
            }

            // 1. Create the 'uploads' folder if it doesn't exist yet
            Path uploadPath = Paths.get(UPLOAD_DIR);
            if (!Files.exists(uploadPath)) {
                Files.createDirectories(uploadPath);
            }

            // 2. Handle the Image File
            if (imageFile != null && !imageFile.isEmpty()) {
                // Generate a unique ID for the image so two files named "pothole.jpg" don't overwrite each other!
                String originalFilename = imageFile.getOriginalFilename();
                String uniqueFilename = UUID.randomUUID().toString() + "_" + originalFilename;

                // Save the physical picture to your hard drive
                Path filePath = uploadPath.resolve(uniqueFilename);
                Files.copy(imageFile.getInputStream(), filePath);

                // Save JUST the unique name to PostgreSQL so we can find it later
                report.setDamageImage(uniqueFilename);
            } else {
                report.setDamageImage("no_image.jpg");
            }

            // 3. Save the text data to PostgreSQL
            report.setStatus("Pending Validation"); // Updated to match your frontend badge!
            return repository.save(report);

        } catch (IOException e) {
            e.printStackTrace();
            throw new RuntimeException("Failed to save image file to the server!");
        }
    }

    @GetMapping
    public List<RoadReport> getAllReports() {
        return repository.findAll();
    }
}