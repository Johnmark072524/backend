package com.roadwise.backend.controller;

import com.roadwise.backend.model.Barangay;
import com.roadwise.backend.repository.BarangayRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/barangays")
@CrossOrigin(origins = "${frontend.url}")
public class BarangayController {

    @Autowired
    private BarangayRepository barangayRepository;

    // ==========================================
    // FETCH ALL BARANGAYS FOR DROPDOWNS
    // ==========================================
    @GetMapping
    public ResponseEntity<List<Barangay>> getAllBarangays() {
        List<Barangay> barangays = barangayRepository.findAll();
        return ResponseEntity.ok(barangays);
    }
}