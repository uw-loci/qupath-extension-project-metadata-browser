package qupath.ext.projectmetadatabrowser.ui;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Comparator;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.ext.projectmetadatabrowser.core.MetadataKeyOperations;
import qupath.ext.projectmetadatabrowser.core.MetadataKeyOperations.CollisionPolicy;
import qupath.ext.projectmetadatabrowser.model.MetadataKeyRow;
import qupath.ext.projectmetadatabrowser.model.MetadataModel;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.projects.Project;

/**
 * Tab content controller for the "Metadata Keys" tab in the Metadata Browser
 * window. Owns the keys {@link TableView}, the amber warning banner, the
 * filter / Rename / Delete toolbar, and the wiring back to the parent window
 * for cross-tab refresh after a mutation.
 *
 * <p>Single-select per Phase 1 (multi-select Delete deferred to v1.1). The
 * Rename and Delete buttons are disabled when no row is selected or when the
 * project is closed.
 */
public final class MetadataKeysTab {

    private static final Logger logger = LoggerFactory.getLogger(MetadataKeysTab.class);

    private final QuPathGUI qupath;
    private final MetadataModel model;
    private final Runnable refreshCallback;
    private final Consumer<String> statusMessageHandler;
    private final Runnable countChangeListener;

    private final BorderPane root = new BorderPane();
    private final TableView<MetadataKeyRow> table = new TableView<>();
    private final TextField filterField = new TextField();
    private final Button renameButton = new Button("Rename...");
    private final Button deleteButton = new Button("Delete...");
    private final FilteredList<MetadataKeyRow> filtered;
    private final SortedList<MetadataKeyRow> sorted;

    /**
     * @param qupath the QuPath GUI used to read the active project.
     * @param model the shared metadata model; this tab reads
     *              {@link MetadataModel#getKeyRows()}.
     * @param refreshCallback a callback that triggers a full reload of both
     *                        tabs after a successful mutation. Typically
     *                        wired to {@code MetadataBrowserWindow.reloadFromProject()}.
     * @param statusMessageHandler a consumer that posts a transient status
     *                             message in the parent window's status line
     *                             after a successful mutation. The parent
     *                             owns the PauseTransition that reverts the
     *                             message after ~5 seconds.
     */
    public MetadataKeysTab(QuPathGUI qupath,
                           MetadataModel model,
                           Runnable refreshCallback,
                           Consumer<String> statusMessageHandler,
                           Runnable countChangeListener) {
        this.qupath = Objects.requireNonNull(qupath, "qupath");
        this.model = Objects.requireNonNull(model, "model");
        this.refreshCallback = Objects.requireNonNull(refreshCallback, "refreshCallback");
        this.statusMessageHandler = Objects.requireNonNull(statusMessageHandler, "statusMessageHandler");
        this.countChangeListener = Objects.requireNonNull(countChangeListener, "countChangeListener");

        filtered = new FilteredList<>(model.getKeyRows(), r -> true);
        sorted = new SortedList<>(filtered);
        sorted.comparatorProperty().bind(table.comparatorProperty());
        // Filter and model-size changes -> notify parent so the status line
        // "Keys: N shown / N total" stays current.
        filtered.predicateProperty().addListener((obs, o, n) -> this.countChangeListener.run());
        filtered.addListener((javafx.collections.ListChangeListener<MetadataKeyRow>) c -> this.countChangeListener.run());

        root.setTop(buildTopRegion());
        root.setCenter(buildCenterRegion());
    }

    /**
     * The tab's root scene node. Wrap in a {@code Tab} at the caller site.
     */
    public BorderPane getRoot() {
        return root;
    }

    /**
     * The current count of visible (post-filter) keys for the parent
     * window's status line.
     */
    public int getFilteredKeyCount() {
        return filtered.size();
    }

    /**
     * The total number of keys in the model. Use with
     * {@link #getFilteredKeyCount()} to compose the "Keys: N shown / N total"
     * status text.
     */
    public int getTotalKeyCount() {
        return model.getKeyRows().size();
    }

