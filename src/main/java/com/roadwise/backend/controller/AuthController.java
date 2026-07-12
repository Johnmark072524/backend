package com.roadwise.backend.controller;

import com.roadwise.backend.model.User;
import com.roadwise.backend.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "${frontend.url}")// This allows your frontend to talk to this backend
public class AuthController {

    @Autowired
    private UserRepository userRepository;

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> credentials) {
        String username = credentials.get("username");
        String password = credentials.get("password");

        // 1. Use your Search Tool to find the user
        Optional<User> userOpt = userRepository.findByUsername(username);

        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid username or password.");
        }

        User user = userOpt.get();

        // 2. Check if the password matches
        if (!user.getPassword().equals(password)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid username or password.");
        }

        // 3. If they pass, pack up their "VIP Ticket" to send to the frontend
        Map<String, Object> responseData = new HashMap<>();
        responseData.put("userId", user.getId());
        responseData.put("username", user.getUsername());
        responseData.put("role", user.getRole());

        // If they are a Barangay Official, send their specific location!
        if (user.getBarangay() != null) {
            responseData.put("barangayId", user.getBarangay().getId());
            responseData.put("barangayName", user.getBarangay().getBarangayName());
        } else {
            responseData.put("barangayId", null);
            responseData.put("barangayName", "City Hall Central");
        }

        return ResponseEntity.ok(responseData);
    }
}