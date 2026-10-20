package com.crowdmonitoring.crowd_monitoring;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;

@Service
public class DatasetEvaluator {

    private final AiDetector aiDetector;

    public DatasetEvaluator(AiDetector aiDetector) {
        this.aiDetector = aiDetector;
    }

    public List<String> getTestImages() throws Exception {
        Path imageFolder = Path.of(
            "C:/Users/Srinithi/Downloads/archive/ShanghaiTech/part_B/test_data/images"
        );

        List<String> images = new ArrayList<>();

        try (Stream<Path> files = Files.list(imageFolder)) {
            files.filter(Files::isRegularFile)
                 .filter(path -> path.toString().toLowerCase().endsWith(".jpg")
                              || path.toString().toLowerCase().endsWith(".jpeg")
                              || path.toString().toLowerCase().endsWith(".png"))
                 .sorted()
                 .forEach(path -> images.add(path.toString()));
        }

        return images;
    }
    
public java.util.Map<String, Object> analyzeDataset(int limit) throws Exception {
    List<String> images = getTestImages();

    int totalToAnalyze = Math.min(Math.max(limit, 1), images.size());
    int successful = 0;
    int failed = 0;
    long totalPeople = 0;

    List<java.util.Map<String, Object>> results = new ArrayList<>();

    for (int i = 0; i < totalToAnalyze; i++) {
        Path imagePath = Path.of(images.get(i));

        try {
            byte[] imageBytes = Files.readAllBytes(imagePath);
            int people = aiDetector.detectPeople(imageBytes);

            totalPeople += people;
            successful++;

            results.add(java.util.Map.of(
    "image", imagePath.getFileName().toString(),
    "peopleDetected", people,
    "crowdLevel", people < 20 ? "LOW"
                : people < 50 ? "MEDIUM" : "HIGH"
));
        } catch (Exception e) {
            failed++;
        }
    }

    double averagePeople = successful == 0
        ? 0.0
        : (double) totalPeople / successful;

    return java.util.Map.of(
        "imagesRequested", totalToAnalyze,
        "imagesAnalyzed", successful,
        "imagesFailed", failed,
        "totalPeopleDetected", totalPeople,
        "averagePeoplePerImage", averagePeople,
        "results", results
    );
}

}