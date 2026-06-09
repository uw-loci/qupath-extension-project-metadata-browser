package qupath.ext.projectmetadatabrowser.ui;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.ext.projectmetadatabrowser.core.TemplateExporter;
import qupath.ext.projectmetadatabrowser.model.MutableEntryRow;
import qupath.fx.dialogs.Dialogs;

/**
 * Dialog for File > Export > Template for fill-in. Lets the user pick:
 * <ul>
 *   <li>which identifier columns to include (Image ID / Name / URI /
 *       Description / Tags);</li>
 *   <li>which existing user-metadata keys to seed with current values;</li>
 *   <li>brand-new column headers to leave blank for the partner;</li>
 *   <li>CSV (.csv) vs TSV (.tsv) and the output file path.</li>
 * </ul>
 *
 * <p>Writes the file via {@link TemplateExporter}. Window-modal.
 */
public final class TemplateExportDialog {

    private static final Logger logger = LoggerFactory.getLogger(TemplateExportDialog.class);

    private TemplateExportDialog() {
        // utility class -- no instances
    }

    /**
     * Show the dialog and, on Export, write the template file. Returns the
     * path written, or {@code null} if the user cancelled or the write
     * failed (in which case an error notification was already shown).
     */
    public static Path showAndExport(Window owner,
                                      List<MutableEntryRow> rows,
                                      List<String> existingUserKeys,
                                      String projectName) {
        Dialog<Path> dialog = new Dialog<>();
        dialog.setTitle("Export template for fill-in");
        if (owner != null)
            dialog.initOwner(owner);
        dialog.initModality(Modality.WINDOW_MODAL);

        ButtonType exportType = new ButtonType("Export", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(exportType, ButtonType.CANCEL);

        // Identifier picker (dual list).
        Label identifierLabel = new Label("Identifier columns to include (left for you to match on)");
        identifierLabel.setStyle("-fx-font-weight: bold;");

        ObservableList<String> available = FXCollections.observableArrayList(
                MutableEntryRow.COL_URI, MutableEntryRow.COL_DESCRIPTION, MutableEntryRow.COL_TAGS);
        ObservableList<String> included = FXCollections.observableArrayList(
                MutableEntryRow.COL_ID, MutableEntryRow.COL_NAME);

        ListView<String> availableList = new ListView<>(available);
        ListView<String> includedList = new ListView<>(included);
        availableList.setPrefHeight(120);
        includedList.setPrefHeight(120);

        Button addBtn = new Button(">>");
        addBtn.setTooltip(new Tooltip("Include the selected identifier column in the template."));
        addBtn.setOnAction(e -> {
            String sel = availableList.getSelectionModel().getSelectedItem();
            if (sel != null) {
                available.remove(sel);
                included.add(sel);
            }
        });
        Button removeBtn = new Button("<<");
        removeBtn.setTooltip(new Tooltip("Remove the selected identifier column from the template."));
        removeBtn.setOnAction(e -> {
            String sel = includedList.getSelectionModel().getSelectedItem();
            if (sel != null) {
                included.remove(sel);
                available.add(sel);
            }
        });
        VBox moveButtons = new VBox(6, addBtn, removeBtn);
        moveButtons.setPadding(new Insets(20, 4, 0, 4));

        HBox dualList = new HBox(6,
                labeledColumn("Available", availableList),
                moveButtons,
                labeledColumn("Included", includedList));
        HBox.setHgrow(dualList, Priority.ALWAYS);

        // Seed existing keys with current values: a FlowPane of checkboxes.
        Label seedLabel = new Label("Seed with current values from existing keys");
        seedLabel.setStyle("-fx-font-weight: bold;");
        Label seedHint = new Label("Check a key to seed its column with each entry's current value.");
        seedHint.setStyle("-fx-text-fill: #666;");

        FlowPane keysFlow = new FlowPane();
        keysFlow.setHgap(12);
        keysFlow.setVgap(6);
        List<String> sortedKeys = new ArrayList<>(existingUserKeys);
        sortedKeys.sort(Comparator.naturalOrder());
        List<CheckBox> keyCheckboxes = new ArrayList<>();
        for (String key : sortedKeys) {
            CheckBox cb = new CheckBox(key);
            cb.setTooltip(new Tooltip(
                    "Include '" + key + "' as a column seeded with each entry's current value."));
            keysFlow.getChildren().add(cb);
            keyCheckboxes.add(cb);
        }
        ScrollPane keysScroll = new ScrollPane(keysFlow);
        keysScroll.setFitToWidth(true);
        keysScroll.setPrefViewportHeight(120);

        // New blank columns (comma-separated).
        Label blankLabel = new Label("Add new blank columns (comma-separated)");
        blankLabel.setStyle("-fx-font-weight: bold;");
        TextField blankField = new TextField();
        blankField.setPromptText("Condition, Timepoint, Pathologist notes");
        blankField.setTooltip(new Tooltip(
                "Comma-separated headers for blank columns the partner will fill in."));
        Label blankNote = new Label(" ");
        blankNote.setStyle("-fx-text-fill: #666;");
        blankNote.setWrapText(true);
        blankNote.setMinHeight(24);

        // Format radios.
        ToggleGroup formatGroup = new ToggleGroup();
        RadioButton csvRadio = new RadioButton("CSV (.csv)");
        csvRadio.setToggleGroup(formatGroup);
        csvRadio.setSelected(true);
        csvRadio.setTooltip(new Tooltip("CSV is the default and opens cleanly in Excel."));
        RadioButton tsvRadio = new RadioButton("TSV (.tsv)");
        tsvRadio.setToggleGroup(formatGroup);
        tsvRadio.setTooltip(new Tooltip("TSV avoids quoting; useful for partners whose Excel locale uses semicolons."));
        HBox formatRow = new HBox(12, new Label("Format:"), csvRadio, tsvRadio);

        // File path + Browse.
        Label fileLabel = new Label("Save to:");
        TextField fileField = new TextField();
        String safeName = projectName == null || projectName.isBlank() ? "project" : projectName;
        String defaultName = safeName + "-metadata-template.csv";
        File initialDir = new File(System.getProperty("user.home", "."));
        fileField.setText(new File(initialDir, defaultName).getAbsolutePath());
        Button browseBtn = new Button("Browse...");
        browseBtn.setTooltip(new Tooltip("Choose where to write the template file."));
        browseBtn.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("Choose template output file");
            String extLabel = tsvRadio.isSelected() ? "TSV (*.tsv)" : "CSV (*.csv)";
            String extGlob = tsvRadio.isSelected() ? "*.tsv" : "*.csv";
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter(extLabel, extGlob));
            String current = fileField.getText();
            if (current != null && !current.isBlank()) {
                File pre = new File(current);
                if (pre.getParentFile() != null && pre.getParentFile().isDirectory())
                    fc.setInitialDirectory(pre.getParentFile());
                fc.setInitialFileName(pre.getName());
            }
            File picked = fc.showSaveDialog(dialog.getDialogPane().getScene().getWindow());
            if (picked != null)
                fileField.setText(picked.getAbsolutePath());
        });
        HBox fileRow = new HBox(6, fileField, browseBtn);
        HBox.setHgrow(fileField, Priority.ALWAYS);

        // Swap extension when the format radio flips.
        Runnable swapExt = () -> {
            String current = fileField.getText();
            if (current == null || current.isBlank())
                return;
            boolean wantTsv = tsvRadio.isSelected();
            int dot = current.lastIndexOf('.');
            String stem = dot < 0 ? current : current.substring(0, dot);
            fileField.setText(stem + (wantTsv ? ".tsv" : ".csv"));
        };
        csvRadio.selectedProperty().addListener((obs, o, n) -> swapExt.run());
        tsvRadio.selectedProperty().addListener((obs, o, n) -> swapExt.run());

        VBox content = new VBox(10,
                identifierLabel,
                dualList,
                seedLabel,
                seedHint,
                keysScroll,
                blankLabel,
                blankField,
                blankNote,
                formatRow,
                fileLabel,
                fileRow);
        content.setPadding(new Insets(12));
        dialog.getDialogPane().setContent(content);
        dialog.setResizable(true);
        Scene scene = dialog.getDialogPane().getScene();
        if (scene != null && scene.getWindow() instanceof Stage stage) {
            stage.setMinWidth(620);
            stage.setMinHeight(540);
        }

        Button exportBtn = (Button) dialog.getDialogPane().lookupButton(exportType);
        exportBtn.setDefaultButton(true);
        exportBtn.setTooltip(new Tooltip("Write the template file and close the dialog."));

        Runnable validate = () -> {
            boolean hasIdent = !included.isEmpty();
            boolean hasPath = fileField.getText() != null && !fileField.getText().isBlank();
            exportBtn.setDisable(!(hasIdent && hasPath));
            // Warn if any blank token collides with an existing key.
            Set<String> existingSet = new LinkedHashSet<>(existingUserKeys);
            List<String> tokens = parseBlanks(blankField.getText());
            for (String t : tokens) {
                if (existingSet.contains(t)) {
                    blankNote.setText("Note: '" + t + "' already exists in the project. "
                            + "The template will write it blank, not seeded.");
                    return;
                }
            }
            blankNote.setText(" ");
        };
        included.addListener((javafx.collections.ListChangeListener<String>) c -> validate.run());
        fileField.textProperty().addListener((obs, o, n) -> validate.run());
        blankField.textProperty().addListener((obs, o, n) -> validate.run());
        validate.run();

        dialog.setResultConverter(bt -> {
            if (bt != exportType)
                return null;
            List<TemplateExporter.IdentifierColumn> idCols = new ArrayList<>();
            for (String h : included)
                idCols.add(toIdentifierColumn(h));
            List<String> seeded = new ArrayList<>();
            for (CheckBox cb : keyCheckboxes)
                if (cb.isSelected()) seeded.add(cb.getText());
            List<String> blanks = parseBlanks(blankField.getText());
            boolean tsv = tsvRadio.isSelected();
            TemplateExporter.Config config = new TemplateExporter.Config(idCols, seeded, blanks, tsv);
            String path = fileField.getText();
            if (path == null || path.isBlank())
                return null;
            return Path.of(path);
        });

        Optional<Path> picked = dialog.showAndWait();
        if (picked.isEmpty())
            return null;
        Path dest = picked.get();
        // Re-pull the config -- result converter is run before the dialog
        // is hidden, so we resolve the choices a second time here.
        List<TemplateExporter.IdentifierColumn> idCols = new ArrayList<>();
        for (String h : included)
            idCols.add(toIdentifierColumn(h));
        List<String> seeded = new ArrayList<>();
        for (CheckBox cb : keyCheckboxes)
            if (cb.isSelected()) seeded.add(cb.getText());
        List<String> blanks = parseBlanks(blankField.getText());
        boolean tsv = tsvRadio.isSelected();
        TemplateExporter.Config config = new TemplateExporter.Config(idCols, seeded, blanks, tsv);
        try {
            int n = TemplateExporter.writeTemplate(rows, config, dest);
            Dialogs.showInfoNotification("Project Metadata Browser",
                    "Exported template with " + n + " rows to " + dest.getFileName());
            return dest;
        } catch (IOException ex) {
            logger.error("Template export failed", ex);
            Dialogs.showErrorNotification("Project Metadata Browser",
                    "Could not write template file: " + ex.getMessage());
            return null;
        }
    }

    private static VBox labeledColumn(String label, ListView<String> list) {
        Label l = new Label(label);
        l.setStyle("-fx-font-weight: bold;");
        VBox box = new VBox(2, l, list);
        VBox.setVgrow(list, Priority.ALWAYS);
        HBox.setHgrow(box, Priority.ALWAYS);
        return box;
    }

    private static TemplateExporter.IdentifierColumn toIdentifierColumn(String header) {
        Function<MutableEntryRow, String> resolver;
        switch (header) {
            case MutableEntryRow.COL_NAME -> resolver = MutableEntryRow::getName;
            case MutableEntryRow.COL_ID -> resolver = MutableEntryRow::getId;
            case MutableEntryRow.COL_URI -> resolver = MutableEntryRow::getUri;
            case MutableEntryRow.COL_DESCRIPTION -> resolver = MutableEntryRow::getDescription;
            case MutableEntryRow.COL_TAGS -> resolver = MutableEntryRow::getTags;
            default -> resolver = r -> "";
        }
        return new TemplateExporter.IdentifierColumn(header, resolver);
    }

    private static List<String> parseBlanks(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank())
            return out;
        for (String tok : raw.split(",")) {
            String t = tok.trim();
            if (!t.isEmpty() && !out.contains(t))
                out.add(t);
        }
        return out;
    }
}
