package qupath.ext.projectmetadatabrowser.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import qupath.ext.projectmetadatabrowser.model.MutableEntryRow;
import qupath.ext.projectmetadatabrowser.model.WorkingCopy;

/**
 * Atomic edit of an arbitrary list of cells. Used by Excel paste and by the
 * per-image right-click "Edit metadata..." dialog. One undo reverses the
 * entire bulk operation.
 *
 * <p>Records each delta with its (entryId, key, oldValue, newValue) so undo
 * is deterministic regardless of intervening commands.
 */
public final class BulkSetCellsCommand implements MetadataCommand {

    /** Cell delta carried by the command. Immutable. */
    public record CellDelta(String entryId, String key, String oldValue, String newValue) {
        public CellDelta {
            Objects.requireNonNull(entryId, "entryId");
            Objects.requireNonNull(key, "key");
        }
    }

    private final String label;
    private final List<CellDelta> deltas;
    /** Columns introduced by this bulk; tracked so undo can remove them. */
    private final Set<String> newColumns = new LinkedHashSet<>();

    public BulkSetCellsCommand(String label, List<CellDelta> deltas) {
        this.label = Objects.requireNonNullElse(label, "Bulk edit");
        this.deltas = List.copyOf(Objects.requireNonNull(deltas, "deltas"));
    }

    @Override
    public void apply(WorkingCopy wc) {
        newColumns.clear();
        for (CellDelta d : deltas) {
            if (!wc.getColumnKeys().contains(d.key()))
                newColumns.add(d.key());
            wc.addColumn(d.key());
            MutableEntryRow row = wc.getRowById(d.entryId());
            if (row == null)
                continue;
            if (d.newValue() == null || d.newValue().isEmpty())
                row.removeWorkingKey(d.key());
            else
                row.putWorkingValue(d.key(), d.newValue());
        }
    }

    @Override
    public void undo(WorkingCopy wc) {
        // Reverse the deltas in apply-order (set/remove is idempotent w.r.t.
        // order for this single command -- a bulk edit never touches the
        // same cell twice).
        for (CellDelta d : deltas) {
            MutableEntryRow row = wc.getRowById(d.entryId());
            if (row == null)
                continue;
            if (d.oldValue() == null || d.oldValue().isEmpty())
                row.removeWorkingKey(d.key());
            else
                row.putWorkingValue(d.key(), d.oldValue());
        }
        for (String col : newColumns)
            wc.removeColumn(col);
    }

    @Override
    public String description() {
        return label;
    }

    public List<CellDelta> getDeltas() {
        return deltas;
    }

    public int affectedCellCount() {
        return deltas.size();
    }

    /** All distinct entry IDs touched by this command, in deltas order. */
    public List<String> affectedEntryIds() {
        Set<String> seen = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();
        for (CellDelta d : deltas) {
            if (seen.add(d.entryId()))
                out.add(d.entryId());
        }
        return out;
    }
}
