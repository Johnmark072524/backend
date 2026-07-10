package com.roadwise.backend.controller;

import com.roadwise.backend.model.CityRoad;
import com.roadwise.backend.repository.CityRoadRepository; // <-- This is the correct import!
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/roads")
@CrossOrigin(origins = "*") // Allows your frontend to connect safely
public class CityRoadController {

    @Autowired
    private CityRoadRepository repository;

    // This is the magic endpoint that sends all roads to your dropdown
    @GetMapping
    public List<CityRoad> getAllRoads() {
        return repository.findAll();
    }
}