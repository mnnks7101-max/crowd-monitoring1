
package com.crowdmonitoring.crowd_monitoring;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

@Component
public class MissingPersonStore {

    private final Path storageFile =
            Path.of("data", "missing-person-complaints.txt");

    public MissingPersonStore() {
        try {
            Files.createDirectories(storageFile.getParent());

            if (!Files.exists(storageFile)) {
                Files.createFile(storageFile);
            }
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Could not initialize complaint storage.", e);
        }
    }

    public synchronized void save(
            String caseId,
            String personName,
            String clothing,
            String lastSeen,
            String lastSeenTime,
            String photoFilename) throws IOException {

        String record = String.join("\t",
                clean(caseId),
                clean(personName),
                clean(clothing),
                clean(lastSeen),
                clean(lastSeenTime),
                clean(photoFilename));

        Files.writeString(
                storageFile,
                record + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    public synchronized List<String> getAll() throws IOException {
        return new ArrayList<>(
                Files.readAllLines(storageFile, StandardCharsets.UTF_8));
    }

    private String clean(String value) {
        if (value == null) {
            return "";
        }

        return value.replace('\t', ' ')
                    .replace('\n', ' ')
                    .replace('\r', ' ');
    }
}
