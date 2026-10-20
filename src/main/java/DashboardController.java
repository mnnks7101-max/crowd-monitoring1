
package com.crowdmonitoring.crowd_monitoring;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Controller
public class DashboardController {

    private final Map<String, Map<String, String>> complaints =
            new ConcurrentHashMap<>();

    @GetMapping("/")
    public String home() {
        return "index";
    }

    @PostMapping("/api/missing-person")
    @ResponseBody
    public Map<String, String> registerMissingPerson(
            @RequestParam("caseId") String caseId,
            @RequestParam("personName") String personName,
            @RequestParam("personPhoto") MultipartFile personPhoto,
            @RequestParam(value = "clothing", required = false) String clothing,
            @RequestParam("lastSeen") String lastSeen,
            @RequestParam("lastSeenTime") String lastSeenTime) {

        try {
            if (caseId.isBlank() || personName.isBlank()
                    || lastSeen.isBlank() || lastSeenTime.isBlank()
                    || personPhoto.isEmpty()) {
                return Map.of("success", "false",
                        "message", "Please complete all required fields.");
            }

            if (personPhoto.getContentType() == null
                    || !personPhoto.getContentType().startsWith("image/")) {
                return Map.of("success", "false",
                        "message", "Please upload a valid image.");
            }

            if (personPhoto.getSize() > 5 * 1024 * 1024) {
                return Map.of("success", "false",
                        "message", "Photo must be 5 MB or smaller.");
            }

            if (complaints.containsKey(caseId)) {
                return Map.of("success", "false",
                        "message", "That case ID is already registered.");
            }

            Path uploadDirectory = Paths.get("uploads", "missing-persons")
                    .toAbsolutePath().normalize();
            Files.createDirectories(uploadDirectory);

            String originalName = personPhoto.getOriginalFilename();
            String extension = ".jpg";

            if (originalName != null && originalName.lastIndexOf('.') >= 0) {
                extension = originalName.substring(originalName.lastIndexOf('.'));
                if (!extension.matches("\\.[A-Za-z0-9]{1,10}")) {
                    extension = ".jpg";
                }
            }

            String photoName = UUID.randomUUID() + extension;
            Path photoPath = uploadDirectory.resolve(photoName).normalize();

            if (!photoPath.startsWith(uploadDirectory)) {
                return Map.of("success", "false",
                        "message", "Invalid photo filename.");
            }

            Files.copy(personPhoto.getInputStream(), photoPath);

            Map<String, String> complaint = Map.of(
                    "caseId", caseId,
                    "personName", personName,
                    "clothing", clothing == null ? "" : clothing,
                    "lastSeen", lastSeen,
                    "lastSeenTime", lastSeenTime,
                    "photoName", photoName
            );

            complaints.put(caseId, complaint);

            return Map.of("success", "true",
                    "message", "Complaint registered successfully. Case ID: "
                            + caseId);

        } catch (IOException e) {
            return Map.of("success", "false",
                    "message", "Could not save the photo. Please try again.");
        }
    }
}
