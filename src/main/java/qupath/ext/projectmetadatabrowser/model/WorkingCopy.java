package qupath.ext.projectmetadatabrowser.model;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyIntegerWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

/**
 * Top-level buffered editor state -- holds one {@link MutableEntryRow} per
 * project entry, the observable set of metadata column keys, and a count of
 * tick events used by the UI to drive a refresh.
 *
 * <p>{@link qupath.ext.projectmetadatabrowser.core.MetadataCommand} instances
 * mutate this state via {@link #applyCommand} (used by the undo stack);
 * mutators called outside the command path (e.g. {@link #addColumn}) are the
 * commands' building blocks.
 *
 * <p>The working copy does <em>not</em> mutate the underlying
 * {@link ProjectImageEntry}. That happens only at Save time via
 * {@link qupath.ext.projectmetadatabrowser.core.MetadataKeyOperations#commitWorkingCopy}.
 */
public final class WorkingCopy {

    private final ObservableList<MutableEntryRow> rows = FXCollections.observableArrayList();
    private final ObservableList<String> columnKeys = FXCollections.observableArrayList();

    /** Snapshot of the columnKeys at load time -- used for diff at Save. */
    private final Set<String> originalColumnKeys = new HashSet<>();

    /** Index of entryId -> row, refreshed by load. */
    private final Map<String, MutableEntryRow> byId = new HashMap<>();

    /** Read-only dirty flag derived from per-entry + column-key dirty state. */
    private final ReadOnlyBooleanWrapper dirty = new ReadOnlyBooleanWrapper(false);

    /**
     * Monotonically increasing "tick" that the UI listens to so it can
     * trigger a full table refresh after any mutation. The TableView's cell
     * value factories read from {@link MutableEntryRow} directly, so a
     * coarse refresh is the simplest way to repaint values + dirty styles
     * after arbitrary command application.
     */
    private final ReadOnlyIntegerWrapper tick = new ReadOnlyIntegerWrapper(0);

    /** Internal serial guard so command application and Save don't race. */
    private final Object commandMonitor = new Object();

    /**
     * Replace every row from {@code project}. Snapshots each entry's metadata
     * eagerly into a {@link MutableEntryRow} and rebuilds the column-key
     * union. Discards any prior dirty state.
     */
    public void loadFrom(Project<BufferedImage> project) {
        synchronized (commandMonitor) {
            rows.clear();
            byId.clear();
            columnKeys.clear();
            originalColumnKeys.clear();
            if (project == null) {
                dirty.set(false);
                tick.set(tick.get() + 1);
                return;
            }
            List<MutableEntryRow> newRows = new ArrayList<>();
            TreeSet<String> keys = new TreeSet<>();
            for (ProjectImageEntry<BufferedImage> entry : project.getImageList()) {
                MutableEntryRow row = new MutableEntryRow(entry);
                newRows.add(row);
                byId.put(row.getId(), row);
                Map<String, String> md = entry.getMetadata();
                synchronized (md) {
                    for (String key : md.keySet()) {
                        if (key != null)
                            keys.add(key);
                    }
                }
            }
            rows.setAll(newRows);
            columnKeys.setAll(keys);
            originalColumnKeys.addAll(keys);
            dirty.set(false);
            tick.set(tick.get() + 1);
        }
    }

    /**
     * The observable list of rows backing the Entries TableView. Insertion
     * order matches {@code project.getImageList()}.
     */
    public ObservableList<MutableEntryRow> getRows() {
        return rows;
    }

    /**
     * The observable list of user-metadata column keys (alphabetical at
     * load; new columns added at the end as they are introduced).
     */
    public ObservableList<String> getColumnKeys() {
        return columnKeys;
    }

    /**
     * Look up a row by its {@link MutableEntryRow#getId()} value, or null if
     * none. Used by commands to map per-entry deltas back into row mutations.
     */
    public MutableEntryRow getRowById(String id) {
        return id == null ? null : byId.get(id);
    }

    public ReadOnlyBooleanProperty dirtyProperty() {
        return dirty.getReadOnlyProperty();
    }

    /**
     * Refresh-tick property; increments whenever the working copy has been
     * mutated. UI listeners use this to redraw cells and dirty indicators
     * without inspecting individual cells.
     */
    public ReadOnlyIntegerProperty tickProperty() {
        return tick.getReadOnlyProperty();
    }

    /** True if any row's cell value or the column-key set differs from load. */
    public boolean isDirty() {
        return dirty.get();
    }

    /**
     * Apply a command. Pushes nothing on the undo stack -- callers wire
     * commands into {@link qupath.ext.projectmetadatabrowser.core.UndoStack}
     * explicitly.
     */
    public void applyCommand(qupath.ext.projectmetadatabrowser.core.MetadataCommand command) {
        Objects.requireNonNull(command, "command");
        synchronized (commandMonitor) {
            command.apply(this);
            recomputeDirty();
            tick.set(tick.get() + 1);
        }
    }

