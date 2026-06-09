package qupath.ext.projectmetadatabrowser.ui;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import qupath.ext.projectmetadatabrowser.core.BulkSetCellsCommand;
import qupath.ext.projectmetadatabrowser.model.MutableEntryRow;
import qupath.ext.projectmetadatabrowser.model.WorkingCopy;

/**
 * Parsed + matched representation of an import-wizard step 2 preview.
 * Reads a CSV / TSV / semicolon-separated file, auto-detects the separator,
 * lets the caller pick the identifier column, and produces the
 * {@link BulkSetCellsCommand.CellDelta} list the wizard's Apply step hands
 * to the working copy.
 *
 * <p>Per-row state enumerated as {@link RowState}. Counts are computed for
 * the full file (not just the previewed rows).
 */
public final class ImportPreviewModel {

    /** Detected row-vs-project state for the preview table. */
    public enum RowState {
        /** Match found, at least one value in the file row differs from the project. */
        UPDATE,
        /** Match found, every value in the file row equals the current project value. */
        UNCHANGED,
        /** Match found, file introduces values for keys the project doesn't have. */
        ADD,
        /** No project entry matches this file row's identifier. */
        NO_MATCH
    }

    /** One row in the preview. */
    public record PreviewRow(RowState state,
                              String fileIdentifierValue,
                              String projectEntryName,
                              List<String> dataValues,
                              String entryId) {
        public PreviewRow {
            Objects.requireNonNull(state, "state");
            dataValues = List.copyOf(Objects.requireNonNull(dataValues, "dataValues"));
        }
    }

    /** Per-state counts across the full file. */
    public record Counts(int update, int unchanged, int add, int noMatch, int entriesMissingFromFile) {}

    private final char separator;
    private final List<String> headers;
    private final List<List<String>> dataRows;

    private String identifierHeader;
    private List<PreviewRow> previewRows = List.of();
    private Counts counts = new Counts(0, 0, 0, 0, 0);
    private List<BulkSetCellsCommand.CellDelta> pendingDeltas = List.of();
    private List<String> newColumns = List.of();

    private ImportPreviewModel(char separator, List<String> headers, List<List<String>> dataRows) {
        this.separator = separator;
        this.headers = List.copyOf(headers);
        this.dataRows = dataRows.stream().map(List::copyOf).toList();
    }

