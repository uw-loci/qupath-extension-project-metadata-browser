package qupath.ext.projectmetadatabrowser.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;

import qupath.ext.projectmetadatabrowser.model.MutableEntryRow;

/**
 * Modal dialog for editing the working-copy metadata of a single
 * {@link MutableEntryRow}. v1.1 returns a delta {@code Map<String,String>}
 * (the user's changes); the caller wraps that into a
 * {@link qupath.ext.projectmetadatabrowser.core.BulkSetCellsCommand} and
 * applies it to the working copy. The dialog no longer mutates the entry
 * or calls {@code project.syncChanges()}.
 */
public final class MetadataEditDialog {

    private MetadataEditDialog() {
        // utility class -- no instances
    }

    /**
     * Show the dialog modally and return the user's delta (or an empty map
     * on cancel / no-op). Blank values indicate keys to remove; non-blank
     * values indicate additions or updates.
     */
    public static Map<String, String> showFor(Window owner, MutableEntryRow row) {
        Objects.requireNonNull(row, "row");

        Dialog<Map<String, String>> dialog = new Dialog<>();
        dialog.setTitle("Edit metadata");
        dialog.setHeaderText("Editing metadata for: " + row.getName());
        if (owner != null)
            dialog.initOwner(owner);

        ButtonType okType = new ButtonType("OK", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(okType, ButtonType.CANCEL);

        // Existing keys, sorted alphabetically for stability.
        Map<String, String> original = new TreeMap<>(row.snapshotWorking());

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(6);
        grid.setPadding(new Insets(10));

        grid.add(new Label("Key"), 0, 0);
        grid.add(new Label("Value"), 1, 0);

        List<KeyFieldPair> fields = new ArrayList<>();
        int rowIdx = 1;
        for (Map.Entry<String, String> e : original.entrySet()) {
            Label keyLabel = new Label(e.getKey());
            keyLabel.setMinWidth(140);
            TextField valueField = new TextField(e.getValue() == null ? "" : e.getValue());
            valueField.setPrefColumnCount(30);
            GridPane.setHgrow(valueField, Priority.ALWAYS);
            grid.add(keyLabel, 0, rowIdx);
            grid.add(valueField, 1, rowIdx);
            fields.add(new KeyFieldPair(e.getKey(), valueField, /*isNew=*/ false));
            rowIdx++;
        }

        // One blank row for adding a new pair.
        Label newLabel = new Label("(new)");
        newLabel.setStyle("-fx-font-style: italic;");
        TextField newKey = new TextField();
        newKey.setPromptText("new key");
        TextField newValue = new TextField();
        newValue.setPromptText("new value");
        newValue.setPrefColumnCount(30);
        GridPane.setHgrow(newValue, Priority.ALWAYS);
        HBox newKeyBox = new HBox(4, newLabel, newKey);
        grid.add(newKeyBox, 0, rowIdx);
        grid.add(newValue, 1, rowIdx);

        Label hint = new Label(original.isEmpty()
                ? "This image has no metadata yet. Fill in the row below to add one."
                : "Clear a value (or enter whitespace only) to remove that "
                        + "metadata entry. Fill in the last row to add a new key. "
                        + "Edits stay in the working copy until you Save.");
        hint.setWrapText(true);

        ScrollPane scroller = new ScrollPane(grid);
        scroller.setFitToWidth(true);
        scroller.setPrefViewportHeight(360);
        VBox content = new VBox(6, hint, scroller);
        content.setPadding(new Insets(10));
        dialog.getDialogPane().setContent(content);
        dialog.setResizable(true);

        Scene scene = dialog.getDialogPane().getScene();
        if (scene != null && scene.getWindow() instanceof Stage stage) {
            stage.setMinWidth(520);
            stage.setMinHeight(320);
        }

        dialog.setResultConverter(bt -> {
            if (bt != okType)
                return null;
            Map<String, String> updates = new HashMap<>();
            for (KeyFieldPair f : fields) {
                String newVal = trimToEmpty(f.valueField.getText());
                String oldVal = trimToEmpty(original.get(f.key));
                if (!Objects.equals(newVal, oldVal))
                    updates.put(f.key, newVal);
            }
            String k = newKey.getText();
            String v = newValue.getText();
            if (k != null && !k.isBlank() && v != null && !v.isBlank()) {
                updates.put(k.trim(), v.trim());
            }
            return updates;
        });

        Optional<Map<String, String>> result = dialog.showAndWait();
        if (result.isEmpty() || result.get().isEmpty())
            return java.util.Collections.emptyMap();
        return result.get();
    }

    private static String trimToEmpty(String s) {
        return s == null ? "" : s.trim();
    }

    private record KeyFieldPair(String key, TextField valueField, boolean isNew) {}
}
