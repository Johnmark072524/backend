package com.roadwise.backend.service;

import ai.onnxruntime.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import jakarta.annotation.PostConstruct;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.util.Collections;

@Service
public class RoadInspectionService {

    private OrtEnvironment env;
    private OrtSession session;

    private final String[] SEVERITY_LEVELS = {"Low", "Medium", "High"};

    public static class InspectionResult {
        private final String severity;
        private final double confidence;

        public InspectionResult(String severity, double confidence) {
            this.severity = severity;
            this.confidence = confidence;
        }

        public String getSeverity() { return severity; }
        public double getConfidence() { return confidence; }
    }

    @PostConstruct
    public void init() {
        // 🛡️ CRITICAL: Catch Throwable instead of Exception to capture UnsatisfiedLinkError & OutOfMemoryError
        try {
            ClassPathResource resource = new ClassPathResource("rdd2022_d4_resnet38.onnx");
            if (!resource.exists()) {
                System.err.println(">>> [AI WARNING] Model file 'rdd2022_d4_resnet38.onnx' not found in classpath. AI triage disabled.");
                return;
            }

            env = OrtEnvironment.getEnvironment();
            try (InputStream modelStream = resource.getInputStream()) {
                byte[] modelBytes = modelStream.readAllBytes();
                session = env.createSession(modelBytes, new OrtSession.SessionOptions());
                System.out.println(">>> [AI] ResNet38 ONNX Model loaded successfully!");
            }
        } catch (Throwable t) {
            System.err.println(">>> [AI ERROR] Native ONNX initialization failed: " + t.getClass().getName() + " - " + t.getMessage());
            t.printStackTrace();
            // Allow Spring Boot to continue booting even if AI is unavailable on cloud
            this.session = null;
            this.env = null;
        }
    }

    public InspectionResult analyzeSeverity(MultipartFile file) throws Exception {
        // 🛡️ Graceful fallback if ONNX failed to initialize in cloud container
        if (session == null || env == null) {
            System.out.println(">>> [AI FALLBACK] ONNX model offline. Assigning default triage.");
            return new InspectionResult("Medium", 75.0);
        }

        BufferedImage originalImage = ImageIO.read(file.getInputStream());
        BufferedImage resizedImage = new BufferedImage(224, 224, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = resizedImage.createGraphics();
        g.drawImage(originalImage, 0, 0, 224, 224, null);
        g.dispose();

        FloatBuffer tensorBuffer = FloatBuffer.allocate(1 * 3 * 224 * 224);
        for (int c = 0; c < 3; c++) {
            for (int y = 0; y < 224; y++) {
                for (int x = 0; x < 224; x++) {
                    int rgb = resizedImage.getRGB(x, y);
                    float value;
                    if (c == 0) value = ((rgb >> 16) & 0xFF) / 255.0f;
                    else if (c == 1) value = ((rgb >> 8) & 0xFF) / 255.0f;
                    else value = (rgb & 0xFF) / 255.0f;
                    tensorBuffer.put(value);
                }
            }
        }
        tensorBuffer.rewind();

        long[] shape = {1, 3, 224, 224};
        try (OnnxTensor inputTensor = OnnxTensor.createTensor(env, tensorBuffer, shape)) {
            String inputName = session.getInputNames().iterator().next();
            try (OrtSession.Result results = session.run(Collections.singletonMap(inputName, inputTensor))) {
                float[][] output = (float[][]) results.get(0).getValue();
                float[] logits = output[0];

                // Softmax calculation for true confidence percentage
                float maxLogit = Math.max(logits[0], Math.max(logits[1], logits[2]));
                float sumExp = 0.0f;
                float[] exp = new float[3];
                for (int i = 0; i < 3; i++) {
                    exp[i] = (float) Math.exp(logits[i] - maxLogit);
                    sumExp += exp[i];
                }

                int maxIndex = 0;
                float highestProb = exp[0] / sumExp;
                for (int i = 1; i < 3; i++) {
                    float prob = exp[i] / sumExp;
                    if (prob > highestProb) {
                        highestProb = prob;
                        maxIndex = i;
                    }
                }

                double confidencePercentage = Math.round(highestProb * 1000.0) / 10.0;
                return new InspectionResult(SEVERITY_LEVELS[maxIndex], confidencePercentage);
            }
        }
    }
}