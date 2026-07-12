package com.roadwise.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**") // Apply this to ALL endpoints (Login, Roads, Reports)
                .allowedOrigins("http://localhost:63342", "http://127.0.0.1:63342") // Allow your IntelliJ frontend
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS") // Allow these commands
                .allowedHeaders("*")
                .allowCredentials(true);
    }
}