package qupath.ext.projectmetadatabrowser.model;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.lib.projects.ProjectImageEntry;

/**
 * One row in the buffered metadata editor -- wraps a single
 * {@link ProjectImageEntry} for its read-only built-in fields (name, ID, URI,
 * description, tags) but holds an editable copy of the user-metadata map.
 *
 * <p>v1.1 replacement for the v0.2.0 {@code EntryRow}: cell value getters
 * read from the live {@code metadata} map, not from the underlying entry.
 * The {@code originalMetadata} snapshot is captured at construction so a
 * later Save can diff the working copy against on-disk state.
 *
 * <p>Mutations are performed by {@link qupath.ext.projectmetadatabrowser.core.MetadataCommand}
 * instances acting through {@link WorkingCopy}; this class deliberately does
 * not mutate the underlying {@link ProjectImageEntry}. That happens only at
 * Save time via
 * {@link qupath.ext.projectmetadatabrowser.core.MetadataKeyOperations#commitWorkingCopy}.
 */
public final class MutableEntryRow {

    private static final Logger logger = LoggerFactory.getLogger(MutableEntryRow.class);

    public static final String COL_NAME = "Name";
    public static final String COL_ID = "ID";
    public static final String COL_URI = "URI";
    public static final String COL_DESCRIPTION = "Description";
    public static final String COL_TAGS = "Tags";

    private final ProjectImageEntry<BufferedImage> entry;
    private final Map<String, String> metadata;
    private final Map<String, String> originalMetadata;

    /**
     * Snapshot the entry's user metadata into the working copy. The snapshot
     * is taken under {@code synchronized(getMetadata())} to defend against a
     * concurrent script writing the entry while the snapshot is taken.
     *
     * @param entry the project image entry to wrap; must not be null.
     */
    public MutableEntryRow(ProjectImageEntry<BufferedImage> entry) {
        this.entry = Objects.requireNonNull(entry, "entry");
        Map<String, String> src = entry.getMetadata();
        Map<String, String> copy;
        Map<String, String> orig;
        synchronized (src) {
            copy = new LinkedHashMap<>(src);
            orig = new HashMap<>(src);
        }
        this.metadata = copy;
        this.originalMetadata = orig;
    }

    /** The wrapped project image entry. Never null. */
    public ProjectImageEntry<BufferedImage> getEntry() {
        return entry;
    }

    /** Stable per-session identifier for this entry. */
    public String getId() {
        return nullSafe(entry.getID());
    }

    public String getName() {
        return nullSafe(entry.getImageName());
    }

    public String getDescription() {
        return nullSafe(entry.getDescription());
    }

    public String getTags() {
        var tags = entry.getTags();
        if (tags == null || tags.isEmpty())
            return "";
        return String.join(" | ", tags);
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
     * Value for a user-metadata column from the working copy. Empty string if
     * the key is not present. This is the value the TableView should render.
     */
    public String getMetadata(String key) {
        if (key == null)
            return "";
        String v = metadata.get(key);
        return v == null ? "" : v;
    }

    /**
     * True if the key is present in the working copy (including a value of
     * the empty string, which is distinct from "absent").
     */
    public boolean hasMetadata(String key) {
        return key != null && metadata.containsKey(key);
    }

    /**
     * Working-copy mutator -- set a single key. A null or empty {@code value}
     * removes the key. Called by {@link qupath.ext.projectmetadatabrowser.core.MetadataCommand}
     * implementations; UI code should not call this directly (the command +
     * working-copy path provides undo).
     */
    public void putWorkingValue(String key, String value) {
        if (key == null)
            return;
        if (value == null || value.isEmpty())
            metadata.remove(key);
        else
            metadata.put(key, value);
    }

    /**
     * Working-copy mutator -- remove a key. No-op when absent. Called by
     * {@link qupath.ext.projectmetadatabrowser.core.MetadataCommand}.
     */
    public void removeWorkingKey(String key) {
        if (key == null)
            return;
        metadata.remove(key);
    }

    /**
     * Defensive copy of the live working-copy map. Used by Save to compute
     * the diff against {@link #snapshotOriginal()}.
     */
    public Map<String, String> snapshotWorking() {
        return new HashMap<>(metadata);
    }

    /**
     * Defensive copy of the original-at-load map. Used by Save to compute
     * the diff against {@link #snapshotWorking()}.
     */
    public Map<String, String> snapshotOriginal() {
        return new HashMap<>(originalMetadata);
    }

    /**
     * Mark the current working-copy state as the new "original" -- called by
     * {@link WorkingCopy#markClean()} after a successful Save so the dirty
     * indicators clear.
     */
    public void markClean() {
        originalMetadata.clear();
        originalMetadata.putAll(metadata);
    }

    /**
     * True if a given cell value differs from the originally-loaded value.
     * Used by the TableView to drive the per-cell dirty visual style.
     */
    public boolean isCellDirty(String key) {
        if (key == null)
            return false;
        String current = metadata.get(key);
        String orig = originalMetadata.get(key);
        boolean curPresent = metadata.containsKey(key);
        boolean origPresent = originalMetadata.containsKey(key);
        if (!curPresent && !origPresent)
            return false;
        if (curPresent != origPresent)
            return true;
        return !Objects.equals(nullSafe(current), nullSafe(orig));
    }

    /** True if any key on this entry differs from its load-time snapshot. */
    public boolean isDirty() {
        if (metadata.size() != originalMetadata.size())
            return true;
        for (Map.Entry<String, String> e : metadata.entrySet()) {
            String orig = originalMetadata.get(e.getKey());
            if (!originalMetadata.containsKey(e.getKey()))
                return true;
            if (!Objects.equals(nullSafe(e.getValue()), nullSafe(orig)))
                return true;
        }
        return false;
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
