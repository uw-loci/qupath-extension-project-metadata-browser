package qupath.ext.projectmetadatabrowser.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import qupath.ext.projectmetadatabrowser.model.MutableEntryRow;
import qupath.ext.projectmetadatabrowser.model.WorkingCopy;

/**
 * Remove a user-metadata key from every entry in the working copy plus the
 * column-key list. Records each entry's pre-remove value so undo can
 * restore them.
 *
 * <p>Used by the Metadata Keys tab Delete action. Replaces the v0.2.0
 * "immediate sync" path -- the actual {@code project.syncChanges()} is
 * deferred until Save.
 */
public final class RemoveColumnCommand implements MetadataCommand {

    private final String key;
    /** entryId -> pre-remove value. Captured at apply time. */
    private final Map<String, String> capturedValues = new LinkedHashMap<>();
    private boolean wasColumnPresent;

    public RemoveColumnCommand(String key) {
        this.key = Objects.requireNonNull(key, "key");
    }

    @Override
    public void apply(WorkingCopy wc) {
        wasColumnPresent = wc.getColumnKeys().contains(key);
        capturedValues.clear();
        for (MutableEntryRow row : wc.getRows()) {
            if (row.hasMetadata(key)) {
                capturedValues.put(row.getId(), row.getMetadata(key));
                row.removeWorkingKey(key);
            }
        }
        wc.removeColumn(key);
    }

    @Override
    public void undo(WorkingCopy wc) {
        if (wasColumnPresent)
            wc.addColumn(key);
        for (Map.Entry<String, String> e : capturedValues.entrySet()) {
            MutableEntryRow row = wc.getRowById(e.getKey());
            if (row != null)
                row.putWorkingValue(key, e.getValue());
        }
    }

    @Override
    public String description() {
        return "Remove column '" + key + "'";
    }

    public String getKey() {
        return key;
    }

    public int affectedEntryCount() {
        return capturedValues.size();
    }
}
