package com.roadwise.backend.model;

import jakarta.persistence.*;

@Entity
@Table(name = "users") // Using 'users' plural is standard practice
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(nullable = false)
    private String password;

    @Column(nullable = false)
    private String role; // "ADMIN", "ENGINEER", or "BARANGAY"

    // nullable = true allows CPDO and CEO accounts to leave this blank!
    @ManyToOne
    @JoinColumn(name = "barangay_id", nullable = true)
    private Barangay barangay;

    public User() {
    }

    // --- GETTERS AND SETTERS ---
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public Barangay getBarangay() { return barangay; }
    public void setBarangay(Barangay barangay) { this.barangay = barangay; }
}