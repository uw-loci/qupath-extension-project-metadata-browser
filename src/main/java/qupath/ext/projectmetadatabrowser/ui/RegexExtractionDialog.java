package qupath.ext.projectmetadatabrowser.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javafx.animation.PauseTransition;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

import qupath.ext.projectmetadatabrowser.core.BulkSetCellsCommand;
import qupath.ext.projectmetadatabrowser.core.MetadataKeyOperations.CollisionPolicy;
import qupath.ext.projectmetadatabrowser.core.RegexExtractCommand;
import qupath.ext.projectmetadatabrowser.model.MutableEntryRow;
import qupath.ext.projectmetadatabrowser.model.WorkingCopy;

/**
 * Edit > Extract columns from filenames dialog. Lets the user type a regex
 * with named groups, watch a 50-row live preview update, and on Apply
 * receives a {@link RegexExtractCommand} composed of new-column adds plus
 * per-entry cell deltas.
 *
 * <p>Reuses the v0.2.0 {@link CollisionPolicy} enum for the
 * Overwrite/Skip/Cancel policy when a group name collides with an existing
 * column. Defaults to Cancel so an accidental Enter does nothing.
 */
public final class RegexExtractionDialog {

    private final WorkingCopy workingCopy;
    private final RegexPreviewModel preview = new RegexPreviewModel();
    private final Dialog<RegexExtractCommand> dialog = new Dialog<>();

    private final ChoiceBox<String> sourceChoice = new ChoiceBox<>();
    private final TextField regexField = new TextField();
    private final Label validationLabel = new Label(" ");
    private final VBox groupOverridesBox = new VBox(4);
    private final Map<String, TextField> overrideFields = new LinkedHashMap<>();
    private final RadioButton overwriteRadio = new RadioButton("Overwrite -- replace any current value");
    private final RadioButton skipRadio = new RadioButton("Skip -- keep current values, leave non-collisions alone");
    private final RadioButton cancelRadio = new RadioButton("Cancel -- abort the extraction");
    private final Label policyHintLabel = new Label(" ");
    private final CheckBox skipNonMatchingBox = new CheckBox("Skip non-matching entries (leave their cells unchanged)");
    private final TableView<RegexPreviewModel.PreviewRow> previewTable = new TableView<>();
    private final Label countsLabel = new Label(" ");
    private final ButtonType applyType = new ButtonType("Apply", ButtonBar.ButtonData.OK_DONE);

    private final PauseTransition debounce = new PauseTransition(Duration.millis(250));

    private RegexExtractionDialog(Window owner, WorkingCopy workingCopy) {
        this.workingCopy = workingCopy;
        dialog.setTitle("Extract columns from filenames");
        if (owner != null)
            dialog.initOwner(owner);
        dialog.initModality(Modality.WINDOW_MODAL);
        dialog.getDialogPane().getButtonTypes().addAll(applyType, ButtonType.CANCEL);
        Button applyBtn = (Button) dialog.getDialogPane().lookupButton(applyType);
        applyBtn.setTooltip(new Tooltip("Apply the regex to every entry as one undoable action."));

        buildPanel();
        wireListeners();
        revalidate();

        dialog.setResultConverter(bt -> bt == applyType ? buildCommand() : null);
    }

    /**
     * Show the dialog and return the constructed {@link RegexExtractCommand}
     * on Apply, or null on Cancel.
     */
    public static RegexExtractCommand showAndBuild(Window owner, WorkingCopy workingCopy) {
        RegexExtractionDialog d = new RegexExtractionDialog(owner, workingCopy);
        Optional<RegexExtractCommand> picked = d.dialog.showAndWait();
        return picked.orElse(null);
    }

