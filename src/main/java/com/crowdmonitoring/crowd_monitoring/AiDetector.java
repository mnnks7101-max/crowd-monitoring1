package com.crowdmonitoring.crowd_monitoring;

import ai.onnxruntime.*;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.FloatBuffer;
import java.nio.file.Paths;
import java.util.*;
import java.io.ByteArrayOutputStream;

@Component
public class AiDetector {

    private static final int SIZE = 640;
    private static final float CONFIDENCE = 0.20f;
    private static final float IOU_THRESHOLD = 0.45f;

    private final OrtEnvironment environment;
    private final OrtSession session;
    private final String inputName;

    public AiDetector() throws Exception {
        environment = OrtEnvironment.getEnvironment();

        String modelPath = Paths.get(
                "models", "yolov8n.onnx"
        ).toAbsolutePath().toString();

        session = environment.createSession(
                modelPath,
                new OrtSession.SessionOptions()
        );

        inputName = session.getInputInfo()
                .keySet().iterator().next();

        System.out.println("AI model loaded successfully!");
        System.out.println(
                "Model inputs: " + session.getInputInfo().keySet()
        );
    }

    // Existing method: preserves your crowd-counting API.
    public int detectPeople(byte[] imageBytes) throws Exception {
        return detectPeopleDetailed(imageBytes).size();
    }

    // New method: returns the bounding box of each detected person.
    public List<PersonDetection> detectPeopleDetailed(
            byte[] imageBytes) throws Exception {

        BufferedImage original = ImageIO.read(
                new ByteArrayInputStream(imageBytes)
        );

        if (original == null) {
            throw new IllegalArgumentException(
                    "The uploaded file is not a supported image."
            );
        }

        int originalWidth = original.getWidth();
        int originalHeight = original.getHeight();

        BufferedImage resized = new BufferedImage(
                SIZE, SIZE, BufferedImage.TYPE_INT_RGB
        );

        Graphics2D graphics = resized.createGraphics();
        graphics.drawImage(original, 0, 0, SIZE, SIZE, null);
        graphics.dispose();

        float[] input = new float[3 * SIZE * SIZE];

        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int rgb = resized.getRGB(x, y);
                int index = y * SIZE + x;

                input[index] =
                        ((rgb >> 16) & 255) / 255.0f;

                input[SIZE * SIZE + index] =
                        ((rgb >> 8) & 255) / 255.0f;

                input[2 * SIZE * SIZE + index] =
                        (rgb & 255) / 255.0f;
            }
        }

        long[] shape = {1, 3, SIZE, SIZE};

        try (
                OnnxTensor tensor = OnnxTensor.createTensor(
                        environment,
                        FloatBuffer.wrap(input),
                        shape
                );

                OrtSession.Result result = session.run(
                        Collections.singletonMap(inputName, tensor)
                )
        ) {
            Object value = result.get(0).getValue();

            if (!(value instanceof float[][][] output)
                    || output.length == 0) {
                throw new IllegalStateException(
                        "Unexpected YOLO output format."
                );
            }

            List<PersonDetection> detections =
                    new ArrayList<>();

            float[][] predictions = output[0];

            // Standard YOLOv8 COCO output: [1, 84, 8400].
            boolean channelsFirst = predictions.length == 84;

            int numberOfPredictions = channelsFirst
                    ? predictions[0].length
                    : predictions.length;

            for (int i = 0; i < numberOfPredictions; i++) {

                float cx;
                float cy;
                float width;
                float height;
                float score;

                if (channelsFirst) {
                    cx = predictions[0][i];
                    cy = predictions[1][i];
                    width = predictions[2][i];
                    height = predictions[3][i];

                    // COCO class 0 is person.
                    score = predictions[4][i];

                } else {
                    if (predictions[i].length < 5) {
                        continue;
                    }

                    cx = predictions[i][0];
                    cy = predictions[i][1];
                    width = predictions[i][2];
                    height = predictions[i][3];
                    score = predictions[i][4];
                }

                if (!Float.isFinite(score)
                        || score < CONFIDENCE
                        || !Float.isFinite(cx)
                        || !Float.isFinite(cy)
                        || !Float.isFinite(width)
                        || !Float.isFinite(height)
                        || width <= 0
                        || height <= 0) {
                    continue;
                }

                // Convert coordinates back to original image size.
                float scaleX = originalWidth / (float) SIZE;
                float scaleY = originalHeight / (float) SIZE;

                float left = (cx - width / 2) * scaleX;
                float top = (cy - height / 2) * scaleY;
                float right = (cx + width / 2) * scaleX;
                float bottom = (cy + height / 2) * scaleY;

                left = Math.max(0, Math.min(left, originalWidth));
                top = Math.max(0, Math.min(top, originalHeight));
                right = Math.max(0, Math.min(right, originalWidth));
                bottom = Math.max(0, Math.min(bottom, originalHeight));

                if (right > left && bottom > top) {
                    detections.add(
                            new PersonDetection(
                                    left, top, right, bottom, score
                            )
                    );
                }
            }

            return applyNms(detections);
        }
    }

