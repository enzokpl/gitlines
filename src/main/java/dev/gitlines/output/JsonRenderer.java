package dev.gitlines.output;

import com.fasterxml.jackson.core.JsonFactory;
import dev.gitlines.model.AnalysisResult;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Writes schema version 1 with Jackson's UTF-8 streaming API and no reflection.
 */
public final class JsonRenderer {
    /**
     * Serializes the complete result in the same author order as the terminal.
     * @param result complete ordered analysis
     * @param output JSON destination stream
     * @throws IOException if JSON output fails
     */
    public void render(AnalysisResult result, OutputStream output) throws IOException {
        try (var json = new JsonFactory().createGenerator(output)) {
            json.useDefaultPrettyPrinter();
            json.writeStartObject();
            json.writeNumberField("schemaVersion", 1);
            json.writeObjectFieldStart("repository");
            var repository = result.repository();
            json.writeStringField("name", repository.name());
            json.writeStringField("path", repository.path().toString());
            json.writeStringField("revision", repository.revision());
            json.writeStringField("branch", repository.branch());
            json.writeBooleanField("shallow", repository.shallow());
            json.writeEndObject();
            json.writeObjectFieldStart("summary");
            var totals = result.summary();
            json.writeNumberField("commits", totals.commits());
            json.writeNumberField("contributors", result.authors().size());
            json.writeNumberField("added", totals.added());
            json.writeNumberField("deleted", totals.deleted());
            json.writeNumberField("net", totals.net());
            json.writeEndObject();
            json.writeArrayFieldStart("authors");
            for (var author : result.authors()) {
                json.writeStartObject();
                json.writeStringField("name", author.name());
                json.writeStringField("email", author.email());
                json.writeNumberField("commits", author.commits());
                json.writeNumberField("added", author.added());
                json.writeNumberField("deleted", author.deleted());
                json.writeNumberField("net", author.net());
                json.writeEndObject();
            }
            json.writeEndArray();
            json.writeEndObject();
        }
    }
}
