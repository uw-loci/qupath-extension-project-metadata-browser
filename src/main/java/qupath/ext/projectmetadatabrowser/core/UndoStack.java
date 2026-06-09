package qupath.ext.projectmetadatabrowser.core;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyIntegerWrapper;

import qupath.ext.projectmetadatabrowser.model.WorkingCopy;

/**
 * Two-stack undo/redo bookkeeping for a single window. Decoupled from the
 * working copy so tests can drive it directly.
 *
 * <p>Push semantics: {@link #pushAndApply} applies the command, records it on
 * the undo stack, and clears the redo stack -- the standard text-editor
 * convention. {@link #undo} pops one entry, reverses it via the command's
 * {@code undo} method, and pushes the same entry onto the redo stack;
 * {@link #redo} is the mirror.
 *
 * <p>Sizes are exposed as observable integer properties so menu items can
 * bind their disabled state and label counters to the stack depth without
 * polling.
 */
public final class UndoStack {

    private final WorkingCopy workingCopy;
    private final Deque<MetadataCommand> undo = new ArrayDeque<>();
    private final Deque<MetadataCommand> redo = new ArrayDeque<>();

    private final ReadOnlyIntegerWrapper undoSize = new ReadOnlyIntegerWrapper(0);
    private final ReadOnlyIntegerWrapper redoSize = new ReadOnlyIntegerWrapper(0);

    public UndoStack(WorkingCopy workingCopy) {
        this.workingCopy = Objects.requireNonNull(workingCopy, "workingCopy");
    }

    /**
     * Apply the command immediately, then record it for undo. Clears the
     * redo stack (a new branch replaces the prior redo history).
     */
    public void pushAndApply(MetadataCommand command) {
        Objects.requireNonNull(command, "command");
        workingCopy.applyCommand(command);
        undo.push(command);
        redo.clear();
        refreshSizes();
    }

    /**
     * Reverse the most recently applied command. No-op on an empty stack.
     */
    public void undo() {
        if (undo.isEmpty())
            return;
        MetadataCommand command = undo.pop();
        workingCopy.undoCommand(command);
        redo.push(command);
        refreshSizes();
    }

    /**
     * Re-apply the most recently undone command. No-op on an empty redo
     * stack.
     */
    public void redo() {
        if (redo.isEmpty())
            return;
        MetadataCommand command = redo.pop();
        workingCopy.applyCommand(command);
        undo.push(command);
        refreshSizes();
    }

    /** Discard all undo and redo history. */
    public void clear() {
        undo.clear();
        redo.clear();
        refreshSizes();
    }

    /** Number of commands currently available for undo. */
    public int undoSize() {
        return undo.size();
    }

    /** Number of commands currently available for redo. */
    public int redoSize() {
        return redo.size();
    }

    /**
     * Description of the next undoable command, or null when the undo stack
     * is empty. Used by the Edit menu's tooltip.
     */
    public String peekUndoDescription() {
        return undo.isEmpty() ? null : undo.peek().description();
    }

    /**
     * Description of the next redoable command, or null when the redo stack
     * is empty.
     */
    public String peekRedoDescription() {
        return redo.isEmpty() ? null : redo.peek().description();
    }

    public ReadOnlyIntegerProperty undoSizeProperty() {
        return undoSize.getReadOnlyProperty();
    }

    public ReadOnlyIntegerProperty redoSizeProperty() {
        return redoSize.getReadOnlyProperty();
    }

    private void refreshSizes() {
        undoSize.set(undo.size());
        redoSize.set(redo.size());
    }
}
