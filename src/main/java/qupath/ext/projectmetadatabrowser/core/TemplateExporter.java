package qupath.ext.projectmetadatabrowser.core;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import qupath.ext.projectmetadatabrowser.model.MutableEntryRow;

/**
 * Writes a CSV or TSV "template for fill-in" given the working copy and the
 * user's column choices. Mirrors the v0.2.0 CSV-quoting helpers in
 * {@code MetadataBrowserWindow.escapeForDelimiter}.
 */
public final class TemplateExporter {

    /**
     * Per-row identifier columns the user picked (e.g. Image ID, Image Name).
     * Resolved via a function so the caller can pass any column type that
     * already exists in the entry table.
     */
    public record IdentifierColumn(String header, Function<MutableEntryRow, String> resolver) {
        public IdentifierColumn {
            Objects.requireNonNull(header, "header");
            Objects.requireNonNull(resolver, "resolver");
        }
    }

    /**
     * Configuration for one export run.
     *
     * @param identifiers identifier columns the user picked.
     * @param seededKeys existing user-metadata keys whose current values are
     *                   copied into the template.
     * @param blankKeys brand-new column headers the user added; written with
     *                  no row data.
     * @param tsv true to write tab-separated, false for CSV (RFC 4180-style
     *            quoting).
     */
    public record Config(List<IdentifierColumn> identifiers,
                         List<String> seededKeys,
                         List<String> blankKeys,
                         boolean tsv) {
        public Config {
            identifiers = List.copyOf(Objects.requireNonNull(identifiers, "identifiers"));
            seededKeys = List.copyOf(Objects.requireNonNull(seededKeys, "seededKeys"));
            blankKeys = List.copyOf(Objects.requireNonNull(blankKeys, "blankKeys"));
        }
    }

    private TemplateExporter() {
        // utility class -- no instances
    }

    /**
     * Write the template to {@code dest}. Overwrites any existing file at
     * that path.
     *
     * @param rows the working-copy rows to write, in order.
     * @param config identifier and column choices.
     * @param dest output path. Parent directory must exist.
     * @return the number of data rows written (excludes the header line).
     * @throws IOException on write failure.
     */
    public static int writeTemplate(List<MutableEntryRow> rows,
                                    Config config,
                                    Path dest) throws IOException {
        Objects.requireNonNull(rows, "rows");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(dest, "dest");
        if (config.identifiers().isEmpty())
            throw new IllegalArgumentException("at least one identifier column is required");

        char sep = config.tsv() ? '\t' : ',';

        // De-duplicate column headers: identifier headers + seeded keys +
        // blank keys. Identifier headers are first by convention.
        LinkedHashSet<String> headerOrder = new LinkedHashSet<>();
        List<String> identifierHeaders = new ArrayList<>();
        for (IdentifierColumn id : config.identifiers()) {
            identifierHeaders.add(id.header());
            headerOrder.add(id.header());
        }
        for (String k : config.seededKeys()) {
            if (k != null && !k.isBlank())
                headerOrder.add(k);
        }
        for (String k : config.blankKeys()) {
            if (k != null && !k.isBlank())
                headerOrder.add(k);
        }

        try (BufferedWriter w = Files.newBufferedWriter(dest, StandardCharsets.UTF_8)) {
            boolean first = true;
            for (String h : headerOrder) {
                if (!first) w.write(sep);
                w.write(escapeForDelimiter(h, sep));
                first = false;
            }
            w.write('\n');

            int dataRows = 0;
            for (MutableEntryRow row : rows) {
                first = true;
                for (String h : headerOrder) {
                    if (!first) w.write(sep);
                    String value;
                    if (identifierHeaders.contains(h)) {
                        // identifier resolver lookup
                        value = "";
                        for (IdentifierColumn id : config.identifiers()) {
                            if (id.header().equals(h)) {
                                value = nullSafe(id.resolver().apply(row));
                                break;
                            }
                        }
                    } else if (config.seededKeys().contains(h)) {
                        value = row.getMetadata(h);
                    } else {
                        // blank-key column -- written empty
                        value = "";
                    }
                    w.write(escapeForDelimiter(value, sep));
                    first = false;
                }
                w.write('\n');
                dataRows++;
            }
            return dataRows;
        }
    }

    /**
     * CSV / TSV cell escape. RFC 4180-style quoting when {@code sep} is a
     * comma; tabs / newlines are stripped for TSV (Excel's clipboard
     * paste behaviour cannot recover from embedded tabs anyway).
     */
    public static String escapeForDelimiter(String s, char sep) {
        if (s == null) return "";
        if (sep == ',') {
            boolean needsQuote = s.indexOf(',') >= 0 || s.indexOf('"') >= 0
                    || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0;
            if (!needsQuote) return s;
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
