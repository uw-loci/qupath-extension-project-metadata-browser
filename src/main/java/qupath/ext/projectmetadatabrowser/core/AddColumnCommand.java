package qupath.ext.projectmetadatabrowser.core;

import java.util.Objects;

import qupath.ext.projectmetadatabrowser.model.WorkingCopy;

/**
 * Add a new user-metadata key as a column. Empty values everywhere; no row
 * data is changed. Used by the Edit menu "Add column..." item.
 *
 * <p>Undo removes the column from the working copy's key list. Per-row
 * values that may have been added between apply and undo (via a follow-on
 * {@link SetCellCommand}) are not cleared by this undo -- those edits are
 * separate commands on the undo stack and reverse independently.
 */
public final class AddColumnCommand implements MetadataCommand {

    private final String key;
    /** Was the column already present at apply time? If so, undo is a no-op. */
    private boolean wasAlreadyPresent;

    public AddColumnCommand(String key) {
        this.key = Objects.requireNonNull(key, "key");
    }

    @Override
    public void apply(WorkingCopy wc) {
        wasAlreadyPresent = wc.getColumnKeys().contains(key);
        wc.addColumn(key);
    }

    @Override
    public void undo(WorkingCopy wc) {
        if (wasAlreadyPresent)
            return;
        wc.removeColumn(key);
    }

    @Override
    public String description() {
        return "Add column '" + key + "'";
    }

    public String getKey() {
        return key;
    }
}
