package qupath.ext.projectmetadatabrowser.ui;

import java.util.Optional;

import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import qupath.ext.projectmetadatabrowser.core.MetadataKeyOperations.CollisionPolicy;

/**
 * Modal dialog that prompts the user for a new name for a metadata key plus a
 * collision policy for entries that already have both the old and new keys.
 *
 * <p>Window-modal -- the parent browser window is non-modal and may sit
 * alongside the QuPath viewer; {@code WINDOW_MODAL} keeps the rename above
 * the browser window without blocking the rest of QuPath.
 *
 * <p>Validation runs in a {@code textProperty()} listener as the user types
 * and disables the Rename button when:
 * <ul>
 *   <li>The trimmed new key is empty.</li>
 *   <li>The new key contains any whitespace (spaces, tabs, newlines).</li>
 *   <li>The new key equals the old key.</li>
 *   <li>No non-Cancel collision-policy radio is selected.</li>
 * </ul>
 *
 * <p>Per the project's modal-dialog discipline, the
 * {@code setResultConverter} returns immediately without blocking validation;
 * the caller treats a {@code null} result as a cancel.
 */
public final class RenameKeyDialog {

    /**
     * Outcome of a successful Rename click. Cancel returns {@code null} from
     * {@link #showFor}, so the policy field is exhaustive: only
     * {@link CollisionPolicy#OVERWRITE} or {@link CollisionPolicy#SKIP}.
     */
    public record Result(String newKey, CollisionPolicy policy) {}

    private RenameKeyDialog() {
        // utility class -- no instances
    }

