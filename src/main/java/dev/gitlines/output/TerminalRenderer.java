package dev.gitlines.output;

import dev.gitlines.model.AnalysisResult;
import java.io.PrintWriter;
import java.util.Locale;

/**
 * Prints deterministic, color-free historical counters and canonical identities.
 * Control characters are replaced to keep untrusted Git data on one terminal line.
 */
public final class TerminalRenderer {
    /**
     * Renders metadata, totals and a bounded-width contributor table.
     * @param result complete ordered analysis
     * @param output CLI output writer
     */
    public void render(AnalysisResult result, PrintWriter output) {
        var repository = result.repository();
        var totals = result.summary();
        output.println("Repository: " + safe(repository.name()));
        output.println("Revision: " + (repository.revision() == null ? "(no commits)" : repository.revision()));
        output.println("Branch: " + (repository.branch() == null ? "(detached HEAD)" : safe(repository.branch())));
        output.println("Shallow: " + repository.shallow());
        output.printf(Locale.ROOT, "Commits analyzed: %,d%nContributors: %,d%n", totals.commits(), result.authors().size());
        output.printf(Locale.ROOT, "Added: %,d%nDeleted: %,d%nNet: %+,d%n%n", totals.added(), totals.deleted(), totals.net());
        if (result.authors().isEmpty()) {
            output.println("No contributions found.");
            output.flush();
            return;
        }
        int width = 20;
        for (var author : result.authors()) {
            var label = identity(author.name(), author.email());
            width = Math.max(width, Math.min(60, label.codePointCount(0, label.length())));
        }
        int numberWidth = Math.max(8, String.format(Locale.ROOT, "%+,d",
            Math.max(totals.commits(), Math.max(totals.added(), totals.deleted()))).length());
        String column = "  %" + numberWidth + "s";
        String format = "%s" + column.repeat(4) + "%n";
        output.printf(format, padded("Author <email>", width), "Commits", "Added", "Deleted", "Net");
        output.println("-".repeat(width + 4 * (numberWidth + 2)));
        for (var author : result.authors()) {
            output.printf(
                format,
                padded(identity(author.name(), author.email()), width),
                String.format(Locale.ROOT, "%,d", author.commits()),
                String.format(Locale.ROOT, "%,d", author.added()),
                String.format(Locale.ROOT, "%,d", author.deleted()),
                String.format(Locale.ROOT, "%+,d", author.net())
            );
        }
        output.flush();
    }

    /**
     * Formats both identity fields so authors with identical names remain distinct.
     * @param name canonical author name
     * @param email canonical author email
     * @return single-line display identity
     */
    private String identity(String name, String email) {
        return safe(name) + " <" + safe(email) + ">";
    }

    /**
     * Replaces terminal controls and Unicode line separators with spaces.
     * @param value repository-controlled text
     * @return text safe to display on one line
     */
    private String safe(String value) {
        var text = new StringBuilder();
        value.codePoints().forEach(code -> text.appendCodePoint(
            Character.isISOControl(code) || code == 0x2028 || code == 0x2029 ? ' ' : code
        ));
        return text.toString();
    }

    /**
     * Truncates by Unicode code point and pads without splitting surrogate pairs.
     * @param value single-line identity
     * @param width maximum number of displayed code points
     * @return padded or ellipsized identity
     */
    private String padded(String value, int width) {
        int length = value.codePointCount(0, value.length());
        if (length > width) {
            return value.substring(0, value.offsetByCodePoints(0, width - 1)) + "…";
        }
        return value + " ".repeat(width - length);
    }
}
