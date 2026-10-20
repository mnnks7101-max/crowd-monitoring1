package com.crowdmonitoring.crowd_monitoring;

import org.springframework.stereotype.Service;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@Service
public class MissingPersonVideoSearch {

    private final AiDetector aiDetector;

    private static final double COLOR_THRESHOLD = 0.20;
    private static final int MAX_RESULTS = 5;

    public MissingPersonVideoSearch(AiDetector aiDetector) {
        this.aiDetector = aiDetector;
    }

    // Preserves the original method for compatibility.
    public List<String> searchVideos(Path videosDirectory)
            throws Exception {
        return scanVideos(videosDirectory, null);
    }

    // Compares detected people with the reference photo.
    public List<String> searchVideos(
            Path videosDirectory,
            Path referencePhoto) throws Exception {

        if (referencePhoto == null
                || !Files.isRegularFile(referencePhoto)) {
            throw new IllegalArgumentException(
                    "Reference photo was not found."
            );
        }

        BufferedImage reference =
                ImageIO.read(referencePhoto.toFile());

        if (reference == null) {
            throw new IllegalArgumentException(
                    "Reference photo could not be read."
            );
        }

        return scanVideos(
                videosDirectory,
                getColorFeatures(reference)
        );
    }

    private List<String> scanVideos(
            Path videosDirectory,
            double[] referenceFeatures) throws Exception {

        List<String> results = new ArrayList<>();

        if (!Files.isDirectory(videosDirectory)) {
            results.add(
                    "Videos folder not found: " + videosDirectory
            );
            return results;
        }

        List<Path> videos;

        try (Stream<Path> files = Files.list(videosDirectory)) {
            videos = files
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName()
                                .toString().toLowerCase();

                        return name.endsWith(".mp4")
                                || name.endsWith(".avi")
                                || name.endsWith(".mov")
                                || name.endsWith(".mkv");
                    })
                    .sorted(Comparator.comparing(
                            path -> path.getFileName().toString()
                    ))
                    .toList();
        }

        if (videos.isEmpty()) {
            results.add("No supported videos found.");
            return results;
        }

        List<Candidate> candidates = new ArrayList<>();

        for (Path video : videos) {
            try {
                if (referenceFeatures == null) {
                    results.addAll(scanOneVideoWithoutPhoto(video));
                } else {
                    candidates.addAll(
                            scanOneVideo(video, referenceFeatures)
                    );
                }
            } catch (Exception e) {
                results.add(
                        "Could not analyze "
                                + video.getFileName()
                                + ": "
                                + (e.getMessage() == null
                                ? "Unknown error" : e.getMessage())
                );
            }
        }

        if (referenceFeatures == null) {
            if (results.isEmpty()) {
                results.add("No people detected in sampled frames.");
            }
            return results;
        }

        // Sort best colour similarity first.
        candidates.sort(
                Comparator.comparingDouble(c -> c.distance)
        );

        // Keep only the best candidate per video and second.
        Map<String, Candidate> uniqueCandidates =
                new LinkedHashMap<>();

        for (Candidate candidate : candidates) {
            String key = candidate.videoName + "|"
                    + Math.round(candidate.seconds);

            if (!uniqueCandidates.containsKey(key)) {
                uniqueCandidates.put(key, candidate);
            }
        }

        List<Candidate> uniqueSorted =
                new ArrayList<>(uniqueCandidates.values());

        uniqueSorted.sort(
                Comparator.comparingDouble(c -> c.distance)
        );

        int limit = Math.min(MAX_RESULTS, uniqueSorted.size());

        for (int i = 0; i < limit; i++) {
            Candidate candidate = uniqueSorted.get(i);

            results.add(
                    "UNVERIFIED VISUAL CANDIDATE"
                            + " | Video: " + candidate.videoName
                            + " | Timestamp: "
                            + String.format("%.2f seconds",
                            candidate.seconds)
                            + " | Colour distance: "
                            + String.format("%.3f",
                            candidate.distance)
                            + " | Human review required"
            );
        }

        if (results.isEmpty()) {
            results.add(
                    "No frames passed the basic colour similarity "
                            + "threshold. This does not prove the "
                            + "person is absent."
            );
        }

        return results;
    }

    private List<String> scanOneVideoWithoutPhoto(Path videoPath)
            throws Exception {

        List<String> results = new ArrayList<>();

        try (
                FFmpegFrameGrabber grabber =
                        new FFmpegFrameGrabber(videoPath.toFile());
                Java2DFrameConverter converter =
                        new Java2DFrameConverter()
        ) {
            grabber.start();

            double frameRate = grabber.getFrameRate();
            if (!Double.isFinite(frameRate) || frameRate <= 0) {
                frameRate = 30.0;
            }

            int interval = Math.max(1, (int) Math.round(frameRate));
            int frameNumber = 0;
            int sampledFrames = 0;
            Frame frame;

            while ((frame = grabber.grabImage()) != null) {
                if (frameNumber % interval == 0) {
                    BufferedImage image = converter.convert(frame);

                    if (image != null) {
                        sampledFrames++;

                        ByteArrayOutputStream output =
                                new ByteArrayOutputStream();

                        if (ImageIO.write(image, "jpg", output)) {
                            List<AiDetector.PersonDetection> people =
                                    aiDetector.detectPeopleDetailed(
                                            output.toByteArray()
                                    );

                            if (!people.isEmpty()) {
                                double seconds = getTimestamp(
                                        grabber, frameNumber, frameRate
                                );

                                results.add(
                                        "UNVERIFIED FRAME"
                                                + " | Video: "
                                                + videoPath.getFileName()
                                                + " | Timestamp: "
                                                + String.format(
                                                "%.2f seconds", seconds
                                        )
                                                + " | People detected: "
                                                + people.size()
                                                + " | Manual review required"
                                );
                            }
                        }
                    }
                }

                frameNumber++;
            }

            if (sampledFrames == 0) {
                results.add(
                        "No readable frames in "
                                + videoPath.getFileName()
                );
            }
        }

        return results;
    }

    private List<Candidate> scanOneVideo(
            Path videoPath,
            double[] referenceFeatures) throws Exception {

        List<Candidate> candidates = new ArrayList<>();

        try (
                FFmpegFrameGrabber grabber =
                        new FFmpegFrameGrabber(videoPath.toFile());
                Java2DFrameConverter converter =
                        new Java2DFrameConverter()
        ) {
            grabber.start();

            double frameRate = grabber.getFrameRate();
            if (!Double.isFinite(frameRate) || frameRate <= 0) {
                frameRate = 30.0;
            }

            int interval = Math.max(1, (int) Math.round(frameRate));
            int frameNumber = 0;
            Frame frame;

            while ((frame = grabber.grabImage()) != null) {
                if (frameNumber % interval == 0) {
                    BufferedImage image = converter.convert(frame);

                    if (image != null) {
                        ByteArrayOutputStream output =
                                new ByteArrayOutputStream();

                        if (ImageIO.write(image, "jpg", output)) {
                            List<AiDetector.PersonDetection> people =
                                    aiDetector.detectPeopleDetailed(
                                            output.toByteArray()
                                    );

                            double seconds = getTimestamp(
                                    grabber, frameNumber, frameRate
                            );

                            for (AiDetector.PersonDetection person
                                    : people) {

                                BufferedImage crop =
                                        cropPerson(image, person);

                                if (crop == null) {
                                    continue;
                                }

                                double[] features =
                                        getColorFeatures(crop);

                                double distance = colorDistance(
                                        referenceFeatures, features
                                );

                                if (distance <= COLOR_THRESHOLD) {
                                    candidates.add(
                                            new Candidate(
                                                    videoPath.getFileName()
                                                            .toString(),
                                                    seconds,
                                                    distance
                                            )
                                    );
                                }
                            }
                        }
                    }
                }

                frameNumber++;
            }
        }

        return candidates;
    }

    private double getTimestamp(
            FFmpegFrameGrabber grabber,
            int frameNumber,
            double frameRate) {

        long timestampMicros = grabber.getTimestamp();

        if (timestampMicros > 0) {
            return timestampMicros / 1_000_000.0;
        }

        return frameNumber / frameRate;
    }

    private BufferedImage cropPerson(
            BufferedImage image,
            AiDetector.PersonDetection person) {

        int x = Math.max(0, person.getX());
        int y = Math.max(0, person.getY());

        int width = Math.min(
                person.getWidth(), image.getWidth() - x
        );

        int height = Math.min(
                person.getHeight(), image.getHeight() - y
        );

        if (width <= 0 || height <= 0) {
            return null;
        }

        try {
            return image.getSubimage(x, y, width, height);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private double[] getColorFeatures(BufferedImage image) {
        int startY = image.getHeight() / 3;

        long red = 0;
        long green = 0;
        long blue = 0;
        long count = 0;

        for (int y = startY; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y);

                red += (rgb >> 16) & 255;
                green += (rgb >> 8) & 255;
                blue += rgb & 255;
                count++;
            }
        }

        if (count == 0) {
            return new double[] {0, 0, 0};
        }

        return new double[] {
                red / (double) count / 255.0,
                green / (double) count / 255.0,
                blue / (double) count / 255.0
        };
    }

    private double colorDistance(double[] a, double[] b) {
        double red = a[0] - b[0];
        double green = a[1] - b[1];
        double blue = a[2] - b[2];

        return Math.sqrt(
                (red * red + green * green + blue * blue) / 3.0
        );
    }

    private static class Candidate {
        final String videoName;
        final double seconds;
        final double distance;

        Candidate(
                String videoName,
                double seconds,
                double distance) {
            this.videoName = videoName;
            this.seconds = seconds;
            this.distance = distance;
        }
    }
}
