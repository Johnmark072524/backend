package com.roadwise.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Expose the "uploads" folder to the web
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations("file:uploads/");
    }

    // 🚀 THE ULTIMATE CORS FILTER: Applies to BOTH APIs and Static Folders!
    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowCredentials(true);
        config.setAllowedOrigins(Arrays.asList(
                "http://localhost:63342",
                "http://127.0.0.1:63342",
                "https://frontend-capstone-fawn.vercel.app",
                "https://frontend-capstone-7zkx2srtu-team-ratbu.vercel.app", // 🚀 EXACT VERCEL URL ADDED HERE
                "https://vitamins-april-unify.ngrok-free.dev"
        ));
        config.setAllowedHeaders(Arrays.asList("*"));
        config.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        // The "/**" ensures the uploads folder gets the CORS headers too!
        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);
    }
}