    private void buildPanel() {
        // Source column choice: Image Name + URI + user-key columns.
        sourceChoice.getItems().add(MutableEntryRow.COL_NAME);
        sourceChoice.getItems().add(MutableEntryRow.COL_URI);
        for (String key : workingCopy.getColumnKeys())
            sourceChoice.getItems().add(key);
        sourceChoice.setValue(MutableEntryRow.COL_NAME);
        sourceChoice.setTooltip(new Tooltip(
                "The column whose text the regex will be matched against."));

        regexField.setPromptText("e.g., (?<patient>P\\d+)_(?<tp>T\\d+)_(?<stain>HE|CD3).*\\.tiff");
        regexField.setTooltip(new Tooltip(
                "Java regex with named groups. Each (?<name>...) becomes a new column."));

        validationLabel.setMinHeight(24);
        validationLabel.setWrapText(true);
        validationLabel.setStyle("-fx-text-fill: #666;");

        Label policyLabel = new Label("If a group's column name already exists on entries:");
        policyLabel.setStyle("-fx-font-weight: bold;");
        ToggleGroup policyGroup = new ToggleGroup();
        overwriteRadio.setToggleGroup(policyGroup);
        skipRadio.setToggleGroup(policyGroup);
        cancelRadio.setToggleGroup(policyGroup);
        cancelRadio.setSelected(true);
        overwriteRadio.setTooltip(new Tooltip(
                "If a destination column already exists, replace its current values."));
        skipRadio.setTooltip(new Tooltip(
                "If a destination column already exists, keep its current values where they are set."));
        cancelRadio.setTooltip(new Tooltip(
                "Default. Apply is disabled until you pick Overwrite or Skip."));

        skipNonMatchingBox.setSelected(true);
        skipNonMatchingBox.setTooltip(new Tooltip(
                "When checked, entries whose source value does not match are left untouched."));

        previewTable.setPlaceholder(new Label(
                "First 50 entries. Type a regex with named groups to populate."));
        previewTable.setPrefHeight(220);

        countsLabel.setStyle("-fx-text-fill: #666;");
        countsLabel.setWrapText(true);

        GridPane top = new GridPane();
        top.setHgap(8);
        top.setVgap(6);
        top.setPadding(new Insets(6));
        top.add(new Label("Source column:"), 0, 0);
        top.add(sourceChoice, 1, 0);
        top.add(new Label("Regex pattern:"), 0, 1);
        top.add(regexField, 1, 1);
        GridPane.setHgrow(regexField, Priority.ALWAYS);
        top.add(validationLabel, 1, 2);

        Label groupsLabel = new Label("Detected groups (rename column if needed):");
        groupsLabel.setStyle("-fx-font-weight: bold;");

        // policyHintLabel surfaces "Choose Overwrite or Skip to enable
        // Apply." when the regex is valid AND >= 1 named group AND policy
        // is still Cancel -- otherwise a user reads "Valid regex" and is
        // baffled why Apply remains greyed.
        policyHintLabel.setStyle("-fx-text-fill: #c80;");
        policyHintLabel.setWrapText(true);
        policyHintLabel.setVisible(false);
        policyHintLabel.setManaged(false);
        VBox policyBox = new VBox(4, policyLabel, overwriteRadio, skipRadio, cancelRadio, policyHintLabel);

        VBox body = new VBox(8,
                top,
                groupsLabel,
                groupOverridesBox,
                policyBox,
                skipNonMatchingBox,
                new Label("Preview:"),
                previewTable,
                countsLabel);
        body.setPadding(new Insets(12));
        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        dialog.getDialogPane().setContent(scroll);
        dialog.setResizable(true);
        Scene scene = dialog.getDialogPane().getScene();
        if (scene != null && scene.getWindow() instanceof Stage stage) {
            stage.setMinWidth(820);
            stage.setMinHeight(640);
        }
    }

    private void wireListeners() {
        debounce.setOnFinished(ev -> refreshPreview());
        regexField.textProperty().addListener((obs, o, n) -> {
            debounce.stop();
            debounce.playFromStart();
        });
        sourceChoice.valueProperty().addListener((obs, o, n) -> {
            debounce.stop();
            debounce.playFromStart();
        });
        overwriteRadio.selectedProperty().addListener((obs, o, n) -> revalidate());
        skipRadio.selectedProperty().addListener((obs, o, n) -> revalidate());
        cancelRadio.selectedProperty().addListener((obs, o, n) -> revalidate());
    }

