package qupath.ext.projectmetadatabrowser.model;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.lib.projects.ProjectImageEntry;

/**
 * One row in the metadata browser -- a snapshot view over a single
 * {@link ProjectImageEntry}. Values are read lazily via accessors so the
 * TableView can call them from cell value factories without holding extra
 * JavaFX properties in memory.
 */
public class EntryRow {

    private static final Logger logger = LoggerFactory.getLogger(EntryRow.class);

    public static final String COL_NAME = "Name";
    public static final String COL_ID = "ID";
    public static final String COL_URI = "URI";
    public static final String COL_DESCRIPTION = "Description";
    public static final String COL_TAGS = "Tags";

    private final ProjectImageEntry<BufferedImage> entry;

    public EntryRow(ProjectImageEntry<BufferedImage> entry) {
        this.entry = entry;
    }

    public ProjectImageEntry<BufferedImage> getEntry() {
        return entry;
    }

    public String getName() {
        return nullSafe(entry.getImageName());
    }

    public String getId() {
        return nullSafe(entry.getID());
    }

    public String getDescription() {
        return nullSafe(entry.getDescription());
    }

    public String getTags() {
        var tags = entry.getTags();
        if (tags == null || tags.isEmpty())
            return "";
        return String.join(", ", tags);
    }

    public String getUri() {
        try {
            Collection<URI> uris = entry.getURIs();
            if (uris == null || uris.isEmpty())
                return "";
            StringBuilder sb = new StringBuilder();
            for (URI u : uris) {
                if (sb.length() > 0)
                    sb.append("; ");
                sb.append(u.toString());
            }
            return sb.toString();
        } catch (IOException e) {
            logger.warn("Unable to read URIs for entry {}: {}", entry.getID(), e.getMessage());
            return "";
        }
    }

    /**
     * Value for a user-metadata column; empty string if the key is not present
     * on this entry.
     */
    public String getMetadata(String key) {
        String v = entry.getMetadata().get(key);
        return v == null ? "" : v;
    }

    /**
     * Value for any column -- handles both the built-in columns and the
     * dynamic user-metadata columns.
     */
    public String getValueForColumn(String column) {
        if (column == null)
            return "";
        switch (column) {
            case COL_NAME: return getName();
            case COL_ID: return getId();
            case COL_URI: return getUri();
            case COL_DESCRIPTION: return getDescription();
            case COL_TAGS: return getTags();
            default: return getMetadata(column);
        }
    }

    /**
     * Snapshot of the current user-metadata map for this entry. Used by the
     * edit dialog as the initial form state.
     */
    public Map<String, String> snapshotMetadata() {
        return new HashMap<>(entry.getMetadata());
    }

    /**
     * Apply a set of metadata changes. Keys with {@code null}, empty, or
     * whitespace-only values are removed. {@code null} keys are ignored.
     * Caller is responsible for calling {@code project.syncChanges()} once
     * after all rows have been updated.
     */
    public void applyMetadataChanges(Map<String, String> updates) {
        Map<String, String> md = entry.getMetadata();
        for (Map.Entry<String, String> e : updates.entrySet()) {
            String key = e.getKey();
            if (key == null)
                continue;
            String val = e.getValue();
            if (val == null || val.isBlank())
                md.remove(key);
            else
                md.put(key, val);
        }
    }

    /**
     * Replace this entry's metadata map wholesale with {@code snapshot}.
     * Used to roll back after a persistence failure.
     */
    public void restoreMetadata(Map<String, String> snapshot) {
        Map<String, String> md = entry.getMetadata();
        md.clear();
        if (snapshot != null)
            md.putAll(snapshot);
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
