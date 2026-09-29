package dev.gitlines.output;

import dev.gitlines.model.AnalysisResult;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Locale;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

/**
 * Writes a self-contained semantic HTML report without JavaScript or external assets.
 * The JDK XML writer escapes repository data exclusively in text-node contexts.
 */
public final class HtmlRenderer {
    private static final String STYLE = """
        :root { color-scheme: light; font-family: system-ui, sans-serif; color: #182a36; background: #f3f6f8; }
        * { box-sizing: border-box; }
        body { margin: 0; padding: 2rem 1rem; }
        main { max-width: 1200px; margin: auto; }
        h1 { font-size: clamp(1.8rem, 5vw, 3rem); margin: .5rem 0; overflow-wrap: anywhere; }
        .eyebrow { font-size: .8rem; text-transform: uppercase; letter-spacing: .12em; font-weight: 700; }
        .note { color: #455b6b; line-height: 1.6; }
        dl { display: grid; grid-template-columns: max-content minmax(0, 1fr); gap: .6rem 1rem; }
        dt { font-weight: 600; } dd { margin: 0; overflow-wrap: anywhere; }
        .summary { display: flex; flex-wrap: wrap; gap: 1rem; margin: 2rem 0; }
        .metric { flex: 1 1 170px; background: white; padding: 1.2rem;
          border: 1px solid #d0dae0; border-radius: .6rem; }
        .metric h2 { font-size: .85rem; color: #455b6b; margin: 0 0 .6rem; }
        .metric p { font-size: 1.6rem; font-weight: 700; margin: 0; }
        .table-wrap { overflow-x: auto; background: white; border: 1px solid #d0dae0; border-radius: .6rem; }
        table { border-collapse: collapse; width: 100%; font-size: .95rem; }
        caption { text-align: left; font-weight: 600; padding: 1rem; }
        th, td { padding: .9rem 1rem; border-top: 1px solid #d0dae0;
          text-align: right; font-variant-numeric: tabular-nums; }
        thead th { background: #e7eef2; white-space: nowrap; }
        th:first-child, td:nth-child(2), thead th:nth-child(2) { text-align: left; overflow-wrap: anywhere; }
        tbody th { text-align: left; font-weight: 600; min-width: 12rem; max-width: 24rem; }
        tbody tr:nth-child(even) { background: #f8fafb; }
        .empty { padding: 2rem; background: white; border: 1px solid #d0dae0; border-radius: .6rem; }
        footer { margin-top: 2rem; font-size: .85rem; color: #455b6b; line-height: 1.6; }
        @media (max-width: 600px) {
          body { padding: 1rem .75rem; } dl { grid-template-columns: 1fr; } dd { margin-bottom: .5rem; }
        }
        """;

    /**
     * Streams escaped UTF-8 HTML with all styles embedded in the document.
     * @param result complete ordered analysis
     * @param output report destination stream
     * @throws IOException if serialization or output fails
     */
    public void render(AnalysisResult result, OutputStream output) throws IOException {
        try {
            var html = XMLOutputFactory.newDefaultFactory().createXMLStreamWriter(output, "UTF-8");
            html.writeDTD("<!DOCTYPE html>");
            html.writeStartElement("html");
            html.writeAttribute("lang", "en");
            html.writeStartElement("head");
            html.writeEmptyElement("meta");
            html.writeAttribute("charset", "UTF-8");
            html.writeEmptyElement("meta");
            html.writeAttribute("name", "viewport");
            html.writeAttribute("content", "width=device-width, initial-scale=1");
            html.writeEmptyElement("meta");
            html.writeAttribute("http-equiv", "Content-Security-Policy");
            html.writeAttribute(
                "content",
                "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'"
            );
            element(html, "title", "gitlines — " + result.repository().name());
            element(html, "style", STYLE);
            html.writeEndElement();
            html.writeStartElement("body");
            html.writeStartElement("main");
            html.writeStartElement("header");
            html.writeStartElement("p");
            html.writeAttribute("class", "eyebrow");
            html.writeCharacters("gitlines · contribution report");
            html.writeEndElement();
            element(html, "h1", result.repository().name());
            element(html, "p", "Historical activity by canonical Git author. Ordered by additions + deletions.");
            metadata(html, result);
            html.writeEndElement();
            html.writeStartElement("section");
            html.writeAttribute("class", "summary");
            html.writeAttribute("aria-label", "Contribution totals");
            var totals = result.summary();
            metric(html, "Commits", totals.commits(), false);
            metric(html, "Contributors", result.authors().size(), false);
            metric(html, "Additions", totals.added(), false);
            metric(html, "Deletions", totals.deleted(), false);
            metric(html, "Net lines", totals.net(), true);
            html.writeEndElement();
            contributors(html, result);
            html.writeStartElement("footer");
            element(html, "p", "Non-merge commits reachable from this revision. Binary files contribute no lines. "
                + "Git .mailmap canonicalizes identities. Uncommitted changes are excluded.");
            element(html, "p", "These counters describe historical additions and removals, not current line ownership. "
                + "This report is local, static and self-contained.");
            html.writeEndElement();
            html.writeEndElement();
            html.writeEndElement();
            html.writeEndElement();
            html.flush();
            html.close();
        } catch (XMLStreamException error) {
            throw new IOException("failed to write HTML report", error);
        }
    }

