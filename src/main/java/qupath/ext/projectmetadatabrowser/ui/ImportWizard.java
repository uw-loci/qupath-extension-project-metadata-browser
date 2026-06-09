package qupath.ext.projectmetadatabrowser.ui;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.ext.projectmetadatabrowser.core.ImportCommand;
import qupath.ext.projectmetadatabrowser.model.MutableEntryRow;
import qupath.ext.projectmetadatabrowser.model.WorkingCopy;

/**
 * Three-step Import metadata wizard. Modeled on the v0.2.0
 * {@code qupath-extension-image-export-toolkit} ExportWizard's
 * forward/back navigation shape. Window-modal.
 */
public final class ImportWizard {

    private static final Logger logger = LoggerFactory.getLogger(ImportWizard.class);

    private static final int PREVIEW_CAP = 100;

    private final Window owner;
    private final WorkingCopy workingCopy;

    private final Dialog<ImportCommand> dialog = new Dialog<>();
    private final StackPane stepPane = new StackPane();
    private final Button backBtn;
    private final Button nextBtn;
    private int step = 1;
    private final ButtonType applyType = new ButtonType("Apply", ButtonBar.ButtonData.OK_DONE);

    // Step-1 state
    private final TextField fileField = new TextField();
    private final ChoiceBox<String> separatorChoice = new ChoiceBox<>();
    private final Label step1Status = new Label(" ");
    private final Label step1Header = new Label(" ");
    private final Label step1RowCount = new Label(" ");
    private Path chosenFile;
    private ImportPreviewModel parsed;

    // Step-2 state
    private final ChoiceBox<String> identifierChoice = new ChoiceBox<>();
    private final TableView<ImportPreviewModel.PreviewRow> previewTable = new TableView<>();
    private final Label countsLabel = new Label(" ");

    // Step-3 state
    private final Label summaryLabel = new Label(" ");

    private ImportWizard(Window owner, WorkingCopy workingCopy) {
        this.owner = owner;
        this.workingCopy = workingCopy;
        if (owner != null)
            dialog.initOwner(owner);
        dialog.initModality(Modality.WINDOW_MODAL);
        dialog.setTitle("Import metadata");
        dialog.getDialogPane().getButtonTypes().addAll(applyType, ButtonType.CANCEL);
        backBtn = new Button("< Back");
        nextBtn = new Button("Next >");
        backBtn.setOnAction(e -> goBack());
        nextBtn.setOnAction(e -> goNext());

        HBox navBar = new HBox(8, backBtn, nextBtn);
        VBox content = new VBox(8, stepPane, navBar);
        content.setPadding(new Insets(12));
        VBox.setVgrow(stepPane, Priority.ALWAYS);
        dialog.getDialogPane().setContent(content);
        dialog.setResizable(true);
        Scene scene = dialog.getDialogPane().getScene();
        if (scene != null && scene.getWindow() instanceof Stage stage) {
            stage.setMinWidth(820);
            stage.setMinHeight(600);
        }

        buildStep1();
        showStep(1);

        Button applyBtn = (Button) dialog.getDialogPane().lookupButton(applyType);
        applyBtn.setDisable(true);
        applyBtn.setTooltip(new Tooltip("Apply every queued change as one undoable action."));

        dialog.setResultConverter(bt -> {
            if (bt != applyType || parsed == null)
                return null;
            String fileLabel = chosenFile == null ? "metadata file" : chosenFile.getFileName().toString();
            return new ImportCommand(fileLabel, parsed.getNewColumns(), parsed.getPendingDeltas());
        });
    }

    /**
     * Show the wizard and, on Apply, return the {@link ImportCommand} the
     * caller pushes onto the undo stack. Null on Cancel.
     */
    public static ImportCommand showAndApply(Window owner, WorkingCopy workingCopy) {
        ImportWizard w = new ImportWizard(owner, workingCopy);
        return w.dialog.showAndWait().orElse(null);
    }