    /**
     * Show the dialog modally and block until the user clicks Rename or
     * cancels.
     *
     * @param owner the owner window. May be null.
     * @param oldKey the existing key being renamed.
     * @param entryCount how many entries currently have {@code oldKey}.
     * @return the user's chosen new key and collision policy, or
     *         {@code null} if the user cancelled.
     */
    public static Result showFor(Window owner, String oldKey, int entryCount) {
        Dialog<Result> dialog = new Dialog<>();
        dialog.setTitle("Rename metadata key");
        dialog.setHeaderText(null);
        if (owner != null)
            dialog.initOwner(owner);
        dialog.initModality(Modality.WINDOW_MODAL);

        ButtonType renameType = new ButtonType("Rename", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(renameType, ButtonType.CANCEL);

        // Header line: "Rename "oldKey" (used by N entries)" -- singular "1 entry".
        String entryCountText = entryCount == 1 ? "1 entry" : entryCount + " entries";
        Label headerLine = new Label("Rename \"" + oldKey + "\" (used by " + entryCountText + ")");
        headerLine.setStyle("-fx-font-weight: bold;");
        headerLine.setWrapText(true);

        Label newKeyLabel = new Label("New key:");
        TextField newKeyField = new TextField(oldKey);
        newKeyField.setPromptText("new key");
        newKeyField.setTooltip(new Tooltip(
                "The new name for the key. Cannot be empty or contain whitespace."));

        // Inline validation label with a reserved height so the dialog does not
        // jump when the message text changes between empty and populated.
        Label validationLabel = new Label(" ");
        validationLabel.setWrapText(true);
        validationLabel.setMinHeight(36);
        validationLabel.setPrefHeight(36);

        Label policyLabel = new Label("If the new key already exists on an entry:");
        policyLabel.setWrapText(true);

        ToggleGroup policyGroup = new ToggleGroup();
        RadioButton overwriteRadio = new RadioButton("Overwrite -- replace the existing value");
        overwriteRadio.setToggleGroup(policyGroup);
        overwriteRadio.setTooltip(new Tooltip(
                "If an entry already has the new key set, replace its existing value "
                        + "with the value from the old key."));

        RadioButton skipRadio = new RadioButton("Skip -- keep the existing value, drop the old key");
        skipRadio.setToggleGroup(policyGroup);
        skipRadio.setTooltip(new Tooltip(
                "If an entry already has the new key set, keep the existing value untouched. "
                        + "The old key is still removed from that entry."));

        RadioButton cancelRadio = new RadioButton(
                "Cancel -- abort the rename");
        cancelRadio.setToggleGroup(policyGroup);
        cancelRadio.setTooltip(new Tooltip(
                "Default. The rename will not proceed until you pick Overwrite or Skip."));
        cancelRadio.setSelected(true);

        VBox policyBox = new VBox(4, policyLabel, overwriteRadio, skipRadio, cancelRadio);

        VBox content = new VBox(8,
                headerLine,
                new Label(" "),
                newKeyLabel,
                newKeyField,
                validationLabel,
                policyBox);
        content.setPadding(new Insets(12));
        dialog.getDialogPane().setContent(content);
        dialog.setResizable(true);

        Scene scene = dialog.getDialogPane().getScene();
        if (scene != null && scene.getWindow() instanceof Stage stage) {
            stage.setMinWidth(480);
            stage.setMinHeight(320);
        }

        Button renameButton = (Button) dialog.getDialogPane().lookupButton(renameType);
        renameButton.setDefaultButton(true);
        renameButton.setTooltip(new Tooltip(
                "Apply the rename to every entry that has the old key."));
        renameButton.setDisable(true);

        Button cancelButton = (Button) dialog.getDialogPane().lookupButton(ButtonType.CANCEL);
        cancelButton.setCancelButton(true);
        cancelButton.setTooltip(new Tooltip("Close without renaming any keys."));

        // Validation: runs on every keystroke or policy change. Updates the
        // inline label text + style and the Rename button's disable property.
        Runnable revalidate = () -> {
            String raw = newKeyField.getText();
            String text = raw == null ? "" : raw;
            boolean ok = true;
            String message = " ";
            // Informational grey (matches MetadataEditDialog hint style).
            String style = "-fx-text-fill: #666;";
            if (text.trim().isEmpty()) {
                ok = false;
                message = "Key cannot be empty.";
                style = "-fx-text-fill: #c00;";
            } else if (containsWhitespace(text)) {
                ok = false;
                message = "Key cannot contain spaces, tabs, or newlines.";
                style = "-fx-text-fill: #c00;";
            } else if (text.equals(oldKey)) {
                ok = false;
                message = "New key is the same as the old key.";
                // Informational, not an error -- grey not red.
                style = "-fx-text-fill: #666;";
            }
            boolean nonCancelPolicy = overwriteRadio.isSelected() || skipRadio.isSelected();
            if (ok && !nonCancelPolicy) {
                message = "Choose Overwrite or Skip to enable Rename.";
                style = "-fx-text-fill: #666;";
            }
            validationLabel.setText(message);
            validationLabel.setStyle(style);
            renameButton.setDisable(!(ok && nonCancelPolicy));
        };
        newKeyField.textProperty().addListener((obs, o, n) -> revalidate.run());
        policyGroup.selectedToggleProperty().addListener((obs, o, n) -> revalidate.run());

        dialog.setOnShown(ev -> {
            // selectAll so typing replaces the prefilled old key immediately,
            // and Tab from the field reaches the radios in source order.
            newKeyField.requestFocus();
            newKeyField.selectAll();
            // Ensure the initial state shows the "same as old key" informational
            // message rather than no message at all -- the dialog opens with
            // the old key prefilled.
            revalidate.run();
        });

        dialog.setResultConverter(bt -> {
            if (bt != renameType)
                return null;
            // Validation guarantees a non-Cancel radio is selected and the
            // text is well-formed, but defend against a future race.
            String text = newKeyField.getText();
            if (text == null || text.trim().isEmpty() || containsWhitespace(text))
                return null;
            if (text.equals(oldKey))
                return null;
            CollisionPolicy policy;
            if (overwriteRadio.isSelected())
                policy = CollisionPolicy.OVERWRITE;
            else if (skipRadio.isSelected())
                policy = CollisionPolicy.SKIP;
            else
                return null;
            return new Result(text, policy);
        });

        Optional<Result> result = dialog.showAndWait();
        return result.orElse(null);
    }

    /**
     * True if {@code s} contains any whitespace character: space, tab,
     * newline, carriage return, form feed, or vertical tab.
     */
    private static boolean containsWhitespace(String s) {
        if (s == null)
            return false;
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i)))
                return true;
        }
        return false;
    }
}