    /**
     * Writes repository metadata, omitting an absent detached branch.
     * @param html escaping writer
     * @param result analyzed repository
     * @throws XMLStreamException if output fails
     */
    private void metadata(XMLStreamWriter html, AnalysisResult result) throws XMLStreamException {
        var repository = result.repository();
        html.writeStartElement("dl");
        detail(html, "Repository", repository.path().toString());
        detail(html, "Revision", repository.revision() == null ? "No commits" : repository.revision());
        if (repository.branch() != null) {
            detail(html, "Branch", repository.branch());
        }
        if (repository.shallow()) {
            detail(html, "Shallow clone", "Yes — only available history was analyzed");
        }
        html.writeEndElement();
    }

    /**
     * Writes one semantic definition-list entry.
     * @param html escaping writer
     * @param label metadata label
     * @param value untrusted metadata value
     * @throws XMLStreamException if output fails
     */
    private void detail(XMLStreamWriter html, String label, String value) throws XMLStreamException {
        element(html, "dt", label);
        element(html, "dd", value);
    }

    /**
     * Writes a labeled summary counter with an explicit sign for net values.
     * @param html escaping writer
     * @param label metric label
     * @param value historical count
     * @param signed whether a positive sign is useful
     * @throws XMLStreamException if output fails
     */
    private void metric(XMLStreamWriter html, String label, long value, boolean signed) throws XMLStreamException {
        html.writeStartElement("div");
        html.writeAttribute("class", "metric");
        element(html, "h2", label);
        element(html, "p", number(value, signed));
        html.writeEndElement();
    }

    /**
     * Writes a semantic contributor table or a readable empty state.
     * @param html escaping writer
     * @param result complete sorted contributions
     * @throws XMLStreamException if output fails
     */
    private void contributors(XMLStreamWriter html, AnalysisResult result) throws XMLStreamException {
        if (result.authors().isEmpty()) {
            html.writeStartElement("p");
            html.writeAttribute("class", "empty");
            html.writeCharacters("No contributions found.");
            html.writeEndElement();
            return;
        }
        html.writeStartElement("div");
        html.writeAttribute("class", "table-wrap");
        html.writeStartElement("table");
        element(html, "caption", "Contributors · most historical activity first");
        html.writeStartElement("thead");
        html.writeStartElement("tr");
        for (var label : new String[] {"Name", "Email", "Commits", "Added", "Deleted", "Net"}) {
            html.writeStartElement("th");
            html.writeAttribute("scope", "col");
            html.writeCharacters(label);
            html.writeEndElement();
        }
        html.writeEndElement();
        html.writeEndElement();
        html.writeStartElement("tbody");
        for (var author : result.authors()) {
            html.writeStartElement("tr");
            html.writeStartElement("th");
            html.writeAttribute("scope", "row");
            html.writeCharacters(author.name());
            html.writeEndElement();
            element(html, "td", author.email());
            element(html, "td", number(author.commits(), false));
            element(html, "td", number(author.added(), false));
            element(html, "td", number(author.deleted(), false));
            element(html, "td", number(author.net(), true));
            html.writeEndElement();
        }
        html.writeEndElement();
        html.writeEndElement();
        html.writeEndElement();
    }

    /**
     * Formats a counter independent of the machine locale.
     * @param value count or balance
     * @param signed whether to show a positive sign
     * @return grouped decimal value
     */
    private String number(long value, boolean signed) {
        return String.format(Locale.ROOT, signed ? "%+,d" : "%,d", value);
    }

    /**
     * Writes repository data as escaped text, never as markup or script.
     * @param html escaping writer
     * @param tag fixed trusted element name
     * @param value text content
     * @throws XMLStreamException if output fails
     */
    private void element(XMLStreamWriter html, String tag, String value) throws XMLStreamException {
        html.writeStartElement(tag);
        html.writeCharacters(value);
        html.writeEndElement();
    }
}
