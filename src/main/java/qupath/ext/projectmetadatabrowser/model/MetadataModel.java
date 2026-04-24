package qupath.ext.projectmetadatabrowser.model;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

/**
 * In-memory view of a QuPath project as a set of {@link EntryRow} objects plus
 * the union of all user-metadata keys used anywhere in the project.
 */
public class MetadataModel {

    private final ObservableList<EntryRow> rows = FXCollections.observableArrayList();
    private final ObservableList<String> metadataKeys = FXCollections.observableArrayList();

    /**
     * Replace the contents of this model with entries read from {@code project}.
     * Safe to call on the FX thread; O(n) in the number of entries.
     */
    public void loadFrom(Project<BufferedImage> project) {
        rows.clear();
        metadataKeys.clear();
        if (project == null)
            return;

        List<EntryRow> newRows = new ArrayList<>();
        TreeSet<String> keys = new TreeSet<>();
        for (ProjectImageEntry<BufferedImage> entry : project.getImageList()) {
            newRows.add(new EntryRow(entry));
            keys.addAll(entry.getMetadata().keySet());
        }
        rows.setAll(newRows);
        metadataKeys.setAll(keys);
    }

    public ObservableList<EntryRow> getRows() {
        return rows;
    }

    public ObservableList<String> getMetadataKeys() {
        return metadataKeys;
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