    private void showStep(int next) {
        step = next;
        switch (step) {
            case 1 -> {
                stepPane.getChildren().setAll(buildStep1Pane());
                backBtn.setDisable(true);
                nextBtn.setText("Next >");
                nextBtn.setDisable(chosenFile == null || parsed == null);
                setApplyDisabled(true);
            }
            case 2 -> {
                buildStep2();
                stepPane.getChildren().setAll(buildStep2Pane());
                backBtn.setDisable(false);
                nextBtn.setText("Next >");
                nextBtn.setDisable(false);
                setApplyDisabled(true);
            }
            case 3 -> {
                buildStep3();
                stepPane.getChildren().setAll(buildStep3Pane());
                backBtn.setDisable(false);
                nextBtn.setText("Apply");
                nextBtn.setDisable(false);
                setApplyDisabled(parsed == null || parsed.getPendingDeltas().isEmpty());
            }
            default -> {
                // unreachable
            }
        }
    }

    private void setApplyDisabled(boolean disabled) {
        Button applyBtn = (Button) dialog.getDialogPane().lookupButton(applyType);
        if (applyBtn != null)
            applyBtn.setDisable(disabled);
    }

    private void goBack() {
        if (step > 1)
            showStep(step - 1);
    }

    private void goNext() {
        if (step == 3) {
            // Apply: fire the OK button so the result converter runs.
            Button applyBtn = (Button) dialog.getDialogPane().lookupButton(applyType);
            if (applyBtn != null)
                applyBtn.fire();
            return;
        }
        showStep(step + 1);
    }

    // ------------------------------------------------------------------
    // Step 1: choose file
    // ------------------------------------------------------------------

