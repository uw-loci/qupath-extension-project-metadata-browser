package qupath.ext.projectmetadatabrowser.model;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

/**
 * In-memory view of a QuPath project as a set of {@link EntryRow} objects plus
 * the union of all user-metadata keys used anywhere in the project, with
 * per-key usage counts and sample values for the Metadata Keys tab.
 */
public class MetadataModel {

    private final ObservableList<EntryRow> rows = FXCollections.observableArrayList();
    private final ObservableList<String> metadataKeys = FXCollections.observableArrayList();
    private final ObservableList<MetadataKeyRow> keyRows = FXCollections.observableArrayList();

    /**
     * Replace the contents of this model with entries read from {@code project}.
     * Safe to call on the FX thread; O(n) in the number of entries.
     *
     * <p>Builds three observable views in a single pass over the project:
     * the {@link EntryRow} list, the sorted union of metadata keys, and the
     * per-key {@link MetadataKeyRow} list with usage counts and sample
     * values. The single-pass build avoids a second iteration for the
     * Metadata Keys tab.
     */
    public void loadFrom(Project<BufferedImage> project) {
        rows.clear();
        metadataKeys.clear();
        keyRows.clear();
        if (project == null)
            return;

        List<EntryRow> newRows = new ArrayList<>();
        TreeSet<String> keys = new TreeSet<>();
        // Use ordered maps so iteration order matches discovery order on the
        // first entry that sets each key -- this is what defines "the sample
        // came from this entry" for the Metadata Keys tab.
        Map<String, Integer> usage = new LinkedHashMap<>();
        Map<String, String> sampleValue = new LinkedHashMap<>();
        Map<String, String> sampleEntryName = new LinkedHashMap<>();

        for (ProjectImageEntry<BufferedImage> entry : project.getImageList()) {
            newRows.add(new EntryRow(entry));
            Map<String, String> md = entry.getMetadata();
            for (Map.Entry<String, String> e : md.entrySet()) {
                String k = e.getKey();
                if (k == null)
                    continue;
                String v = e.getValue();
                keys.add(k);
                usage.merge(k, 1, Integer::sum);
                if (!sampleValue.containsKey(k)) {
                    sampleValue.put(k, v == null ? "" : v);
                    String name = entry.getImageName();
                    sampleEntryName.put(k, name == null ? "" : name);
                }
            }
        }
        rows.setAll(newRows);
        metadataKeys.setAll(keys);

        // Build keyRows in sorted-key order so the table's default sort matches
        // the natural alphabetical view of the keys.
        List<MetadataKeyRow> newKeyRows = new ArrayList<>(keys.size());
        for (String k : keys) {
            int count = usage.getOrDefault(k, 0);
            String v = sampleValue.getOrDefault(k, "");
            String name = sampleEntryName.getOrDefault(k, "");
            newKeyRows.add(new MetadataKeyRow(k, count, v, name));
        }
        keyRows.setAll(newKeyRows);
    }

    public ObservableList<EntryRow> getRows() {
        return rows;
    }

    public ObservableList<String> getMetadataKeys() {
        return metadataKeys;
    }

    /**
     * Observable list of per-key rows for the Metadata Keys tab. Refreshed
     * by every call to {@link #loadFrom(Project)}.
     */
    public ObservableList<MetadataKeyRow> getKeyRows() {
        return keyRows;
    }

    /**
     * Per-key usage counts as a defensive copy. Useful for callers that
     * want a snapshot independent of the observable {@link #getKeyRows()}
     * view.
     */
    public Map<String, Integer> keyUsageCounts() {
        Map<String, Integer> out = new TreeMap<>();
        for (MetadataKeyRow r : keyRows)
            out.put(r.getKey(), r.getEntryCount());
        return out;
    }

    /**
     * Per-key sample values, as {@code [sampleValue, sampleEntryName]}.
     * Defensive copy.
     */
    public Map<String, String[]> sampleValuesByKey() {
        Map<String, String[]> out = new TreeMap<>();
        for (MetadataKeyRow r : keyRows)
            out.put(r.getKey(), new String[] { r.getSampleValue(), r.getSampleEntryName() });
        return out;
    }

    /**
     * Utility exposed for tests: compute the sorted union of metadata keys
     * across a collection of entries without mutating model state.
     */
    public static List<String> unionMetadataKeys(Iterable<ProjectImageEntry<BufferedImage>> entries) {
        TreeSet<String> keys = new TreeSet<>();
        for (ProjectImageEntry<BufferedImage> entry : entries) {
            keys.addAll(entry.getMetadata().keySet());
        }
        return Collections.unmodifiableList(new ArrayList<>(keys));
    }
}