    private void refreshPreview() {
        Function<MutableEntryRow, String> resolver = sourceResolver();
        preview.update(regexField.getText(), workingCopy.getRows(), resolver);
        rebuildGroupOverrides(preview.getGroupNames());
        rebuildPreviewTable();
        revalidate();
    }

    private void rebuildGroupOverrides(List<String> groupNames) {
        Set<String> existing = new LinkedHashSet<>(overrideFields.keySet());
        Set<String> wanted = new LinkedHashSet<>(groupNames);
        if (existing.equals(wanted))
            return;
        groupOverridesBox.getChildren().clear();
        // Preserve any user override that survives the group set.
        Map<String, String> kept = new LinkedHashMap<>();
        for (Map.Entry<String, TextField> e : overrideFields.entrySet()) {
            if (wanted.contains(e.getKey()))
                kept.put(e.getKey(), e.getValue().getText());
        }
        overrideFields.clear();
        for (String name : groupNames) {
            HBox row = new HBox(6);
            Label l = new Label(name + " -> ");
            l.setMinWidth(120);
            TextField tf = new TextField(kept.getOrDefault(name, name));
            tf.setTooltip(new Tooltip(
                    "The destination column name for this group. Defaults to the group name."));
            row.getChildren().addAll(l, tf);
            HBox.setHgrow(tf, Priority.ALWAYS);
            groupOverridesBox.getChildren().add(row);
            overrideFields.put(name, tf);
        }
    }

    private void rebuildPreviewTable() {
        previewTable.getColumns().clear();
        TableColumn<RegexPreviewModel.PreviewRow, String> nameCol = new TableColumn<>("Source");
        nameCol.setCellValueFactory(cdf -> new ReadOnlyStringWrapper(cdf.getValue().sourceValue()));
        nameCol.setPrefWidth(260);
        previewTable.getColumns().add(nameCol);
        for (String group : preview.getGroupNames()) {
            TableColumn<RegexPreviewModel.PreviewRow, String> col = new TableColumn<>(group);
            col.setCellValueFactory(cdf -> {
                if (!cdf.getValue().matched())
                    return new ReadOnlyStringWrapper("(no match)");
                return new ReadOnlyStringWrapper(cdf.getValue().groupValues().getOrDefault(group, ""));
            });
            col.setPrefWidth(120);
            previewTable.getColumns().add(col);
        }
        previewTable.setItems(FXCollections.observableArrayList(preview.getPreviewRows()));
        StringBuilder sb = new StringBuilder();
        sb.append("Matched ").append(preview.getTotalMatched())
                .append(" of ").append(preview.getTotalMatched() + preview.getTotalUnmatched())
                .append("; ").append(preview.getTotalUnmatched()).append(" unmatched.");
        countsLabel.setText(sb.toString());
    }

    private void revalidate() {
        String regex = regexField.getText();
        Button applyBtn = (Button) dialog.getDialogPane().lookupButton(applyType);
        // Default to hidden; only the "valid regex + groups detected +
        // still on Cancel policy" path turns it on.
        setPolicyHintVisible(false, null);
        if (regex == null || regex.isEmpty()) {
            validationLabel.setText("No named groups yet -- add (?<name>...) to extract.");
            validationLabel.setStyle("-fx-text-fill: #666;");
            applyBtn.setDisable(true);
            return;
        }
        if (preview.getCompileError() != null) {
            String msg = preview.getCompileError();
            if (msg.length() > 80) msg = msg.substring(0, 80) + "...";
            validationLabel.setText("Invalid regex: " + msg);
            validationLabel.setStyle("-fx-text-fill: #c00;");
            applyBtn.setDisable(true);
            return;
        }
        List<String> groups = preview.getGroupNames();
        if (groups.isEmpty()) {
            validationLabel.setText("No named groups detected -- add (?<name>...) to extract.");
            validationLabel.setStyle("-fx-text-fill: #666;");
            applyBtn.setDisable(true);
            return;
        }
        validationLabel.setText("Valid regex. " + groups.size()
                + (groups.size() == 1 ? " named group detected." : " named groups detected."));
        validationLabel.setStyle("-fx-text-fill: #666;");
        boolean policyOk = overwriteRadio.isSelected() || skipRadio.isSelected();
        applyBtn.setDisable(!policyOk);
        if (!policyOk) {
            setPolicyHintVisible(true, "Choose Overwrite or Skip to enable Apply.");
        }
    }