    private VBox buildStep1Pane() {
        Label title = new Label("Step 1 of 3: Choose a metadata file");
        title.setStyle("-fx-font-weight: bold;");

        Label fileLabel = new Label("File:");
        Button browseBtn = new Button("Browse...");
        browseBtn.setTooltip(new Tooltip("Choose the CSV or TSV file to import."));
        browseBtn.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("Choose metadata file to import");
            fc.getExtensionFilters().addAll(
                    new FileChooser.ExtensionFilter("CSV / TSV / TXT", "*.csv", "*.tsv", "*.txt"),
                    new FileChooser.ExtensionFilter("All files", "*.*"));
            File picked = fc.showOpenDialog(dialog.getDialogPane().getScene().getWindow());
            if (picked != null) {
                fileField.setText(picked.getAbsolutePath());
                tryParse(null);
            }
        });
        HBox fileRow = new HBox(6, fileField, browseBtn);
        HBox.setHgrow(fileField, Priority.ALWAYS);

        Label sepLabel = new Label("Separator:");
        separatorChoice.setTooltip(new Tooltip(
                "Override the auto-detected delimiter if it picked wrong."));
        if (separatorChoice.getItems().isEmpty()) {
            separatorChoice.getItems().addAll("comma", "tab", "semicolon");
        }
        separatorChoice.setOnAction(e -> tryParse(toSeparator(separatorChoice.getValue())));
        HBox sepRow = new HBox(6, sepLabel, separatorChoice);

        step1Status.setStyle("-fx-text-fill: #666;");
        step1Status.setWrapText(true);
        step1Header.setStyle("-fx-text-fill: #333;");
        step1Header.setWrapText(true);

        VBox box = new VBox(8, title, fileLabel, fileRow, sepRow, step1Status,
                new Label("Header row:"), step1Header, step1RowCount);
        VBox.setVgrow(box, Priority.ALWAYS);
        return box;
    }

    private void buildStep1() {
        fileField.setPromptText("path to .csv / .tsv / .txt");
        fileField.textProperty().addListener((obs, o, n) -> {
            // user typed -- attempt parse if the file exists
            if (n != null && !n.isBlank()) {
                File f = new File(n);
                if (f.exists() && f.isFile())
                    tryParse(null);
            }
        });
    }

    private void tryParse(Character override) {
        try {
            File f = new File(fileField.getText());
            if (!f.exists() || !f.isFile()) {
                step1Status.setText("Cannot read file.");
                step1Status.setStyle("-fx-text-fill: #c00;");
                step1Header.setText(" ");
                step1RowCount.setText(" ");
                chosenFile = null;
                parsed = null;
                nextBtn.setDisable(true);
                return;
            }
            Path path = f.toPath();
            ImportPreviewModel model = ImportPreviewModel.read(path, override);
            chosenFile = path;
            parsed = model;
            step1Status.setText("Detected separator: " + describeSeparator(model.getSeparator()));
            step1Status.setStyle("-fx-text-fill: #666;");
            // Sync the separator choice without re-triggering parse.
            String want = describeSeparator(model.getSeparator());
            if (!want.equals(separatorChoice.getValue()))
                separatorChoice.setValue(want);
            step1Header.setText(String.join(", ", model.getHeaders()));
            step1RowCount.setText("File has " + model.dataRowCount() + " rows (after header).");
            nextBtn.setDisable(false);
            if (model.dataRowCount() == 0) {
                step1Status.setText("File contains no data rows.");
                step1Status.setStyle("-fx-text-fill: #c00;");
                nextBtn.setDisable(true);
            }
        } catch (IOException ex) {
            logger.warn("Could not read metadata file: {}", ex.getMessage());
            step1Status.setText("Cannot read file: " + ex.getMessage());
            step1Status.setStyle("-fx-text-fill: #c00;");
            chosenFile = null;
            parsed = null;
            nextBtn.setDisable(true);
        }
    }

    // ------------------------------------------------------------------
    // Step 2: preview
    // ------------------------------------------------------------------

    private void buildStep2() {
        identifierChoice.getItems().setAll();
        if (parsed == null)
            return;
        List<String> matchable = new ArrayList<>();
        for (String h : parsed.getHeaders()) {
            if (h.equalsIgnoreCase(MutableEntryRow.COL_ID)
                    || h.equalsIgnoreCase(MutableEntryRow.COL_NAME)
                    || h.equalsIgnoreCase("Image ID")
                    || h.equalsIgnoreCase("Image Name")) {
                matchable.add(h);
            }
        }
        if (matchable.isEmpty())
            matchable.addAll(parsed.getHeaders());
        identifierChoice.getItems().setAll(matchable);
        String preferred = null;
        for (String h : matchable) {
            if (h.equalsIgnoreCase("Image ID") || h.equalsIgnoreCase(MutableEntryRow.COL_ID)) {
                preferred = h;
                break;
            }
        }
        if (preferred == null && !matchable.isEmpty())
            preferred = matchable.get(0);
        identifierChoice.setValue(preferred);
        if (preferred != null)
            parsed.computePreview(workingCopy, preferred);
        identifierChoice.setOnAction(e -> {
            if (parsed != null && identifierChoice.getValue() != null) {
                parsed.computePreview(workingCopy, identifierChoice.getValue());
                refreshPreviewTable();
            }
        });
    }

    private VBox buildStep2Pane() {
        Label title = new Label("Step 2 of 3: Preview the import");
        title.setStyle("-fx-font-weight: bold;");
        HBox idRow = new HBox(6, new Label("Match on identifier column:"), identifierChoice);
        refreshPreviewTable();
        VBox box = new VBox(8, title, idRow, previewTable, countsLabel);
        VBox.setVgrow(previewTable, Priority.ALWAYS);
        return box;
    }

    private void refreshPreviewTable() {
        previewTable.getColumns().clear();
        if (parsed == null || parsed.getIdentifierHeader() == null)
            return;
        TableColumn<ImportPreviewModel.PreviewRow, String> stateCol = new TableColumn<>("State");
        stateCol.setCellValueFactory(cdf -> new ReadOnlyStringWrapper(stateGlyph(cdf.getValue().state())));
        stateCol.setPrefWidth(72);
        previewTable.getColumns().add(stateCol);

        TableColumn<ImportPreviewModel.PreviewRow, String> idCol = new TableColumn<>(parsed.getIdentifierHeader());
        idCol.setCellValueFactory(cdf -> new ReadOnlyStringWrapper(cdf.getValue().fileIdentifierValue()));
        idCol.setPrefWidth(160);
        previewTable.getColumns().add(idCol);

        TableColumn<ImportPreviewModel.PreviewRow, String> matchCol = new TableColumn<>("Project entry");
        matchCol.setCellValueFactory(cdf -> new ReadOnlyStringWrapper(cdf.getValue().projectEntryName()));
        matchCol.setPrefWidth(180);
        previewTable.getColumns().add(matchCol);

        List<String> dataHeaders = parsed.getDataHeaders();
        for (int idx = 0; idx < dataHeaders.size(); idx++) {
            int colIdx = idx;
            String h = dataHeaders.get(idx);
            TableColumn<ImportPreviewModel.PreviewRow, String> col = new TableColumn<>(h);
            col.setCellValueFactory(cdf -> {
                List<String> vals = cdf.getValue().dataValues();
                return new ReadOnlyStringWrapper(colIdx < vals.size() ? vals.get(colIdx) : "");
            });
            col.setPrefWidth(140);
            previewTable.getColumns().add(col);
        }
        ObservableList<ImportPreviewModel.PreviewRow> rows =
                FXCollections.observableArrayList(parsed.getPreviewSlice(PREVIEW_CAP));
        previewTable.setItems(rows);
        ImportPreviewModel.Counts c = parsed.getCounts();
        StringBuilder sb = new StringBuilder();
        sb.append("[+] ").append(c.add()).append(" entries will gain new column values; ");
        sb.append("[~] ").append(c.update()).append(" entries will have values updated; ");
        sb.append("[ ] ").append(c.unchanged()).append(" entries unchanged; ");
        sb.append("[!] ").append(c.noMatch()).append(" file rows did not match (will be skipped); ");
        sb.append(c.entriesMissingFromFile()).append(" project entries have no row in this file (left unchanged).");
        countsLabel.setText(sb.toString());
        countsLabel.setWrapText(true);
    }

    // ------------------------------------------------------------------
    // Step 3: apply
    // ------------------------------------------------------------------

    private void buildStep3() {
        if (parsed == null) {
            summaryLabel.setText("No preview computed; go back to step 2.");
            return;
        }
        ImportPreviewModel.Counts c = parsed.getCounts();
        StringBuilder sb = new StringBuilder();
        if (!parsed.getNewColumns().isEmpty()) {
            sb.append("Will add ").append(parsed.getNewColumns().size())
                    .append(" new column(s): ")
                    .append(String.join(", ", parsed.getNewColumns())).append('\n');
        }
        sb.append("Will update ").append(c.update() + c.add()).append(" rows.\n");
        sb.append(c.noMatch()).append(" file rows did not match and will be skipped.\n");
        sb.append(c.entriesMissingFromFile()).append(" project entries have no row in this file (left unchanged).\n\n");
        sb.append("The import will be a single undoable action (Ctrl+Z reverts the entire import).\n\n");
        sb.append("Apply now? Edits will live in the working copy; Save commits.");
        summaryLabel.setText(sb.toString());
        summaryLabel.setWrapText(true);
    }

    private VBox buildStep3Pane() {
        Label title = new Label("Step 3 of 3: Apply the import");
        title.setStyle("-fx-font-weight: bold;");
        VBox box = new VBox(8, title, summaryLabel);
        VBox.setVgrow(summaryLabel, Priority.ALWAYS);
        return box;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static String stateGlyph(ImportPreviewModel.RowState state) {
        return switch (state) {
            case ADD -> "[+] add";
            case UPDATE -> "[~] update";
            case UNCHANGED -> "[ ] same";
            case NO_MATCH -> "[!] no match";
        };
    }

    private static String describeSeparator(char c) {
        return switch (c) {
            case '\t' -> "tab";
            case ';' -> "semicolon";
            default -> "comma";
        };
    }

    private static Character toSeparator(String label) {
        if (label == null) return null;
        return switch (label) {
            case "tab" -> '\t';
            case "semicolon" -> ';';
            case "comma" -> ',';
            default -> null;
        };
    }
}
