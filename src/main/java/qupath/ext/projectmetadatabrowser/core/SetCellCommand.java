package qupath.ext.projectmetadatabrowser.core;

import java.util.Objects;

import qupath.ext.projectmetadatabrowser.model.MutableEntryRow;
import qupath.ext.projectmetadatabrowser.model.WorkingCopy;

/**
 * Single-cell edit. Records (entryId, key, oldValue, newValue) so that undo
 * restores the cell to its pre-edit value even if the working copy has
 * since been mutated by other commands.
 *
 * <p>A null or empty {@code newValue} removes the key from the row. A null
 * or empty {@code oldValue} treats the key as absent on undo (the key is
 * removed). The key is added to the working-copy column key list on apply
 * if it is not already there -- a SetCell on a brand new column is
 * conceptually a column-add too, but the {@link AddColumnCommand} is the
 * explicit form preferred by the UI for that operation.
 */
public final class SetCellCommand implements MetadataCommand {

    private final String entryId;
    private final String key;
    private final String oldValue;
    private final String newValue;

    public SetCellCommand(String entryId, String key, String oldValue, String newValue) {
        this.entryId = Objects.requireNonNull(entryId, "entryId");
        this.key = Objects.requireNonNull(key, "key");
        this.oldValue = oldValue;
        this.newValue = newValue;
    }

    @Override
    public void apply(WorkingCopy wc) {
        MutableEntryRow row = wc.getRowById(entryId);
        if (row == null)
            return;
        wc.addColumn(key);
        if (newValue == null || newValue.isEmpty())
            row.removeWorkingKey(key);
        else
            row.putWorkingValue(key, newValue);
    }

    @Override
    public void undo(WorkingCopy wc) {
        MutableEntryRow row = wc.getRowById(entryId);
        if (row == null)
            return;
        if (oldValue == null || oldValue.isEmpty())
            row.removeWorkingKey(key);
        else
            row.putWorkingValue(key, oldValue);
    }

    @Override
    public String description() {
        return "Edit '" + key + "' on entry";
    }

    public String getEntryId() {
        return entryId;
    }

    public String getKey() {
        return key;
    }

    public String getOldValue() {
        return oldValue;
    }

    public String getNewValue() {
        return newValue;
    }
}
