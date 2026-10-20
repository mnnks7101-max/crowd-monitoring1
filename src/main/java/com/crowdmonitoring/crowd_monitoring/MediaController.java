package com.crowdmonitoring.crowd_monitoring;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.List;

@RestController
@RequestMapping("/api")
public class MediaController {

    private final AiDetector aiDetector;
    private final DatasetEvaluator datasetEvaluator;
    private final VideoAnalyzer videoAnalyzer;
    private final MissingPersonStore missingPersonStore;
    private final MissingPersonVideoSearch missingPersonVideoSearch;

    public MediaController(
            DatasetEvaluator datasetEvaluator,
            AiDetector aiDetector,
            VideoAnalyzer videoAnalyzer,
            MissingPersonStore missingPersonStore,
            MissingPersonVideoSearch missingPersonVideoSearch) {
        this.datasetEvaluator = datasetEvaluator;
        this.aiDetector = aiDetector;
        this.videoAnalyzer = videoAnalyzer;
        this.missingPersonStore = missingPersonStore;
        this.missingPersonVideoSearch = missingPersonVideoSearch;
    }

    @PostMapping(
            value = "/analyze",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ResponseEntity<Map<String, Object>> analyze(
            @RequestParam("file") MultipartFile file,
            @RequestParam("area") double area,
            @RequestParam("type") String type,
            @RequestParam(value = "jobId", required = false)
                    String jobId) {

        Map<String, Object> result = new HashMap<>();

        if (file == null || file.isEmpty()) {
            result.put("error", "Please select an image or video.");
            return ResponseEntity.badRequest().body(result);
        }

        if (!Double.isFinite(area) || area <= 0) {
            result.put("error", "Area must be greater than zero.");
            return ResponseEntity.badRequest().body(result);
        }

        if (!"image".equals(type) && !"video".equals(type)) {
            result.put("error", "Choose image or video.");
            return ResponseEntity.badRequest().body(result);
        }

        String filename = file.getOriginalFilename();

        try {
            if ("image".equals(type)) {
                int people = aiDetector.detectPeople(file.getBytes());
                double density = people / area;
                String status = getDensityStatus(density);

                result.put("message", "Image analyzed successfully.");
                result.put("filename", filename);
                result.put("type", "image");
                result.put("area", area);
                result.put("analysisStatus", "COMPLETED");
                result.put("peopleCount", people);
                result.put(
                        "density",
                        Math.round(density * 100.0) / 100.0
                );
                result.put("status", status);
                result.put("densityLevel", status);

                return ResponseEntity.ok(result);
            }

            // The browser supplies this ID so it can request progress.
            if (jobId == null || !jobId.matches("[a-zA-Z0-9_-]{1,100}")) {
                result.put("error", "A valid video analysis job ID is required.");
                return ResponseEntity.badRequest().body(result);
            }

            String extension = ".mp4";

            if (filename != null && filename.contains(".")) {
                String suppliedExtension =
                        filename.substring(filename.lastIndexOf("."));

                if (suppliedExtension.matches(
                        "\\.[a-zA-Z0-9]{1,10}")) {
                    extension = suppliedExtension;
                }
            }

            Path videoPath = Files.createTempFile(
                    "crowd-video-", extension
            );

            try {
                file.transferTo(videoPath.toFile());

                Map<String, Object> videoResult =
        videoAnalyzer.analyzeVideo(videoPath, area, jobId);

// Create the folder for annotated videos.
Path annotatedDirectory = Path.of(
        "data", "annotated-videos"
).toAbsolutePath().normalize();

Files.createDirectories(annotatedDirectory);

// Generate a unique filename for the processed video.
String annotatedFilename =
        UUID.randomUUID() + ".mp4";

Path annotatedPath =
        annotatedDirectory.resolve(annotatedFilename);

// Draw green boxes and save the processed video.
videoAnalyzer.createAnnotatedVideo(
        videoPath, annotatedPath
);

// Return the annotated video's filename to the website.
videoResult.put("annotatedVideo", annotatedFilename);

                result.putAll(videoResult);
                result.put("filename", filename);
                result.put("type", "video");
                result.put("area", area);
                result.put("jobId", jobId);

                return ResponseEntity.ok(result);

            } finally {
                Files.deleteIfExists(videoPath);
            }

        } catch (Exception e) {
            result.put("error", "Media analysis failed.");
            result.put(
                    "details",
                    e.getMessage() == null
                            ? "Unknown error"
                            : e.getMessage()
            );

            return ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(result);
        }
    }

    // The webpage polls this endpoint while the video is analyzed.
    @GetMapping("/analyze/progress/{jobId}")
    public ResponseEntity<Map<String, Object>> getAnalysisProgress(
            @PathVariable String jobId) {

        if (jobId == null
                || !jobId.matches("[a-zA-Z0-9_-]{1,100}")) {
            return ResponseEntity.badRequest().body(
                    Map.of("error", "Invalid analysis job ID.")
            );
        }

        Map<String, Object> progress =
                videoAnalyzer.getProgress(jobId);

        if (progress.containsKey("error")) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(progress);
        }

        return ResponseEntity.ok(progress);
    }

