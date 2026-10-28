package com.roadwise.backend.config;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    // 🛡️ CONTROL 23: Catch Malformed JSON Syntax (Broken brackets, unclosed quotes)
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleMalformedJson(HttpMessageNotReadableException ex) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("timestamp", LocalDateTime.now());
        response.put("status", HttpStatus.BAD_REQUEST.value());
        response.put("error", "MALFORMED_REQUEST_PAYLOAD");
        response.put("message", "The request body syntax is malformed or could not be parsed.");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    // 🛡️ CONTROL 23: Catch Type Mismatch (e.g., text passed into numeric path ID like /api/reports/abc)
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("timestamp", LocalDateTime.now());
        response.put("status", HttpStatus.BAD_REQUEST.value());
        response.put("error", "INVALID_PARAMETER_TYPE");
        response.put("message", "Parameter '" + ex.getName() + "' contains an invalid data type format.");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    // 🛡️ CONTROL 23: Sanitized Fallback for Uncaught Runtime Errors (Suppresses SQL & internal traces)
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGenericException(Exception ex) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("timestamp", LocalDateTime.now());
        response.put("status", HttpStatus.INTERNAL_SERVER_ERROR.value());
        response.put("error", "INTERNAL_SERVER_ERROR");
        response.put("message", "An unexpected system error occurred. Details have been logged securely to server diagnostics.");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }
}