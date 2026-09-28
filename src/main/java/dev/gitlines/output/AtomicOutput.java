package dev.gitlines.output;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Publishes complete reports from temporary siblings, replacing regular files only.
 */
public final class AtomicOutput {
    /**
     * Writes a temporary sibling and moves it only after rendering succeeds.
     * @param destination report destination; parents are created as needed
     * @param renderer streaming report writer
     * @throws IOException if rendering, validation or publication fails
     */
    public static void write(Path destination, Writer renderer) throws IOException {
        var target = destination.toAbsolutePath().normalize();
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
            && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("report destination is not a regular file: " + target);
        }
        Files.createDirectories(target.getParent());
        var temporary = Files.createTempFile(target.getParent(), ".gitlines-", ".tmp");
        try {
            try (var output = Files.newOutputStream(temporary)) {
                renderer.write(output);
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /**
     * Represents the shared streaming boundary for report publication.
     */
    @FunctionalInterface
    public interface Writer {
        /**
         * Renders a complete report to the provided temporary file stream.
         * @param output owned output stream
         * @throws IOException if report rendering fails
         */
        void write(OutputStream output) throws IOException;
    }
}
