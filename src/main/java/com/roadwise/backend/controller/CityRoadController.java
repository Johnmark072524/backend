package com.roadwise.backend.controller;

import com.roadwise.backend.model.CityRoad; // <-- Fixes 'Cannot resolve symbol'
import com.roadwise.backend.repository.CityRoadRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/roads")
@CrossOrigin(origins = "${frontend.url}")
public class CityRoadController {

    @Autowired
    private CityRoadRepository repository;

    @GetMapping
    public List<CityRoad> getAllRoads() {
        return repository.findAll();
    }

    @GetMapping("/barangay/{barangayId}")
    public ResponseEntity<List<CityRoad>> getRoadsByBarangay(@PathVariable Long barangayId) {

        // Because we saved the Repository file first, this line will no longer have an error!
        List<CityRoad> roads = repository.findByBarangayId(barangayId);

        if (roads.isEmpty()) {
            return ResponseEntity.ok(java.util.Collections.emptyList());
        }

        return ResponseEntity.ok(roads);
    }
}