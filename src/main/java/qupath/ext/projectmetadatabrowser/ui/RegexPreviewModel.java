package qupath.ext.projectmetadatabrowser.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import qupath.ext.projectmetadatabrowser.model.MutableEntryRow;

/**
 * Reactive backing model for the Regex extraction dialog's live preview.
 * Owns: the compiled {@link Pattern} (or the most recent
 * {@link PatternSyntaxException} message), the detected named-group list, the
 * preview rows (capped at 50), and aggregate match counts over the full
 * working copy.
 */
public final class RegexPreviewModel {

    /** One row of preview data. */
    public record PreviewRow(String entryId,
                              String entryName,
                              String sourceValue,
                              boolean matched,
                              Map<String, String> groupValues) {
        public PreviewRow {
            Objects.requireNonNull(entryId, "entryId");
            groupValues = Map.copyOf(Objects.requireNonNull(groupValues, "groupValues"));
        }
    }

    private static final int PREVIEW_CAP = 50;
    private static final Pattern NAMED_GROUP_DECL = Pattern.compile("\\(\\?<([A-Za-z][A-Za-z0-9]*)>");

    private Pattern compiled;
    private String compileError;
    private List<String> groupNames = List.of();
    private List<PreviewRow> previewRows = List.of();
    private int totalMatched;
    private int totalUnmatched;

    public void update(String regex, List<MutableEntryRow> rows, java.util.function.Function<MutableEntryRow, String> sourceResolver) {
        Objects.requireNonNull(sourceResolver, "sourceResolver");
        compiled = null;
        compileError = null;
        groupNames = List.of();
        previewRows = List.of();
        totalMatched = 0;
        totalUnmatched = 0;
        if (regex == null || regex.isEmpty())
            return;
        try {
            compiled = Pattern.compile(regex);
        } catch (PatternSyntaxException ex) {
            compileError = ex.getDescription();
            return;
        }
        groupNames = parseNamedGroups(regex);
        if (groupNames.isEmpty())
            return;
        List<PreviewRow> preview = new ArrayList<>();
        int matched = 0;
        int unmatched = 0;
        for (MutableEntryRow row : rows) {
            String src = sourceResolver.apply(row);
            String safeSrc = src == null ? "" : src;
            Matcher m = compiled.matcher(safeSrc);
            boolean ok = m.find();
            Map<String, String> values = new LinkedHashMap<>();
            if (ok) {
                for (String name : groupNames) {
                    String val;
                    try {
                        val = m.group(name);
                    } catch (IllegalArgumentException | IllegalStateException ex) {
                        val = null;
                    }
                    values.put(name, val == null ? "" : val);
                }
                matched++;
            } else {
                for (String name : groupNames)
                    values.put(name, "");
                unmatched++;
            }
            if (preview.size() < PREVIEW_CAP)
                preview.add(new PreviewRow(row.getId(), row.getName(), safeSrc, ok, values));
        }
        totalMatched = matched;
        totalUnmatched = unmatched;
        previewRows = preview;
    }

    public Pattern getCompiled() {
        return compiled;
    }

    /** Short compile error message, or null when the pattern compiled. */
    public String getCompileError() {
        return compileError;
    }

    public List<String> getGroupNames() {
        return groupNames;
    }

    public List<PreviewRow> getPreviewRows() {
        return Collections.unmodifiableList(previewRows);
    }

    public int getTotalMatched() {
        return totalMatched;
    }

    public int getTotalUnmatched() {
        return totalUnmatched;
    }

    /**
     * Parse declared named groups from the source regex in left-to-right
     * order. Best-effort -- {@link Pattern} does not surface declared group
     * names from the compiled pattern.
     */
    public static List<String> parseNamedGroups(String regex) {
        List<String> out = new ArrayList<>();
        if (regex == null)
            return out;
        Matcher m = NAMED_GROUP_DECL.matcher(regex);
        while (m.find()) {
            String name = m.group(1);
            if (!out.contains(name))
                out.add(name);
        }
        return out;
    }
}