    private VBox buildTopRegion() {
        Label banner = new Label(
                "[!] Renaming or deleting a key changes every image entry that uses it. "
                        + "This cannot be undone -- back up the project first if you are unsure. "
                        + "(See User Guide -> Metadata Keys.)");
        banner.setWrapText(true);
        banner.setMaxWidth(Double.MAX_VALUE);
        banner.setStyle("-fx-background-color: #fff3cd; -fx-text-fill: #663c00; -fx-padding: 8; "
                + "-fx-border-color: #e0a800; -fx-border-width: 0 0 1 0;");
        HBox bannerBox = new HBox(banner);
        HBox.setHgrow(banner, Priority.ALWAYS);

        filterField.setPromptText("Filter keys (case-insensitive)");
        filterField.setTooltip(new Tooltip("Filter the key list by substring (case-insensitive)."));
        filterField.textProperty().addListener((obs, o, n) -> applyFilter());
        HBox.setHgrow(filterField, Priority.ALWAYS);

        renameButton.setTooltip(new Tooltip(
                "Rename the selected key everywhere it appears in the project."));
        renameButton.setDisable(true);
        renameButton.setOnAction(e -> onRename());

        deleteButton.setTooltip(new Tooltip(
                "Remove the selected key from every entry that has it. Cannot be undone."));
        deleteButton.setDisable(true);
        deleteButton.setOnAction(e -> onDelete());

        Region toolbarSpacer = new Region();
        HBox toolbar = new HBox(8,
                new Label("Filter keys:"),
                filterField,
                toolbarSpacer,
                renameButton,
                deleteButton);
        HBox.setHgrow(toolbarSpacer, Priority.NEVER);
        toolbar.setStyle("-fx-padding: 8;");

        VBox top = new VBox(bannerBox, toolbar);
        return top;
    }

    private TableView<MetadataKeyRow> buildCenterRegion() {
        table.setItems(sorted);
        table.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
        table.setTableMenuButtonVisible(true);
        table.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        table.setPlaceholder(new Label(
                "This project has no user-set metadata keys. Add metadata from the "
                        + "Entries tab or via a script, then click Refresh."));

        TableColumn<MetadataKeyRow, String> keyCol = new TableColumn<>("Key");
        keyCol.setCellValueFactory(cdf -> new ReadOnlyStringWrapper(cdf.getValue().getKey()));
        keyCol.setCellFactory(c -> new TooltipKeyCell());
        keyCol.setPrefWidth(220);
        keyCol.setMinWidth(80);
        keyCol.setSortable(true);
        // Case-insensitive sort so uppercase keys do not bubble above lowercase.
        keyCol.setComparator(Comparator.comparing(String::toLowerCase));

        TableColumn<MetadataKeyRow, MetadataKeyRow> usedByCol = new TableColumn<>("Used by");
        usedByCol.setCellValueFactory(cdf -> new javafx.beans.property.ReadOnlyObjectWrapper<>(cdf.getValue()));
        usedByCol.setCellFactory(c -> new UsedByCell());
        usedByCol.setPrefWidth(110);
        usedByCol.setMinWidth(70);
        usedByCol.setSortable(true);
        usedByCol.setComparator(Comparator.comparingInt(MetadataKeyRow::getEntryCount));

        TableColumn<MetadataKeyRow, MetadataKeyRow> sampleCol = new TableColumn<>("Sample value");
        sampleCol.setCellValueFactory(cdf -> new javafx.beans.property.ReadOnlyObjectWrapper<>(cdf.getValue()));
        sampleCol.setCellFactory(c -> new SampleValueCell());
        sampleCol.setPrefWidth(280);
        sampleCol.setMinWidth(80);
        sampleCol.setSortable(true);
        sampleCol.setComparator(Comparator.comparing(r -> r.getSampleValue(), Comparator.nullsFirst(String::compareTo)));

        table.getColumns().add(keyCol);
        table.getColumns().add(usedByCol);
        table.getColumns().add(sampleCol);
        table.getSortOrder().add(keyCol);

        // Selection -> button enable/disable.
        table.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> updateButtonStates());

        // Refresh button states when the model is reloaded (the selected row may
        // disappear or its count may drop to zero).
        model.getKeyRows().addListener((javafx.collections.ListChangeListener<MetadataKeyRow>) c -> updateButtonStates());