    /**
     * Reverse a command. Mirrors {@link #applyCommand}.
     */
    public void undoCommand(qupath.ext.projectmetadatabrowser.core.MetadataCommand command) {
        Objects.requireNonNull(command, "command");
        synchronized (commandMonitor) {
            command.undo(this);
            recomputeDirty();
            tick.set(tick.get() + 1);
        }
    }

    /**
     * Add a new column key. Mutator -- called from commands. No-op if the
     * key already exists.
     */
    public void addColumn(String key) {
        if (key == null || key.isBlank())
            return;
        if (!columnKeys.contains(key))
            columnKeys.add(key);
    }

    /**
     * Remove a column key. Mutator -- called from commands. Does NOT remove
     * the value from each row's working-copy map; the caller must combine
     * with per-row removeWorkingKey calls if it wants both effects.
     */
    public void removeColumn(String key) {
        if (key == null)
            return;
        columnKeys.remove(key);
    }

    /**
     * Diff every row against its load-time snapshot, returning the deltas
     * Save will apply. Read-only -- no mutation. The returned list is empty
     * when {@link #isDirty()} is false.
     */
    public List<EntryDiff> diff() {
        List<EntryDiff> out = new ArrayList<>();
        synchronized (commandMonitor) {
            for (MutableEntryRow row : rows) {
                if (!row.isDirty())
                    continue;
                Map<String, String> live = row.snapshotWorking();
                Map<String, String> orig = row.snapshotOriginal();
                Map<String, String> toSet = new LinkedHashMap<>();
                Set<String> toRemove = new HashSet<>();
                for (Map.Entry<String, String> e : live.entrySet()) {
                    String key = e.getKey();
                    String val = e.getValue();
                    String origVal = orig.get(key);
                    if (!orig.containsKey(key)) {
                        if (val != null && !val.isEmpty())
                            toSet.put(key, val);
                    } else if (!Objects.equals(val, origVal)) {
                        if (val == null || val.isEmpty())
                            toRemove.add(key);
                        else
                            toSet.put(key, val);
                    }
                }
                for (String origKey : orig.keySet()) {
                    if (!live.containsKey(origKey))
                        toRemove.add(origKey);
                }
                if (!toSet.isEmpty() || !toRemove.isEmpty())
                    out.add(new EntryDiff(row.getId(), toSet, toRemove));
            }
        }
        return out;
    }

    /**
     * Promote every row's current working state to its new "original" --
     * invoked by the save path after a successful commit so the UI dirty
     * indicators clear without throwing away undo history.
     */
    public void markClean() {
        synchronized (commandMonitor) {
            for (MutableEntryRow row : rows)
                row.markClean();
            originalColumnKeys.clear();
            originalColumnKeys.addAll(columnKeys);
            recomputeDirty();
            tick.set(tick.get() + 1);
        }
    }

    /** Number of unsaved deltas (used to drive the menu / status counters). */
    public int unsavedChangeCount() {
        int n = 0;
        for (EntryDiff d : diff()) {
            n += d.toSet().size();
            n += d.toRemove().size();
        }
        return n;
    }

    /** Test-only convenience: every entry's working-copy metadata map. */
    public Map<String, Map<String, String>> debugWorkingState() {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        for (MutableEntryRow row : rows)
            out.put(row.getId(), row.snapshotWorking());
        return Collections.unmodifiableMap(out);
    }

    private void recomputeDirty() {
        boolean anyDirty = false;
        for (MutableEntryRow row : rows) {
            if (row.isDirty()) {
                anyDirty = true;
                break;
            }
        }
        if (!anyDirty) {
            // Column-key add/remove without per-row delta is also dirty
            // (e.g. user added an empty column and saved nothing into it).
            if (!sameKeySet(columnKeys, originalColumnKeys))
                anyDirty = true;
        }
        dirty.set(anyDirty);
    }

    private static boolean sameKeySet(List<String> live, Set<String> orig) {
        if (live.size() != orig.size())
            return false;
        for (String k : live)
            if (!orig.contains(k))
                return false;
        return true;
    }

    /**
     * Per-entry diff carried from {@link #diff()} into
     * {@link qupath.ext.projectmetadatabrowser.core.MetadataKeyOperations#commitWorkingCopy}.
     *
     * @param entryId stable per-session entry identifier; matches
     *                {@link ProjectImageEntry#getID()}.
     * @param toSet metadata keys to write (with their values).
     * @param toRemove metadata keys to remove from the entry.
     */
    public record EntryDiff(String entryId, Map<String, String> toSet, Set<String> toRemove) {
        public EntryDiff {
            Objects.requireNonNull(entryId, "entryId");
            toSet = Map.copyOf(toSet);
            toRemove = Set.copyOf(toRemove);
        }
    }
}