    private void setPolicyHintVisible(boolean visible, String text) {
        policyHintLabel.setVisible(visible);
        policyHintLabel.setManaged(visible);
        if (visible && text != null)
            policyHintLabel.setText(text);
    }

    private Function<MutableEntryRow, String> sourceResolver() {
        String header = sourceChoice.getValue();
        if (header == null) header = MutableEntryRow.COL_NAME;
        switch (header) {
            case MutableEntryRow.COL_NAME -> {
                return MutableEntryRow::getName;
            }
            case MutableEntryRow.COL_URI -> {
                return MutableEntryRow::getUri;
            }
            default -> {
                final String key = header;
                return r -> r.getMetadata(key);
            }
        }
    }

    private RegexExtractCommand buildCommand() {
        Pattern pattern = preview.getCompiled();
        if (pattern == null)
            return null;
        List<String> groupNames = preview.getGroupNames();
        if (groupNames.isEmpty())
            return null;
        // Resolve override -> destination column names.
        List<String> destColumns = new ArrayList<>();
        Map<String, String> groupToDest = new LinkedHashMap<>();
        for (String name : groupNames) {
            TextField tf = overrideFields.get(name);
            String dest = tf == null ? name : tf.getText();
            if (dest == null || dest.isBlank()) dest = name;
            groupToDest.put(name, dest);
            if (!destColumns.contains(dest))
                destColumns.add(dest);
        }

        CollisionPolicy policy = overwriteRadio.isSelected()
                ? CollisionPolicy.OVERWRITE
                : CollisionPolicy.SKIP;
        boolean skipNonMatching = skipNonMatchingBox.isSelected();
        Set<String> existing = new LinkedHashSet<>(workingCopy.getColumnKeys());

        List<String> newColumns = new ArrayList<>();
        for (String dest : destColumns) {
            if (!existing.contains(dest))
                newColumns.add(dest);
        }

        Function<MutableEntryRow, String> resolver = sourceResolver();
        List<BulkSetCellsCommand.CellDelta> deltas = new ArrayList<>();
        for (MutableEntryRow row : workingCopy.getRows()) {
            String src = resolver.apply(row);
            String safeSrc = src == null ? "" : src;
            Matcher m = pattern.matcher(safeSrc);
            boolean matched = m.find();
            for (Map.Entry<String, String> entry : groupToDest.entrySet()) {
                String group = entry.getKey();
                String dest = entry.getValue();
                String currentValue = row.getMetadata(dest);
                if (!matched) {
                    if (skipNonMatching)
                        continue;
                    deltas.add(new BulkSetCellsCommand.CellDelta(
                            row.getId(), dest, currentValue, ""));
                    continue;
                }
                String captured;
                try {
                    captured = m.group(group);
                } catch (IllegalArgumentException | IllegalStateException ex) {
                    captured = null;
                }
                String newValue = captured == null ? "" : captured;
                boolean existedBefore = existing.contains(dest);
                if (existedBefore && policy == CollisionPolicy.SKIP) {
                    // Only set when the entry's current value is empty.
                    if (currentValue == null || currentValue.isEmpty()) {
                        deltas.add(new BulkSetCellsCommand.CellDelta(
                                row.getId(), dest, currentValue, newValue));
                    }
                    continue;
                }
                if (!currentValue.equals(newValue)) {
                    deltas.add(new BulkSetCellsCommand.CellDelta(
                            row.getId(), dest, currentValue, newValue));
                }
            }
        }
        return new RegexExtractCommand(regexField.getText(), newColumns, deltas);
    }
}
