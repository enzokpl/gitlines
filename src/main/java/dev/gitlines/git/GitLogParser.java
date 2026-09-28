package dev.gitlines.git;

import dev.gitlines.model.AuthorStats;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * Consumes NUL-delimited commit headers and numstat records incrementally.
 * Only author fields are decoded; filenames, including rename pairs, are discarded.
 * Binary records contribute no lines. Memory is bounded by one author identity.
 */
final class GitLogParser {
    /**
     * Emits one aggregated record per commit from the private GL header protocol.
     * @param source Git stdout
     * @param consumer recipient of complete commit contributions
     * @throws IOException if the stream is truncated or malformed
     */
    void parse(InputStream source, Consumer<AuthorStats> consumer) throws IOException {
        var input = new BufferedInputStream(source, 65536);
        String name = null;
        String email = null;
        long added = 0;
        long deleted = 0;
        int first;
        while ((first = input.read()) != -1) {
            if (first == 0 || first == '\n') {
                continue;
            }
            if (first == 'G') {
                expect(input, 'L');
                expect(input, 0);
                if (name != null) {
                    consumer.accept(new AuthorStats(name, email, 1, added, deleted));
                }
                name = identity(input);
                email = identity(input);
                added = 0;
                deleted = 0;
                continue;
            }
            if (name == null) {
                throw new IOException("numstat data appears before a commit header");
            }
            long additions = number(input, first);
            long removals = number(input, input.read());
            int pathStart = input.read();
            if (pathStart == 0) {
                skipPath(input, input.read());
                skipPath(input, input.read());
            } else {
                skipPath(input, pathStart);
            }
            if (additions == -1 && removals == -1) {
                continue;
            }
            if (additions < 0 || removals < 0) {
                throw new IOException("inconsistent binary numstat record");
            }
            added = Math.addExact(added, additions);
            deleted = Math.addExact(deleted, removals);
        }
        if (name != null) {
            consumer.accept(new AuthorStats(name, email, 1, added, deleted));
        }
    }

    /**
     * Reads a decimal count or Git's explicit binary marker through its tab.
     * @param input buffered protocol stream
     * @param first first field byte
     * @return nonnegative line count, or -1 for a binary marker
     * @throws IOException for invalid or truncated fields
     */
    private long number(InputStream input, int first) throws IOException {
        if (first == '-') {
            expect(input, '\t');
            return -1;
        }
        if (first < '0' || first > '9') {
            throw new IOException("invalid numstat count");
        }
        long value = 0;
        int current = first;
        while (current != '\t') {
            if (current < '0' || current > '9') {
                throw new IOException("invalid numstat count");
            }
            value = Math.addExact(Math.multiplyExact(value, 10), current - '0');
            current = input.read();
        }
        return value;
    }

    /**
     * Discards a filename without allocating, independent of tabs and newlines.
     * @param input buffered protocol stream
     * @param first first filename byte
     * @throws IOException if the filename lacks its NUL terminator
     */
    private void skipPath(InputStream input, int first) throws IOException {
        int current = first;
        while (current != 0) {
            if (current == -1) {
                throw new IOException("truncated numstat filename");
            }
            current = input.read();
        }
    }

    /**
     * Decodes an author field with a defensive 1 MiB bound on malformed input.
     * @param input buffered protocol stream
     * @return exact UTF-8 author field
     * @throws IOException if the field is truncated or exceeds the bound
     */
    private String identity(InputStream input) throws IOException {
        var bytes = new ByteArrayOutputStream();
        int current;
        while ((current = input.read()) != 0) {
            if (current == -1 || bytes.size() >= 1048576) {
                throw new IOException("invalid Git author field");
            }
            bytes.write(current);
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    /**
     * Validates a fixed protocol byte.
     * @param input protocol stream
     * @param expected required next byte
     * @throws IOException if the stream does not match
     */
    private void expect(InputStream input, int expected) throws IOException {
        if (input.read() != expected) {
            throw new IOException("invalid Git history protocol");
        }
    }
}
