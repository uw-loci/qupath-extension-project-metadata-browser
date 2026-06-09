package qupath.ext.projectmetadatabrowser.core;

import java.util.List;
import java.util.Objects;

import qupath.ext.projectmetadatabrowser.model.WorkingCopy;

/**
 * Composite command produced by the Import wizard. Apply is the union of:
 * <ol>
 *   <li>Adding any new columns the import will populate.</li>
 *   <li>Setting cell values per (entryId, key) delta.</li>
 * </ol>
 *
 * <p>Delegates the heavy lifting to {@link BulkSetCellsCommand} -- the
 * import wizard pre-computes the cell deltas during preview, and the
 * command just replays them on Apply. One undo reverses the entire import.
 */
public final class ImportCommand implements MetadataCommand {

    private final String fileLabel;
    private final BulkSetCellsCommand inner;
    private final List<String> newColumns;

    public ImportCommand(String fileLabel,
                         List<String> newColumns,
                         List<BulkSetCellsCommand.CellDelta> cellDeltas) {
        this.fileLabel = Objects.requireNonNullElse(fileLabel, "imported file");
        this.newColumns = List.copyOf(Objects.requireNonNull(newColumns, "newColumns"));
        this.inner = new BulkSetCellsCommand("Import " + this.fileLabel,
                Objects.requireNonNull(cellDeltas, "cellDeltas"));
    }

    @Override
    public void apply(WorkingCopy wc) {
        for (String col : newColumns)
            wc.addColumn(col);
        inner.apply(wc);
    }

    @Override
    public void undo(WorkingCopy wc) {
        inner.undo(wc);
        // newColumns are also undone by inner.undo if they were introduced
        // by the inner deltas; explicit ones (e.g. blank-template adds) are
        // removed here. Tolerate duplicates -- removeColumn is idempotent.
        for (String col : newColumns)
            wc.removeColumn(col);
    }

    @Override
    public String description() {
        return "Import " + fileLabel;
    }

    public int affectedCellCount() {
        return inner.affectedCellCount();
    }

    public List<String> getNewColumns() {
        return newColumns;
    }
}
