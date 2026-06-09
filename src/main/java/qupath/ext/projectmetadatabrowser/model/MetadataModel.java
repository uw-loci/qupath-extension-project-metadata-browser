package qupath.ext.projectmetadatabrowser.model;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

/**
 * View over a QuPath project as a {@link WorkingCopy} of editable entries
 * plus a per-key {@link MetadataKeyRow} list with usage counts and sample
 * values for the Metadata Keys tab.
 *
 * <p>v1.1 reshape: rows are sourced from the {@link WorkingCopy} the UI
 * mutates buffered-editor style. Usage counts and sample values are
 * derived from the working copy on every reload so they stay consistent
 * with the table view.
 */
public class MetadataModel {

    private final WorkingCopy workingCopy = new WorkingCopy();
    private final ObservableList<MetadataKeyRow> keyRows = FXCollections.observableArrayList();

    /**
     * Replace the contents of this model from {@code project}. Safe to call
     * on the FX thread; O(n) in the number of entries. Discards any prior
     * dirty state on the working copy.
     */
    public void loadFrom(Project<BufferedImage> project) {
        workingCopy.loadFrom(project);
        rebuildKeyRows();
    }

    /**
     * Rebuild the per-key rows from the live working-copy state. Called by
     * {@link #loadFrom} and by the browser window after every undo/redo or
     * command application so the Metadata Keys tab stays current.
     */
    public void rebuildKeyRows() {
        keyRows.clear();
        TreeSet<String> keys = new TreeSet<>(workingCopy.getColumnKeys());
        Map<String, Integer> usage = new LinkedHashMap<>();
        Map<String, String> sampleValue = new LinkedHashMap<>();
        Map<String, String> sampleEntryName = new LinkedHashMap<>();
        for (MutableEntryRow row : workingCopy.getRows()) {
            Map<String, String> md = row.snapshotWorking();
            String name = row.getName();
            for (Map.Entry<String, String> e : md.entrySet()) {
                String k = e.getKey();
                String v = e.getValue();
                if (k == null)
                    continue;
                usage.merge(k, 1, Integer::sum);
                if (!sampleValue.containsKey(k)) {
                    sampleValue.put(k, v == null ? "" : v);
                    sampleEntryName.put(k, name == null ? "" : name);
                }
            }
        }
        List<MetadataKeyRow> newRows = new ArrayList<>(keys.size());
        for (String k : keys) {
            int count = usage.getOrDefault(k, 0);
            String v = sampleValue.getOrDefault(k, "");
            String n = sampleEntryName.getOrDefault(k, "");
            newRows.add(new MetadataKeyRow(k, count, v, n));
        }
        keyRows.setAll(newRows);
    }

    public WorkingCopy getWorkingCopy() {
        return workingCopy;
    }

    public ObservableList<MutableEntryRow> getRows() {
        return workingCopy.getRows();
    }

    public ObservableList<String> getMetadataKeys() {
        return workingCopy.getColumnKeys();
    }

    /**
     * Observable list of per-key rows for the Metadata Keys tab. Refreshed
     * by every call to {@link #loadFrom(Project)} or {@link #rebuildKeyRows()}.
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
        Objects.requireNonNull(entries, "entries");
        TreeSet<String> keys = new TreeSet<>();
        for (ProjectImageEntry<BufferedImage> entry : entries) {
            keys.addAll(entry.getMetadata().keySet());
        }
        return Collections.unmodifiableList(new ArrayList<>(keys));
    }
}
