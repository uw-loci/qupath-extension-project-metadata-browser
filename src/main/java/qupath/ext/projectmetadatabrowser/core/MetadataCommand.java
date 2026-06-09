package qupath.ext.projectmetadatabrowser.core;

import qupath.ext.projectmetadatabrowser.model.WorkingCopy;

/**
 * One undoable mutation on a {@link WorkingCopy}. Project-internal command
 * pattern -- intentionally simpler than
 * {@code javax.swing.undo.UndoableEdit} (no merge semantics, no localized
 * presentation names, no canUndo/canRedo flags). The three methods are all
 * the buffered editor needs.
 *
 * <p>Commands are pushed onto {@link UndoStack} after a successful
 * {@link #apply}. The stack calls {@link #undo} to reverse; redo replays
 * {@link #apply} again. Each command must record enough state at
 * construction time to round-trip cleanly, even if the working-copy state
 * has been mutated by intervening commands between apply and undo.
 */
public interface MetadataCommand {

    /** Apply the command's mutation to {@code wc}. */
    void apply(WorkingCopy wc);

    /** Reverse the mutation, restoring the pre-apply state. */
    void undo(WorkingCopy wc);

    /**
     * Short human-readable label for the menu counter ("Edit cell B12",
     * "Rename 'typo' -> 'Antibody'", "Import metadata.csv"). ASCII only.
     */
    String description();
}
