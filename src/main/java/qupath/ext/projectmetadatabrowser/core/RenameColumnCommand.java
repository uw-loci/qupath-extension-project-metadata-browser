package qupath.ext.projectmetadatabrowser.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import qupath.ext.projectmetadatabrowser.core.MetadataKeyOperations.CollisionPolicy;
import qupath.ext.projectmetadatabrowser.model.MutableEntryRow;
import qupath.ext.projectmetadatabrowser.model.WorkingCopy;

/**
 * Rename a user-metadata key across every entry in the working copy with
 * the collision policy from {@link CollisionPolicy}. Equivalent to the
 * v0.2.0 {@code MetadataKeyOperations.renameAcrossProject} behaviour but
 * deferred to Save: applies to the working copy only.
 *
 * <p>Per-entry decision (mirrors v0.2.0):
 * <ul>
 *   <li>Entry has {@code oldKey} but not {@code newKey} -> move value;
 *       remove old key.</li>
 *   <li>Entry has both keys and policy is {@link CollisionPolicy#OVERWRITE} ->
 *       new key gets old's value; old removed.</li>
 *   <li>Entry has both keys and policy is {@link CollisionPolicy#SKIP} ->
 *       new key keeps its existing value; old removed.</li>
 *   <li>Entry has only {@code newKey} or neither -> untouched.</li>
 * </ul>
 *
 * <p>For undo, the command remembers each entry's pre-rename value of
 * {@code newKey} (so an overwritten value is restored) plus the value that
 * was carried from {@code oldKey}.
 */
public final class RenameColumnCommand implements MetadataCommand {

    private final String oldKey;
    private final String newKey;
    private final CollisionPolicy policy;

    /** entryId -> pre-rename value of oldKey (always non-null when captured). */
    private final Map<String, String> capturedOldValues = new LinkedHashMap<>();
    /** entryId -> pre-rename value of newKey (only when newKey was present). */
    private final Map<String, String> capturedNewValues = new LinkedHashMap<>();
    private boolean wasOldColumnPresent;
    private boolean wasNewColumnPresent;

    public RenameColumnCommand(String oldKey, String newKey, CollisionPolicy policy) {
        this.oldKey = Objects.requireNonNull(oldKey, "oldKey");
        this.newKey = Objects.requireNonNull(newKey, "newKey");
        this.policy = Objects.requireNonNull(policy, "policy");
        if (oldKey.isBlank())
            throw new IllegalArgumentException("oldKey must not be blank");
        if (newKey.isBlank())
            throw new IllegalArgumentException("newKey must not be blank");
    }

    @Override
    public void apply(WorkingCopy wc) {
        wasOldColumnPresent = wc.getColumnKeys().contains(oldKey);
        wasNewColumnPresent = wc.getColumnKeys().contains(newKey);
        capturedOldValues.clear();
        capturedNewValues.clear();
        for (MutableEntryRow row : wc.getRows()) {
            if (!row.hasMetadata(oldKey))
                continue;
            String oldValue = row.getMetadata(oldKey);
            capturedOldValues.put(row.getId(), oldValue);
            boolean hasNew = row.hasMetadata(newKey);
            if (hasNew) {
                capturedNewValues.put(row.getId(), row.getMetadata(newKey));
                if (policy == CollisionPolicy.OVERWRITE)
                    row.putWorkingValue(newKey, oldValue);
                // SKIP: leave newKey alone.
                row.removeWorkingKey(oldKey);
            } else {
                row.putWorkingValue(newKey, oldValue);
                row.removeWorkingKey(oldKey);
            }
        }
        // Column list: add newKey, remove oldKey if nobody still has it.
        wc.addColumn(newKey);
        boolean anyOldRemains = false;
        for (MutableEntryRow row : wc.getRows()) {
            if (row.hasMetadata(oldKey)) {
                anyOldRemains = true;
                break;
            }
        }
        if (!anyOldRemains)
            wc.removeColumn(oldKey);
    }

    @Override
    public void undo(WorkingCopy wc) {
        // Restore each touched entry's pre-rename state.
        for (Map.Entry<String, String> e : capturedOldValues.entrySet()) {
            MutableEntryRow row = wc.getRowById(e.getKey());
            if (row == null)
                continue;
            row.putWorkingValue(oldKey, e.getValue());
        }
        for (MutableEntryRow row : wc.getRows()) {
            String id = row.getId();
            if (capturedOldValues.containsKey(id)) {
                if (capturedNewValues.containsKey(id)) {
                    row.putWorkingValue(newKey, capturedNewValues.get(id));
                } else {
                    row.removeWorkingKey(newKey);
                }
            }
        }
        // Column list: restore presence as it was at apply time.
        if (wasOldColumnPresent)
            wc.addColumn(oldKey);
        if (!wasNewColumnPresent)
            wc.removeColumn(newKey);
    }

    @Override
    public String description() {
        return "Rename '" + oldKey + "' to '" + newKey + "'";
    }

    public String getOldKey() {
        return oldKey;
    }

    public String getNewKey() {
        return newKey;
    }

    public CollisionPolicy getPolicy() {
        return policy;
    }

    public int affectedEntryCount() {
        return capturedOldValues.size();
    }
}
