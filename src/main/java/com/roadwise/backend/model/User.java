package com.roadwise.backend.model;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "users")
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

    // 🚀 NAMES
    private String firstName;
    private String middleName;
    private String lastName;

    // 🚀 PROFILE DETAILS
    private String email;
    private String phoneNumber;
    private LocalDate birthday;
    private String gender;

    // 🚀 PROFILE PICTURE & STATUS
    private String profilePicture;
    private String status;

    // 🔒 RATE LIMITING & SECURITY LOCKOUT
    @Column(name = "failed_login_attempts")
    private Integer failedLoginAttempts = 0; // Changed to Integer to safely handle DB nulls for legacy accounts

    @Column(name = "lockout_until")
    private LocalDateTime lockoutUntil;

    // Nullable for CPDO and CEO accounts
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

    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }

    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }

    public String getMiddleName() { return middleName; }
    public void setMiddleName(String middleName) { this.middleName = middleName; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }

    public LocalDate getBirthday() { return birthday; }
    public void setBirthday(LocalDate birthday) { this.birthday = birthday; }

    public String getGender() { return gender; }
    public void setGender(String gender) { this.gender = gender; }

    public String getProfilePicture() { return profilePicture; }
    public void setProfilePicture(String profilePicture) { this.profilePicture = profilePicture; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    // Rate Limiting Getters & Setters
    public int getFailedLoginAttempts() {
        // Safe Getter: If DB has null (old user), treat it as 0 to prevent 500 crashes
        return failedLoginAttempts == null ? 0 : failedLoginAttempts;
    }

    public void setFailedLoginAttempts(Integer failedLoginAttempts) {
        this.failedLoginAttempts = failedLoginAttempts;
    }

    public LocalDateTime getLockoutUntil() { return lockoutUntil; }
    public void setLockoutUntil(LocalDateTime lockoutUntil) { this.lockoutUntil = lockoutUntil; }
}