// Draw bounding boxes around detected people.
public byte[] detectPeopleWithBoxes(byte[] imageBytes)
        throws Exception {

    BufferedImage image = ImageIO.read(
            new ByteArrayInputStream(imageBytes)
    );

    if (image == null) {
        throw new IllegalArgumentException(
                "Could not read the uploaded image."
        );
    }

    List<PersonDetection> people =
            detectPeopleDetailed(imageBytes);

    Graphics2D graphics = image.createGraphics();

    try {
        graphics.setColor(java.awt.Color.GREEN);
        graphics.setStroke(new java.awt.BasicStroke(3));

        graphics.setFont(
                new java.awt.Font(
                        "Arial",
                        java.awt.Font.BOLD,
                        Math.max(14, image.getWidth() / 60)
                )
        );

        for (int i = 0; i < people.size(); i++) {
            PersonDetection person = people.get(i);

            int x = person.getX();
            int y = person.getY();
            int width = Math.min(
                    person.getWidth(), image.getWidth() - x
            );
            int height = Math.min(
                    person.getHeight(), image.getHeight() - y
            );

            if (width <= 0 || height <= 0) {
                continue;
            }

            graphics.drawRect(x, y, width, height);

            String label = "Person " + (i + 1);

            graphics.drawString(
                    label,
                    x,
                    Math.max(15, y - 5)
            );
        }

        graphics.setColor(java.awt.Color.YELLOW);
        graphics.drawString(
                "People detected: " + people.size(),
                15,
                30
        );

    } finally {
        graphics.dispose();
    }

    ByteArrayOutputStream output =
            new ByteArrayOutputStream();

    ImageIO.write(image, "jpg", output);

    return output.toByteArray();
}

    private List<PersonDetection> applyNms(
            List<PersonDetection> detections) {

        detections.sort(
                (a, b) -> Float.compare(b.confidence, a.confidence)
        );

        List<PersonDetection> kept = new ArrayList<>();

        for (PersonDetection candidate : detections) {
            boolean duplicate = false;

            for (PersonDetection accepted : kept) {
                if (iou(candidate, accepted) > IOU_THRESHOLD) {
                    duplicate = true;
                    break;
                }
            }

            if (!duplicate) {
                kept.add(candidate);
            }
        }

        return kept;
    }

    private float iou(PersonDetection a, PersonDetection b) {
        float left = Math.max(a.left, b.left);
        float top = Math.max(a.top, b.top);
        float right = Math.min(a.right, b.right);
        float bottom = Math.min(a.bottom, b.bottom);

        float intersection =
                Math.max(0, right - left)
                * Math.max(0, bottom - top);

        float areaA =
                (a.right - a.left) * (a.bottom - a.top);

        float areaB =
                (b.right - b.left) * (b.bottom - b.top);

        float union = areaA + areaB - intersection;

        return union <= 0 ? 0 : intersection / union;
    }

    // Public result object for other services to use.
    public static class PersonDetection {

        public final float left;
        public final float top;
        public final float right;
        public final float bottom;
        public final float confidence;

        public PersonDetection(
                float left,
                float top,
                float right,
                float bottom,
                float confidence) {

            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.confidence = confidence;
        }

        public int getX() {
            return Math.max(0, (int) Math.floor(left));
        }

        public int getY() {
            return Math.max(0, (int) Math.floor(top));
        }

        public int getWidth() {
            return Math.max(
                    0, (int) Math.ceil(right) - getX()
            );
        }

        public int getHeight() {
            return Math.max(
                    0, (int) Math.ceil(bottom) - getY()
            );
        }
    }
}