    @PostMapping(
            value = "/missing-person",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ResponseEntity<Map<String, Object>> registerMissingPerson(
            @RequestParam("caseId") String caseId,
            @RequestParam("personName") String personName,
            @RequestParam("personPhoto") MultipartFile personPhoto,
            @RequestParam(value = "clothing", required = false)
                    String clothing,
            @RequestParam("lastSeen") String lastSeen,
            @RequestParam("lastSeenTime") String lastSeenTime) {

        if (caseId == null || caseId.isBlank()
                || personName == null || personName.isBlank()
                || lastSeen == null || lastSeen.isBlank()
                || lastSeenTime == null || lastSeenTime.isBlank()
                || personPhoto == null || personPhoto.isEmpty()) {

            return ResponseEntity.badRequest().body(
                    Map.of(
                            "success", false,
                            "message",
                            "Please complete all required fields."
                    )
            );
        }

        if (personPhoto.getSize() > 5L * 1024 * 1024) {
            return ResponseEntity.badRequest().body(
                    Map.of(
                            "success", false,
                            "message",
                            "Reference photo must be 5 MB or smaller."
                    )
            );
        }

        String contentType = personPhoto.getContentType();
        String extension;

        if ("image/jpeg".equalsIgnoreCase(contentType)) {
            extension = ".jpg";
        } else if ("image/png".equalsIgnoreCase(contentType)) {
            extension = ".png";
        } else if ("image/webp".equalsIgnoreCase(contentType)) {
            extension = ".webp";
        } else {
            return ResponseEntity.badRequest().body(
                    Map.of(
                            "success", false,
                            "message",
                            "Upload a JPG, PNG, or WebP photo."
                    )
            );
        }

        Path photoPath = null;

        try {
            Path photoDirectory = Path.of(
                    "data", "missing-person-photos"
            ).toAbsolutePath().normalize();

            Files.createDirectories(photoDirectory);

            String safeCaseId =
                    caseId.replaceAll("[^a-zA-Z0-9_-]", "_");

            String storedFilename =
                    safeCaseId + "_" + UUID.randomUUID() + extension;

            photoPath = photoDirectory.resolve(storedFilename)
                    .normalize();

            if (!photoPath.startsWith(photoDirectory)) {
                return ResponseEntity.badRequest().body(
                        Map.of(
                                "success", false,
                                "message", "Invalid case ID."
                        )
                );
            }

            try (InputStream input = personPhoto.getInputStream()) {
                Files.copy(
                        input,
                        photoPath,
                        StandardCopyOption.REPLACE_EXISTING
                );
            }

            try {
                missingPersonStore.save(
                        caseId,
                        personName,
                        clothing,
                        lastSeen,
                        lastSeenTime,
                        storedFilename
                );
            } catch (Exception saveException) {
                Files.deleteIfExists(photoPath);
                throw saveException;
            }

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("caseId", caseId);
            response.put("personName", personName);
            response.put("photoStored", true);

            try {
                Path videosDirectory = Path.of("videos")
                        .toAbsolutePath()
                        .normalize();

                List<String> scanResults =
                        missingPersonVideoSearch.searchVideos(
                                videosDirectory, photoPath
                        );

                response.put("searchStatus", "COMPLETED");
                response.put("videoScanResults", scanResults);
                response.put(
                        "message",
                        "Complaint saved and video colour "
                        + "comparison completed. Results are "
                        + "unverified candidates requiring "
                        + "human review."
                );

            } catch (Exception searchException) {
                response.put("searchStatus", "FAILED");
                response.put(
                        "message",
                        "Complaint and photo saved, but video "
                        + "comparison could not be completed."
                );
                response.put(
                        "searchError",
                        searchException.getMessage() == null
                                ? "Unknown search error"
                                : searchException.getMessage()
                );
            }

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(
                    Map.of(
                            "success", false,
                            "message",
                            "Could not save the complaint and photo."
                    )
            );
        }
    }

    private String getDensityStatus(double density) {
        if (density < 0.5) {
            return "LOW";
        } else if (density < 2.0) {
            return "MEDIUM";
        } else {
            return "HIGH";
        }
    }

@PostMapping(
        value = "/analyze/boxes",
        consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
        produces = MediaType.IMAGE_JPEG_VALUE
)
public ResponseEntity<byte[]> analyzeImageWithBoxes(
        @RequestParam("file") MultipartFile file) {

    try {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        byte[] annotatedImage =
                aiDetector.detectPeopleWithBoxes(file.getBytes());

        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .body(annotatedImage);

    } catch (Exception e) {
        return ResponseEntity.internalServerError().build();
    }
}

@GetMapping("/annotated-video")
public ResponseEntity<org.springframework.core.io.FileSystemResource>
        getAnnotatedVideo(
                @RequestParam("file") String filename) {

    try {
        Path videoDirectory = Path.of("data", "annotated-videos")
                .toAbsolutePath().normalize();

        Path videoPath = videoDirectory.resolve(filename)
                .normalize();

        if (!videoPath.startsWith(videoDirectory)
                || !Files.isRegularFile(videoPath)) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok()
                .contentType(MediaType.valueOf("video/mp4"))
                .body(new org.springframework.core.io.FileSystemResource(
                        videoPath.toFile()));

    } catch (Exception e) {
        return ResponseEntity.internalServerError().build();
    }
}

@GetMapping("/dataset/images")
public ResponseEntity<Map<String, Object>> getDatasetImages() {
    try {
        List<String> images = datasetEvaluator.getTestImages();

        return ResponseEntity.ok(
                Map.of(
                        "totalImages", images.size(),
                        "images", images
                )
        );
    } catch (Exception e) {
        return ResponseEntity.internalServerError().body(
                Map.of("error", "Could not load dataset images.")
        );
    }
}

@GetMapping("/dataset/analyze")
public ResponseEntity<Map<String, Object>> analyzeDataset(
        @RequestParam(defaultValue = "10") int limit) {
    try {
        return ResponseEntity.ok(
            datasetEvaluator.analyzeDataset(limit)
        );
    } catch (Exception e) {
        return ResponseEntity.internalServerError().body(
            Map.of("error", "Dataset analysis failed: " + e.getMessage())
        );
    }
}

}