        // Right-click context menu mirrors the toolbar buttons.
        ContextMenu rowMenu = new ContextMenu();
        MenuItem renameItem = new MenuItem("Rename...");
        renameItem.setOnAction(e -> onRename());
        MenuItem deleteItem = new MenuItem("Delete...");
        deleteItem.setOnAction(e -> onDelete());
        rowMenu.getItems().addAll(renameItem, new SeparatorMenuItem(), deleteItem);
        rowMenu.setOnShowing(e -> {
            boolean hasSelection = table.getSelectionModel().getSelectedItem() != null;
            renameItem.setDisable(!hasSelection || qupath.getProject() == null);
            deleteItem.setDisable(!hasSelection || qupath.getProject() == null);
        });
        table.setRowFactory(tv -> {
            TableRow<MetadataKeyRow> row = new TableRow<>();
            row.setContextMenu(rowMenu);
            return row;
        });

        return table;
    }

    /**
     * Focus the filter field. Convenience for the parent window to call when
     * the Keys tab is activated by a keyboard shortcut.
     */
    public void requestFilterFocus() {
        filterField.requestFocus();
    }

    private void applyFilter() {
        String raw = filterField.getText();
        if (raw == null || raw.isBlank()) {
            filtered.setPredicate(r -> true);
            return;
        }
        String needle = raw.toLowerCase();
        Predicate<MetadataKeyRow> p = row -> row.getKey().toLowerCase().contains(needle);
        filtered.setPredicate(p);
    }

    private void updateButtonStates() {
        MetadataKeyRow selected = table.getSelectionModel().getSelectedItem();
        boolean projectOpen = qupath.getProject() != null;
        boolean enabled = selected != null && projectOpen && selected.getEntryCount() > 0;
        renameButton.setDisable(!enabled);
        deleteButton.setDisable(!enabled);
    }

    private void onRename() {
        MetadataKeyRow selected = table.getSelectionModel().getSelectedItem();
        if (selected == null)
            return;
        Project<BufferedImage> project = qupath.getProject();
        if (project == null) {
            Dialogs.showErrorNotification("Project Metadata Browser",
                    "No project is open. Open a project before renaming keys.");
            return;
        }

        RenameKeyDialog.Result result = RenameKeyDialog.showFor(
                root.getScene() == null ? null : root.getScene().getWindow(),
                selected.getKey(),
                selected.getEntryCount());
        if (result == null)
            return;

        String oldKey = selected.getKey();
        String newKey = result.newKey();
        CollisionPolicy policy = result.policy();
        try {
            MetadataKeyOperations.Result opResult =
                    MetadataKeyOperations.renameAcrossProject(project, oldKey, newKey, policy);
            String message;
            if (opResult.mutated() == 1) {
                message = "Renamed '" + oldKey + "' to '" + newKey + "' on 1 entry.";
            } else {
                message = "Renamed '" + oldKey + "' to '" + newKey + "' across "
                        + opResult.mutated() + " entries.";
            }
            logger.info(message);
            statusMessageHandler.accept(message);
            refreshCallback.run();
            selectKeyAfterRefresh(newKey);
        } catch (IOException e) {
            logger.error("Rename of '{}' -> '{}' failed; changes reverted", oldKey, newKey, e);
            Dialogs.showErrorNotification("Project Metadata Browser",
                    "Could not save metadata changes. Reverted. "
                            + "Check that the project file is writable.");
            refreshCallback.run();
        }
    }

    private void onDelete() {
        MetadataKeyRow selected = table.getSelectionModel().getSelectedItem();
        if (selected == null)
            return;
        Project<BufferedImage> project = qupath.getProject();
        if (project == null) {
            Dialogs.showErrorNotification("Project Metadata Browser",
                    "No project is open. Open a project before deleting keys.");
            return;
        }

        String key = selected.getKey();
        int n = selected.getEntryCount();
        String entriesWord = (n == 1) ? "1 entry" : n + " entries";
        String deleteLabel = (n == 1) ? "Delete from 1 entry" : "Delete from " + n + " entries";

        // Build the confirmation Alert manually so we can apply the destructive
        // -fx-base styling and set focus to Cancel, per design 02a Section 4.
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Delete metadata key");
        alert.setHeaderText("[!] Delete the metadata key \"" + key + "\"?");
        alert.setContentText("This key will be removed from " + entriesWord + ". "
                + "This cannot be undone. Back up the project first if you are unsure.");
        if (root.getScene() != null && root.getScene().getWindow() != null)
            alert.initOwner(root.getScene().getWindow());

        ButtonType deleteType = new ButtonType(deleteLabel, ButtonBar.ButtonData.OK_DONE);
        alert.getButtonTypes().setAll(deleteType, ButtonType.CANCEL);

        // Style the destructive button. Don't make it the default; Cancel is the
        // cancel button. Enter is a no-op on this alert.
        Button deleteBtn = (Button) alert.getDialogPane().lookupButton(deleteType);
        deleteBtn.setStyle("-fx-base: #c33;");
        deleteBtn.setDefaultButton(false);
        deleteBtn.setTooltip(new Tooltip(
                "Remove this metadata key from " + entriesWord + ". This cannot be undone."));

        Button cancelBtn = (Button) alert.getDialogPane().lookupButton(ButtonType.CANCEL);
        cancelBtn.setDefaultButton(false);
        cancelBtn.setCancelButton(true);
        cancelBtn.setTooltip(new Tooltip("Close without removing any keys."));
        // Schedule focus on Cancel after the alert lays out -- if we requestFocus
        // synchronously the Alert's default-button machinery may steal it back.
        javafx.application.Platform.runLater(cancelBtn::requestFocus);

        java.util.Optional<ButtonType> choice = alert.showAndWait();
        if (choice.isEmpty() || choice.get() != deleteType)
            return;

        try {
            MetadataKeyOperations.Result opResult =
                    MetadataKeyOperations.removeAcrossProject(project, key);
            String message;
            if (opResult.mutated() == 1) {
                message = "Removed '" + key + "' from 1 entry.";
            } else {
                message = "Removed '" + key + "' from " + opResult.mutated() + " entries.";
            }
            logger.info(message);
            statusMessageHandler.accept(message);
            refreshCallback.run();
        } catch (IOException e) {
            logger.error("Remove of '{}' failed; changes reverted", key, e);
            Dialogs.showErrorNotification("Project Metadata Browser",
                    "Could not save metadata changes. Reverted. "
                            + "Check that the project file is writable.");
            refreshCallback.run();
        }
    }

    /**
     * After a refresh, try to restore selection to {@code key}. If the model
     * no longer contains it (e.g. the rename produced a key that was filtered
     * out), clear selection.
     */
    private void selectKeyAfterRefresh(String key) {
        for (MetadataKeyRow row : sorted) {
            if (row.getKey().equals(key)) {
                table.getSelectionModel().select(row);
                table.scrollTo(row);
                return;
            }
        }
        table.getSelectionModel().clearSelection();
    }

    /** Show the full key string in a tooltip on hover. */
    private static final class TooltipKeyCell extends TableCell<MetadataKeyRow, String> {
        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null || item.isEmpty()) {
                setText(null);
                setTooltip(null);
            } else {
                setText(item);
                Tooltip tt = getTooltip();
                if (tt == null) {
                    tt = new Tooltip();
                    tt.setWrapText(true);
                    tt.setMaxWidth(600);
                    setTooltip(tt);
                }
                tt.setText(item);
            }
        }
    }

    /** Right-aligned "N entries" cell with project-size tooltip. */
    private final class UsedByCell extends TableCell<MetadataKeyRow, MetadataKeyRow> {
        UsedByCell() {
            setStyle("-fx-alignment: CENTER-RIGHT;");
        }

        @Override
        protected void updateItem(MetadataKeyRow item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setTooltip(null);
            } else {
                setText(item.getEntryCountDisplay());
                int total = model.getRows().size();
                Tooltip tt = getTooltip();
                if (tt == null) {
                    tt = new Tooltip();
                    setTooltip(tt);
                }
                tt.setText("Used by " + item.getEntryCount() + " out of " + total + " entries.");
            }
        }
    }

    /** Sample-value cell with full-value + source-entry tooltip. */
    private static final class SampleValueCell extends TableCell<MetadataKeyRow, MetadataKeyRow> {
        SampleValueCell() {
            setWrapText(true);
        }

        @Override
        protected void updateItem(MetadataKeyRow item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setTooltip(null);
                return;
            }
            String value = item.getSampleValue();
            setText(value);
            if (value == null || value.isEmpty()) {
                setTooltip(null);
                return;
            }
            Tooltip tt = getTooltip();
            if (tt == null) {
                tt = new Tooltip();
                tt.setWrapText(true);
                tt.setMaxWidth(600);
                setTooltip(tt);
            }
            String src = item.getSampleEntryName();
            if (src == null || src.isEmpty())
                tt.setText(value);
            else
                tt.setText(value + "\nFrom entry: " + src);
        }
    }
}
