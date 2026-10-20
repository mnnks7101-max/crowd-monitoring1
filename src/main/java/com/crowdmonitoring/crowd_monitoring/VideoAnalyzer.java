package com.crowdmonitoring.crowd_monitoring;

import org.springframework.stereotype.Component;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;

@Component
public class VideoAnalyzer {

    private final AiDetector aiDetector;

    private final Map<String, Map<String, Object>> progressStore =
            new ConcurrentHashMap<>();

    public VideoAnalyzer(AiDetector aiDetector) {
        this.aiDetector = aiDetector;
    }

    public Map<String, Object> analyzeVideo(
            Path videoPath, double area) throws Exception {
        return analyzeVideo(videoPath, area, null);
    }

    public Map<String, Object> analyzeVideo(
            Path videoPath, double area, String jobId)
            throws Exception {

        File videoFile = videoPath.toFile();

        if (!videoFile.exists()) {
            throw new IllegalArgumentException("Video file not found.");
        }

        int maxPeople = 0;
        int framesAnalyzed = 0;
        double durationSeconds = 0;

        try (
                FFmpegFrameGrabber grabber =
                        new FFmpegFrameGrabber(videoFile);
                Java2DFrameConverter converter =
                        new Java2DFrameConverter()
        ) {
            grabber.start();

            durationSeconds =
                    grabber.getLengthInTime() / 1_000_000.0;

            if (!Double.isFinite(durationSeconds)
                    || durationSeconds < 0) {
                durationSeconds = 0;
            }

            updateProgress(
                    jobId, 0, 0, 0, durationSeconds, false
            );

            Frame frame;
            int frameNumber = 0;
            long lastUpdate = System.currentTimeMillis();
            double frameRate = grabber.getFrameRate();

            if (!Double.isFinite(frameRate) || frameRate <= 0) {
                frameRate = 30.0;
            }

            while ((frame = grabber.grabImage()) != null) {

                if (frameNumber % 30 == 0) {
                    BufferedImage image = converter.convert(frame);

                    if (image != null) {
                        java.io.ByteArrayOutputStream output =
                                new java.io.ByteArrayOutputStream();

                        if (javax.imageio.ImageIO.write(
                                image, "jpg", output)) {

                            int people = aiDetector.detectPeople(
                                    output.toByteArray()
                            );

                            maxPeople = Math.max(maxPeople, people);
                            framesAnalyzed++;
                        }
                    }

                    long now = System.currentTimeMillis();

                    if (now - lastUpdate >= 200
                            || frameNumber == 0) {

                        double processedSeconds =
                                frameNumber / frameRate;

                        double percent = durationSeconds > 0
                                ? Math.min(
                                        99.0,
                                        processedSeconds
                                                / durationSeconds * 100.0
                                )
                                : 0;

                        updateProgress(
                                jobId,
                                maxPeople,
                                framesAnalyzed,
                                percent,
                                durationSeconds,
                                false
                        );

                        lastUpdate = now;
                    }
                }

                frameNumber++;
            }

            grabber.stop();

        } catch (Exception e) {
            updateProgress(
                    jobId, maxPeople, framesAnalyzed, 0,
                    durationSeconds, true
            );
            throw e;
        }

        double density = maxPeople / area;

        String status;
        if (density < 0.5) {
            status = "LOW";
        } else if (density < 2.0) {
            status = "MEDIUM";
        } else {
            status = "HIGH";
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message", "Video analysis completed.");
        result.put("analysisStatus", "COMPLETED");
        result.put("peopleCount", maxPeople);
        result.put("density", Math.round(density * 100.0) / 100.0);
        result.put("status", status);
        result.put("durationSeconds", durationSeconds);
        result.put("framesAnalyzed", framesAnalyzed);

        updateProgress(
                jobId, maxPeople, framesAnalyzed,
                100, durationSeconds, true
        );

        return result;
    }

    // Create a separate video with green detection boxes.
    // The source video is not modified.
    public void createAnnotatedVideo(
            Path inputPath, Path outputPath) throws Exception {

        File inputFile = inputPath.toFile();

        if (!inputFile.exists()) {
            throw new IllegalArgumentException("Video file not found.");
        }

        try (
                FFmpegFrameGrabber grabber =
                        new FFmpegFrameGrabber(inputFile);
                Java2DFrameConverter converter =
                        new Java2DFrameConverter()
        ) {
            grabber.start();

            int width = grabber.getImageWidth();
            int height = grabber.getImageHeight();

            double frameRate = grabber.getFrameRate();
            if (!Double.isFinite(frameRate) || frameRate <= 0) {
                frameRate = 25.0;
            }

            try (FFmpegFrameRecorder recorder =
                         new FFmpegFrameRecorder(
                                 outputPath.toString(), width, height)) {

                recorder.setFormat("mp4");
                recorder.setFrameRate(frameRate);
                recorder.setVideoCodec(avcodec.AV_CODEC_ID_MPEG4);
                recorder.setVideoBitrate(2_000_000);
                recorder.setPixelFormat(
                        org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUV420P
                );

                recorder.start();

                Frame frame;

                while ((frame = grabber.grabImage()) != null) {
                    BufferedImage image = converter.convert(frame);

                    if (image == null) {
                        continue;
                    }

                    byte[] imageBytes;
                    try (java.io.ByteArrayOutputStream output =
                                 new java.io.ByteArrayOutputStream()) {

                        if (!javax.imageio.ImageIO.write(
                                image, "jpg", output)) {
                            continue;
                        }

                        imageBytes = output.toByteArray();
                    }

                    java.util.List<AiDetector.PersonDetection> people =
                            aiDetector.detectPeopleDetailed(imageBytes);

                    Graphics2D graphics = image.createGraphics();

                    try {
                        graphics.setColor(Color.GREEN);
                        graphics.setStroke(new BasicStroke(3));
                        graphics.setFont(new Font(
                                "Arial", Font.BOLD,
                                Math.max(14, width / 60)
                        ));

                        for (int i = 0; i < people.size(); i++) {
                            AiDetector.PersonDetection person = people.get(i);

                            int x = person.getX();
                            int y = person.getY();
                            int boxWidth = Math.min(
                                    person.getWidth(), width - x
                            );
                            int boxHeight = Math.min(
                                    person.getHeight(), height - y
                            );

                            if (boxWidth <= 0 || boxHeight <= 0) {
                                continue;
                            }

                            graphics.drawRect(
                                    x, y, boxWidth, boxHeight
                            );

                            graphics.drawString(
                                    "Person " + (i + 1),
                                    x, Math.max(15, y - 5)
                            );
                        }

                        graphics.setColor(Color.YELLOW);
                        graphics.drawString(
                                "People detected: " + people.size(),
                                15, 30
                        );

                    } finally {
                        graphics.dispose();
                    }

                    recorder.record(converter.convert(image));
                }

                recorder.stop();
            }

            grabber.stop();
        }
    }

    public Map<String, Object> getProgress(String jobId) {
        Map<String, Object> progress = progressStore.get(jobId);

        if (progress == null) {
            return Map.of("error", "Analysis job not found.");
        }

        return new LinkedHashMap<>(progress);
    }

    private void updateProgress(
            String jobId,
            int peopleCount,
            int framesAnalyzed,
            double percent,
            double durationSeconds,
            boolean completed) {

        if (jobId == null || jobId.isBlank()) {
            return;
        }

        Map<String, Object> progress = new LinkedHashMap<>();
        progress.put("peopleCount", peopleCount);
        progress.put("framesAnalyzed", framesAnalyzed);
        progress.put("progressPercent", Math.round(percent));
        progress.put("durationSeconds", durationSeconds);
        progress.put("completed", completed);

        progressStore.put(jobId, progress);
    }
}
