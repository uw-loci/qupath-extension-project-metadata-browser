package qupath.ext.projectmetadatabrowser.core;

import java.util.List;
import java.util.Objects;

import qupath.ext.projectmetadatabrowser.model.WorkingCopy;

/**
 * Composite command produced by the Regex extraction dialog. Adds zero or
 * more new columns (one per named group) and writes per-entry deltas with
 * the captured group values.
 *
 * <p>Internally a thin wrapper over {@link BulkSetCellsCommand}; one undo
 * reverses the entire extraction.
 */
public final class RegexExtractCommand implements MetadataCommand {

    private final String pattern;
    private final BulkSetCellsCommand inner;
    private final List<String> newColumns;

    public RegexExtractCommand(String pattern,
                               List<String> newColumns,
                               List<BulkSetCellsCommand.CellDelta> cellDeltas) {
        this.pattern = Objects.requireNonNullElse(pattern, "regex");
        this.newColumns = List.copyOf(Objects.requireNonNull(newColumns, "newColumns"));
        this.inner = new BulkSetCellsCommand("Regex extract: " + pattern,
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
        for (String col : newColumns)
            wc.removeColumn(col);
    }

    @Override
    public String description() {
        return "Extract columns from regex: " + pattern;
    }

    public List<String> getNewColumns() {
        return newColumns;
    }

    public int affectedCellCount() {
        return inner.affectedCellCount();
    }
}