    /**
     * Read and parse {@code path}. If {@code overrideSeparator} is null,
     * auto-detect (comma / tab / semicolon -- whichever yields the most
     * consistent column count over the first 5 data lines). Throws
     * {@link IOException} if the file is missing or unreadable.
     */
    public static ImportPreviewModel read(Path path, Character overrideSeparator) throws IOException {
        Objects.requireNonNull(path, "path");
        List<String> lines;
        try (BufferedReader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            lines = new ArrayList<>();
            String line;
            while ((line = r.readLine()) != null) {
                lines.add(line);
            }
        }
        if (lines.isEmpty())
            throw new IOException("File contains no data rows.");

        char sep = overrideSeparator != null ? overrideSeparator : detectSeparator(lines);
        List<String> headers = parseLine(lines.get(0), sep);
        List<List<String>> data = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isEmpty())
                continue;
            data.add(parseLine(line, sep));
        }
        return new ImportPreviewModel(sep, headers, data);
    }

    /** Auto-detected separator (',' / '\t' / ';'). */
    public char getSeparator() {
        return separator;
    }

    public List<String> getHeaders() {
        return headers;
    }

    public int dataRowCount() {
        return dataRows.size();
    }

    /**
     * Compute the preview against {@code workingCopy} matching on
     * {@code identifierHeader}. Resets {@link #getPreviewRows} /
     * {@link #getCounts} / {@link #getPendingDeltas} / {@link #getNewColumns}.
     */
    public void computePreview(WorkingCopy workingCopy, String identifierHeader) {
        Objects.requireNonNull(workingCopy, "workingCopy");
        Objects.requireNonNull(identifierHeader, "identifierHeader");
        this.identifierHeader = identifierHeader;
        int idIdx = headers.indexOf(identifierHeader);
        if (idIdx < 0) {
            previewRows = List.of();
            counts = new Counts(0, 0, 0, 0, 0);
            pendingDeltas = List.of();
            newColumns = List.of();
            return;
        }
        // Build entries by identifier value (Image ID or Image Name).
        Map<String, MutableEntryRow> byIdentifier = new HashMap<>();
        for (MutableEntryRow row : workingCopy.getRows()) {
            String key = identifierValueFor(row, identifierHeader);
            if (key != null && !key.isEmpty())
                byIdentifier.put(key, row);
        }
        // Find which header indexes are "data" (not the identifier).
        List<Integer> dataIdx = new ArrayList<>();
        List<String> dataHeaders = new ArrayList<>();
        for (int j = 0; j < headers.size(); j++) {
            if (j == idIdx) continue;
            dataIdx.add(j);
            dataHeaders.add(headers.get(j));
        }
        Set<String> existingColumns = new LinkedHashSet<>(workingCopy.getColumnKeys());
        LinkedHashSet<String> introduced = new LinkedHashSet<>();
        for (String header : dataHeaders) {
            if (!existingColumns.contains(header))
                introduced.add(header);
        }
        this.newColumns = new ArrayList<>(introduced);

        Set<String> matchedEntryIds = new LinkedHashSet<>();
        int update = 0, unchanged = 0, add = 0, noMatch = 0;
        List<PreviewRow> rows = new ArrayList<>();
        List<BulkSetCellsCommand.CellDelta> deltas = new ArrayList<>();
        for (List<String> file : dataRows) {
            if (file.size() <= idIdx) {
                // Malformed row -- treat as NO_MATCH with empty values.
                rows.add(new PreviewRow(RowState.NO_MATCH, "", "(empty row)",
                        emptyValues(dataIdx.size()), null));
                noMatch++;
                continue;
            }
            String fileId = file.get(idIdx);
            MutableEntryRow row = byIdentifier.get(fileId);
            List<String> dataValues = new ArrayList<>();
            for (int j : dataIdx) {
                dataValues.add(j < file.size() ? file.get(j) : "");
            }
            if (row == null) {
                rows.add(new PreviewRow(RowState.NO_MATCH, fileId, "(no project entry)",
                        dataValues, null));
                noMatch++;
                continue;
            }
            matchedEntryIds.add(row.getId());
            // Categorize: ADD (only new columns are populated), UPDATE (an
            // existing column changes value), UNCHANGED (no deltas).
            boolean addedNew = false;
            boolean changedExisting = false;
            for (int k = 0; k < dataHeaders.size(); k++) {
                String header = dataHeaders.get(k);
                String fileValue = dataValues.get(k);
                String currentValue = row.getMetadata(header);
                boolean diff = !Objects.equals(nullSafe(fileValue), nullSafe(currentValue));
                if (diff) {
                    if (introduced.contains(header)) {
                        if (fileValue != null && !fileValue.isEmpty())
                            addedNew = true;
                    } else {
                        changedExisting = true;
                    }
                    deltas.add(new BulkSetCellsCommand.CellDelta(
                            row.getId(), header, currentValue, fileValue));
                }
            }
            RowState state;
            if (!changedExisting && !addedNew)
                state = RowState.UNCHANGED;
            else if (addedNew && !changedExisting)
                state = RowState.ADD;
            else
                state = RowState.UPDATE;
            switch (state) {
                case UNCHANGED -> unchanged++;
                case ADD -> add++;
                case UPDATE -> update++;
                default -> {
                    // unreachable
                }
            }
            rows.add(new PreviewRow(state, fileId, row.getName(), dataValues, row.getId()));
        }
        int missingFromFile = 0;
        for (MutableEntryRow row : workingCopy.getRows()) {
            if (!matchedEntryIds.contains(row.getId()))
                missingFromFile++;
        }
        previewRows = rows;
        counts = new Counts(update, unchanged, add, noMatch, missingFromFile);
        pendingDeltas = deltas;
    }

    public List<PreviewRow> getPreviewRows() {
        return previewRows;
    }

    /** Cap-by helper for the visible preview slice (first {@code max} rows). */
    public List<PreviewRow> getPreviewSlice(int max) {
        if (previewRows.size() <= max)
            return previewRows;
        return previewRows.subList(0, max);
    }

    public Counts getCounts() {
        return counts;
    }

    public String getIdentifierHeader() {
        return identifierHeader;
    }

    /** Data column headers (excluding the identifier). */
    public List<String> getDataHeaders() {
        List<String> out = new ArrayList<>();
        if (identifierHeader == null)
            return out;
        for (String h : headers)
            if (!h.equals(identifierHeader))
                out.add(h);
        return out;
    }

    /** Deltas the Apply step will package into the ImportCommand. */
    public List<BulkSetCellsCommand.CellDelta> getPendingDeltas() {
        return pendingDeltas;
    }

    /** Columns the Apply step will introduce. */
    public List<String> getNewColumns() {
        return newColumns;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static List<String> emptyValues(int n) {
        List<String> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add("");
        return out;
    }

    private static String identifierValueFor(MutableEntryRow row, String header) {
        if (header == null)
            return "";
        // Accept the canonical column names and the "Image X" aliases the
        // template exporter emits.
        String norm = header.trim();
        if (norm.equalsIgnoreCase(MutableEntryRow.COL_ID) || norm.equalsIgnoreCase("Image ID"))
            return row.getId();
        if (norm.equalsIgnoreCase(MutableEntryRow.COL_NAME) || norm.equalsIgnoreCase("Image Name"))
            return row.getName();
        if (norm.equalsIgnoreCase(MutableEntryRow.COL_URI))
            return row.getUri();
        if (norm.equalsIgnoreCase(MutableEntryRow.COL_DESCRIPTION))
            return row.getDescription();
        if (norm.equalsIgnoreCase(MutableEntryRow.COL_TAGS))
            return row.getTags();
        return row.getMetadata(header);
    }

    /**
     * Split {@code line} by {@code sep}, with RFC 4180-style quote handling
     * when {@code sep} is a comma. Tab / semicolon use a simple split (no
     * quote handling -- Excel TSV / EU-Excel CSV-with-semicolons workflows
     * do not quote in practice).
     */
    private static List<String> parseLine(String line, char sep) {
        if (sep == ',')
            return parseCsvLine(line);
        List<String> out = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) == sep) {
                out.add(line.substring(start, i));
                start = i + 1;
            }
        }
        out.add(line.substring(start));
        return out;
    }

    private static List<String> parseCsvLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean inQuotes = false;
        int i = 0;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cell.append('"');
                        i += 2;
                        continue;
                    }
                    inQuotes = false;
                    i++;
                    continue;
                }
                cell.append(c);
                i++;
            } else {
                if (c == ',') {
                    out.add(cell.toString());
                    cell.setLength(0);
                    i++;
                    continue;
                }
                if (c == '"' && cell.length() == 0) {
                    inQuotes = true;
                    i++;
                    continue;
                }
                cell.append(c);
                i++;
            }
        }
        out.add(cell.toString());
        return out;
    }

    private static char detectSeparator(List<String> lines) {
        char[] candidates = {',', '\t', ';'};
        int sampleSize = Math.min(5, lines.size());
        char best = ',';
        int bestScore = -1;
        for (char c : candidates) {
            Map<Integer, Integer> hist = new LinkedHashMap<>();
            for (int i = 0; i < sampleSize; i++) {
                int cols = countOccurrences(lines.get(i), c) + 1;
                hist.merge(cols, 1, Integer::sum);
            }
            int score = 0;
            for (Map.Entry<Integer, Integer> e : hist.entrySet()) {
                if (e.getKey() > 1)
                    score = Math.max(score, e.getValue() * e.getKey());
            }
            if (score > bestScore) {
                bestScore = score;
                best = c;
            }
        }
        return best;
    }

    private static int countOccurrences(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++)
            if (s.charAt(i) == c) n++;
        return n;
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
