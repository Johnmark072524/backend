package com.roadwise.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**") // Apply this to ALL endpoints
                .allowedOrigins(
                        "http://localhost:63342",
                        "http://127.0.0.1:63342",
                        "https://frontend-capstone-fawn.vercel.app/login.html", // 🚀 1. Put your actual Vercel URL here
                        "https://vitamins-april-unify.ngrok-free.dev"       // 🚀 2. Put your active Ngrok URL here (Optional, but safe)
                )
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }
}