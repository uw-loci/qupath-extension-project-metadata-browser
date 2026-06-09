package qupath.ext.projectmetadatabrowser.ui;

import java.awt.image.BufferedImage;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.value.ChangeListener;
import javafx.collections.ListChangeListener;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Spinner;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TablePosition;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.Duration;
import javafx.animation.PauseTransition;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.ext.projectmetadatabrowser.Preferences;
import qupath.ext.projectmetadatabrowser.core.AddColumnCommand;
import qupath.ext.projectmetadatabrowser.core.BulkSetCellsCommand;
import qupath.ext.projectmetadatabrowser.core.ImportCommand;
import qupath.ext.projectmetadatabrowser.core.MetadataCommand;
import qupath.ext.projectmetadatabrowser.core.MetadataKeyOperations;
import qupath.ext.projectmetadatabrowser.core.RegexExtractCommand;
import qupath.ext.projectmetadatabrowser.core.SetCellCommand;
import qupath.ext.projectmetadatabrowser.core.UndoStack;
import qupath.ext.projectmetadatabrowser.model.MetadataModel;
import qupath.ext.projectmetadatabrowser.model.MutableEntryRow;
import qupath.ext.projectmetadatabrowser.model.WorkingCopy;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.projects.Project;

/**
 * Non-modal browser window. Single instance per QuPath session; reused when
 * the menu item is invoked again.
 *
 * <p>v1.1 reshape: window holds a {@link WorkingCopy} that buffers every
 * edit, an {@link UndoStack} the menu bar reflects, and a Save action that
 * delegates to
 * {@link MetadataKeyOperations#commitWorkingCopy(Project, java.util.List)}.
 */
public class MetadataBrowserWindow {

    private static final Logger logger = LoggerFactory.getLogger(MetadataBrowserWindow.class);

    private static MetadataBrowserWindow instance;

    private final QuPathGUI qupath;
    private final Stage stage;
    private final MetadataModel model = new MetadataModel();
    private final WorkingCopy workingCopy = model.getWorkingCopy();
    private final UndoStack undoStack = new UndoStack(workingCopy);

    private final TableView<MutableEntryRow> table = new TableView<>();
    private final TextField searchField = new TextField();
    private final Label statusLabel = new Label();
    private final Label dirtyChip = new Label();
    private final FilteredList<MutableEntryRow> filtered;
    private final SortedList<MutableEntryRow> sorted;
    private final Menu columnsMenu = new Menu("Columns");

    private final TabPane tabPane = new TabPane();
    private final Tab entriesTab = new Tab("Entries");
    private final Tab keysTab = new Tab("Metadata Keys");
    private final MetadataKeysTab keysTabController;
    private final Button fitBtn = new Button("Fit Columns");
    private final PauseTransition statusRevert = new PauseTransition(Duration.seconds(5));
    private String transientStatusMessage = null;

    // Menu items whose enabled state / label tracks the working copy.
    private final MenuItem saveItem = new MenuItem("Save");
    private final MenuItem discardItem = new MenuItem("Discard changes");
    private final MenuItem undoItem = new MenuItem("Undo");
    private final MenuItem redoItem = new MenuItem("Redo");

    private final Set<String> builtInColumnHeaders = Set.of(
            MutableEntryRow.COL_NAME, MutableEntryRow.COL_ID, MutableEntryRow.COL_URI,
            MutableEntryRow.COL_DESCRIPTION, MutableEntryRow.COL_TAGS);

    private final ChangeListener<Project<BufferedImage>> projectListener;

    public static void showFor(QuPathGUI qupath) {
        if (instance == null)
            instance = new MetadataBrowserWindow(qupath);
        instance.reloadFromProject();
        if (!instance.stage.isShowing())
            instance.stage.show();
        instance.stage.toFront();
        instance.stage.requestFocus();
    }

    private MetadataBrowserWindow(QuPathGUI qupath) {
        this.qupath = qupath;
        this.stage = new Stage();
        stage.setTitle(titleFor(qupath.getProject(), false));
        stage.initOwner(qupath.getStage());
        stage.initModality(Modality.NONE);
        stage.setMinWidth(600);
        stage.setMinHeight(400);

        filtered = new FilteredList<>(workingCopy.getRows(), r -> true);
        sorted = new SortedList<>(filtered);
        sorted.comparatorProperty().bind(table.comparatorProperty());
        table.setItems(sorted);
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        table.getSelectionModel().setCellSelectionEnabled(true);
        table.setTableMenuButtonVisible(true);
        table.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        table.setEditable(true);
        table.setPlaceholder(new Label("No project open, or project contains no images."));

        searchField.setPromptText("Search (all columns, case-insensitive)...");
        searchField.textProperty().addListener((obs, oldV, newV) -> applyFilter());

        Button refreshBtn = new Button("Refresh");
        refreshBtn.setTooltip(new Tooltip(
                "Reload entries and metadata from the active project (F5).\n"
                        + "Disabled while you have unsaved edits."));
        refreshBtn.setOnAction(e -> tryReload());

        fitBtn.setTooltip(new Tooltip(
                "Resize each visible column to the width of its widest content,\n"
                        + "capped at the Max column width preference. Long values wrap."));
        fitBtn.setOnAction(e -> fitColumnsToContent());

        Button exportBtn = new Button("Export...");
        exportBtn.setOnAction(e -> exportTable());

        Button closeBtn = new Button("Close");
        closeBtn.setOnAction(e -> requestCloseWindow());

        HBox topBar = new HBox(8, new Label("Filter rows:"), searchField, refreshBtn, fitBtn);
        HBox.setHgrow(searchField, Priority.ALWAYS);
        topBar.setStyle("-fx-padding: 8;");

        Spinner<Integer> maxWidthSpinner = new Spinner<>(80, 2000,
                Math.max(80, Preferences.MAX_COLUMN_WIDTH.get()), 20);
        maxWidthSpinner.setEditable(true);
        maxWidthSpinner.setPrefWidth(90);
        maxWidthSpinner.setTooltip(new Tooltip(
                "Maximum column width in pixels for the Fit Columns button.\n"
                        + "Cells longer than this wrap to multiple lines.\n"
                        + "Saved across sessions."));
        maxWidthSpinner.valueProperty().addListener((obs, o, n) -> {
            if (n != null) Preferences.MAX_COLUMN_WIDTH.set(n);
        });
        Label maxWidthLabel = new Label("Max column width:");
        maxWidthLabel.setTooltip(new Tooltip(
                "Maximum column width in pixels for the Fit Columns button.\n"
                        + "Cells longer than this wrap to multiple lines.\n"
                        + "Saved across sessions."));

        dirtyChip.setStyle("-fx-background-color: #fff3cd; -fx-text-fill: #663c00; "
                + "-fx-padding: 2 8 2 8; -fx-background-radius: 3;");
        dirtyChip.setVisible(false);
        dirtyChip.setManaged(false);

        HBox bottomBar = new HBox(8, statusLabel, dirtyChip, spacer(),
                maxWidthLabel, maxWidthSpinner, exportBtn, closeBtn);
        bottomBar.setStyle("-fx-padding: 8;");

        MenuBar menuBar = new MenuBar();
        Menu fileMenu = new Menu("File");
        MenuItem importItem = new MenuItem("Import metadata...");
        importItem.setOnAction(e -> openImportWizard());
        Menu exportMenu = new Menu("Export");
        MenuItem exportTableItem = new MenuItem("Export visible columns...");
        exportTableItem.setOnAction(e -> exportTable());
        MenuItem exportTemplateItem = new MenuItem("Template for fill-in...");
        exportTemplateItem.setOnAction(e -> openTemplateExport());
        exportMenu.getItems().addAll(exportTableItem, exportTemplateItem);

        MenuItem refreshItem = new MenuItem("Refresh");
        refreshItem.setAccelerator(new KeyCodeCombination(KeyCode.F5));
        refreshItem.setOnAction(e -> tryReload());

        saveItem.setAccelerator(new KeyCodeCombination(KeyCode.S, KeyCombination.SHORTCUT_DOWN));
        saveItem.setOnAction(e -> trySave());
        discardItem.setOnAction(e -> tryDiscard());

        MenuItem closeItem = new MenuItem("Close");
        closeItem.setAccelerator(new KeyCodeCombination(KeyCode.ESCAPE));
        closeItem.setOnAction(e -> requestCloseWindow());

        fileMenu.getItems().addAll(
                importItem, exportMenu, new SeparatorMenuItem(),
                refreshItem, new SeparatorMenuItem(),
                saveItem, discardItem, new SeparatorMenuItem(),
                closeItem);

        Menu editMenu = new Menu("Edit");
        undoItem.setAccelerator(new KeyCodeCombination(KeyCode.Z, KeyCombination.SHORTCUT_DOWN));
        undoItem.setOnAction(e -> undoStack.undo());
        redoItem.setAccelerator(new KeyCodeCombination(KeyCode.Z,
                KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN));
        redoItem.setOnAction(e -> undoStack.redo());
        MenuItem redoAliasItem = new MenuItem("Redo (alias)");
        redoAliasItem.setAccelerator(new KeyCodeCombination(KeyCode.Y, KeyCombination.SHORTCUT_DOWN));
        redoAliasItem.setOnAction(e -> undoStack.redo());
        redoAliasItem.setVisible(false);
        MenuItem addColumnItem = new MenuItem("Add column...");
        addColumnItem.setOnAction(e -> addColumnViaDialog());
        MenuItem extractItem = new MenuItem("Extract columns from filenames...");
        extractItem.setOnAction(e -> openRegexExtraction());
        editMenu.getItems().addAll(undoItem, redoItem, redoAliasItem,
                new SeparatorMenuItem(), addColumnItem, extractItem);

        menuBar.getMenus().addAll(fileMenu, editMenu, columnsMenu);

        keysTabController = new MetadataKeysTab(
                qupath,
                model,
                this::refreshAfterCommand,
                this::showTransientStatusMessage,
                this::updateStatusLabel,
                this::applyCommandFromTab);
        BorderPane entriesContent = new BorderPane(table);
        entriesTab.setContent(entriesContent);
        entriesTab.setClosable(false);
        keysTab.setContent(keysTabController.getRoot());
        keysTab.setClosable(false);
        tabPane.getTabs().addAll(entriesTab, keysTab);
        tabPane.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
            boolean keysActive = newTab == keysTab;
            searchField.setDisable(keysActive);
            fitBtn.setDisable(keysActive);
            columnsMenu.setDisable(keysActive);
            updateStatusLabel();
        });

        BorderPane root = new BorderPane();
        VBox top = new VBox(menuBar, topBar);
        root.setTop(top);
        root.setCenter(tabPane);
        root.setBottom(bottomBar);

        Scene scene = new Scene(root, 1100, 650);

        KeyCodeCombination copyCombo = new KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN);
        KeyCodeCombination pasteCombo = new KeyCodeCombination(KeyCode.V, KeyCombination.SHORTCUT_DOWN);
        scene.addEventFilter(KeyEvent.KEY_PRESSED, ev -> {
            if (copyCombo.match(ev)
                    && !(scene.getFocusOwner() instanceof javafx.scene.control.TextInputControl)) {
                copySelectionToClipboard();
                ev.consume();
                return;
            }
            if (pasteCombo.match(ev)
                    && !(scene.getFocusOwner() instanceof javafx.scene.control.TextInputControl)) {
                pasteFromClipboard();
                ev.consume();
            }
        });
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.F, KeyCombination.SHORTCUT_DOWN),
                () -> {
                    tabPane.getSelectionModel().select(entriesTab);
                    searchField.requestFocus();
                });

        table.setRowFactory(tv -> {
            TableRow<MutableEntryRow> row = new TableRow<>();
            row.setOnMouseClicked(ev -> {
                if (ev.getClickCount() == 2 && !row.isEmpty()) {
                    // Double-click opens the entry only on a non-user-key
                    // built-in column; on user-key columns, double-click
                    // enters edit mode (TableView default).
                    TablePosition<?, ?> pos = table.getFocusModel().getFocusedCell();
                    if (pos != null && pos.getTableColumn() != null) {
                        Object userData = pos.getTableColumn().getUserData();
                        if (Boolean.TRUE.equals(userData))
                            return;
                    }
                    openEntry(row.getItem());
                }
            });
            row.setContextMenu(buildRowContextMenu());
            return row;
        });

        filtered.predicateProperty().addListener((obs, o, n) -> updateStatusLabel());
        workingCopy.getRows().addListener((ListChangeListener<MutableEntryRow>) c -> updateStatusLabel());
        model.getKeyRows().addListener(
                (ListChangeListener<qupath.ext.projectmetadatabrowser.model.MetadataKeyRow>) c -> updateStatusLabel());

        statusRevert.setOnFinished(ev -> {
            transientStatusMessage = null;
            updateStatusLabel();
        });

        // Working-copy listeners: refresh table on every tick; keep the
        // title bar and menu counters in sync with dirty state.
        workingCopy.tickProperty().addListener((obs, o, n) -> {
            model.rebuildKeyRows();
            table.refresh();
            updateStatusLabel();
            // Save / Discard label counters also need a refresh on every
            // command (the dirty boolean only fires on transitions; without
            // this, "Save (N changes)" gets stuck at the value seen when
            // dirty first turned true).
            updateSaveDiscardMenus();
        });
        workingCopy.dirtyProperty().addListener((obs, o, n) -> {
            updateTitle();
            updateSaveDiscardMenus();
        });
        undoStack.undoSizeProperty().addListener((obs, o, n) -> updateUndoRedoMenus());
        undoStack.redoSizeProperty().addListener((obs, o, n) -> updateUndoRedoMenus());

        stage.setScene(scene);

        stage.setOnCloseRequest(ev -> {
            if (!confirmDirtyOrCancel("close the window")) {
                ev.consume();
            }
        });

        projectListener = (obs, oldProj, newProj) -> {
            if (!stage.isShowing())
                return;
            Platform.runLater(() -> {
                if (workingCopy.isDirty()) {
                    if (!confirmDirtyOrCancel("switch projects")) {
                        // User cancelled -- but the project has already
                        // switched in QuPath. Best we can do is force a
                        // reload anyway so the working copy reflects
                        // reality. Surface the lost state via a notification.
                        Dialogs.showErrorNotification("Project Metadata Browser",
                                "Project changed underneath the browser; reloaded.");
                    }
                }
                reloadFromProject();
            });
        };
        qupath.projectProperty().addListener(projectListener);

        updateTitle();
        updateSaveDiscardMenus();
        updateUndoRedoMenus();
    }

    private static Node spacer() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    private static String titleFor(Project<BufferedImage> project, boolean dirty) {
        StringBuilder sb = new StringBuilder("Project Metadata Browser");
        if (project != null && project.getName() != null && !project.getName().isBlank())
            sb.append(" - ").append(project.getName());
        if (dirty)
            sb.append(" *");
        return sb.toString();
    }

    private void updateTitle() {
        stage.setTitle(titleFor(qupath.getProject(), workingCopy.isDirty()));
    }

    private void updateSaveDiscardMenus() {
        boolean dirty = workingCopy.isDirty();
        saveItem.setDisable(!dirty);
        discardItem.setDisable(!dirty);
        int n = workingCopy.unsavedChangeCount();
        if (dirty) {
            saveItem.setText(n == 1 ? "Save (1 change)" : "Save (" + n + " changes)");
        } else {
            saveItem.setText("Save");
        }
    }

    private void updateUndoRedoMenus() {
        int u = undoStack.undoSize();
        int r = undoStack.redoSize();
        undoItem.setText(u == 0 ? "Undo" : "Undo (" + u + ")");
        redoItem.setText(r == 0 ? "Redo" : "Redo (" + r + ")");
        undoItem.setDisable(u == 0);
        redoItem.setDisable(r == 0);
    }

    private void tryReload() {
        if (workingCopy.isDirty()) {
            showTransientStatusMessage("Save or Discard first -- refresh blocked while there are unsaved edits.");
            return;
        }
        reloadFromProject();
    }

    private void reloadFromProject() {
        Project<BufferedImage> project = qupath.getProject();

        Set<String> selectedIds = new HashSet<>();
        for (MutableEntryRow r : table.getSelectionModel().getSelectedItems()) {
            if (r != null)
                selectedIds.add(r.getId());
        }

        Map<String, Boolean> visibilityByHeader = new HashMap<>();
        for (TableColumn<MutableEntryRow, ?> c : table.getColumns())
            visibilityByHeader.put(c.getText(), c.isVisible());

        String sortHeader = null;
        TableColumn.SortType sortType = null;
        if (!table.getSortOrder().isEmpty()) {
            TableColumn<MutableEntryRow, ?> primary = table.getSortOrder().get(0);
            sortHeader = primary.getText();
            sortType = primary.getSortType();
        }

        model.loadFrom(project);
        rebuildColumns();
        undoStack.clear();

        for (TableColumn<MutableEntryRow, ?> c : table.getColumns()) {
            Boolean visible = visibilityByHeader.get(c.getText());
            if (visible != null)
                c.setVisible(visible);
        }

        if (sortHeader != null) {
            for (TableColumn<MutableEntryRow, ?> c : table.getColumns()) {
                if (sortHeader.equals(c.getText())) {
                    c.setSortType(sortType);
                    table.getSortOrder().clear();
                    table.getSortOrder().add(c);
                    break;
                }
            }
        }

        table.setPlaceholder(new Label(project == null
                ? "No project open."
                : "Project contains no images."));
        updateTitle();
        updateSaveDiscardMenus();
        updateUndoRedoMenus();
        updateStatusLabel();

        if (!selectedIds.isEmpty()) {
            for (MutableEntryRow r : table.getItems()) {
                if (selectedIds.contains(r.getId()))
                    table.getSelectionModel().select(r);
            }
            MutableEntryRow focus = table.getSelectionModel().getSelectedItem();
            if (focus != null)
                table.scrollTo(focus);
        }
    }

    private final Map<String, java.util.function.Function<MutableEntryRow, String>> columnResolvers = new HashMap<>();

    private void rebuildColumns() {
        table.getColumns().clear();
        columnsMenu.getItems().clear();
        columnResolvers.clear();

        addBuiltInColumn(MutableEntryRow.COL_NAME, MutableEntryRow::getName);
        addBuiltInColumn(MutableEntryRow.COL_ID, MutableEntryRow::getId);
        addBuiltInColumn(MutableEntryRow.COL_URI, MutableEntryRow::getUri);
        addBuiltInColumn(MutableEntryRow.COL_DESCRIPTION, MutableEntryRow::getDescription);
        addBuiltInColumn(MutableEntryRow.COL_TAGS, MutableEntryRow::getTags);

        for (String key : workingCopy.getColumnKeys()) {
            String header = builtInColumnHeaders.contains(key) ? key + " (metadata)" : key;
            addUserKeyColumn(header, key);
        }

        columnsMenu.getItems().add(new SeparatorMenuItem());
        MenuItem selectAll = new MenuItem("Select All");
        selectAll.setOnAction(e -> setAllColumnsVisible(true));
        MenuItem selectNone = new MenuItem("Select None");
        selectNone.setOnAction(e -> setAllColumnsVisible(false));
        columnsMenu.getItems().addAll(selectAll, selectNone);
    }

    private void setAllColumnsVisible(boolean visible) {
        for (TableColumn<MutableEntryRow, ?> c : table.getColumns()) {
            c.setVisible(visible);
        }
    }

    private void fitColumnsToContent() {
        int maxWidth = Math.max(80, Preferences.MAX_COLUMN_WIDTH.get());
        Font font = Font.getDefault();
        double headerPad = 24;
        double cellPad = 16;
        for (TableColumn<MutableEntryRow, ?> col : table.getColumns()) {
            if (!col.isVisible()) continue;
            double widest = textWidth(col.getText(), font) + headerPad;
            for (MutableEntryRow row : table.getItems()) {
                String v = resolveCell(row, col.getText());
                if (v == null || v.isEmpty()) continue;
                double w = textWidth(v, font) + cellPad;
                if (w > widest) {
                    widest = w;
                    if (widest >= maxWidth) break;
                }
            }
            col.setPrefWidth(Math.min(maxWidth, Math.max(60, widest)));
        }
    }

    private static double textWidth(String s, Font font) {
        Text t = new Text(s);
        t.setFont(font);
        return t.getLayoutBounds().getWidth();
    }

    private void addBuiltInColumn(String header,
                                   java.util.function.Function<MutableEntryRow, String> resolver) {
        columnResolvers.put(header, resolver);
        TableColumn<MutableEntryRow, String> tc = new TableColumn<>(header);
        tc.setCellValueFactory(cdf -> new ReadOnlyStringWrapper(resolver.apply(cdf.getValue())));
        tc.setCellFactory(col -> new TooltipTextCell());
        tc.setPrefWidth(preferredWidthFor(header));
        tc.setMinWidth(60);
        tc.setSortable(true);
        tc.setEditable(false);
        tc.setUserData(Boolean.FALSE);
        table.getColumns().add(tc);

        CheckMenuItem item = new CheckMenuItem(header);
        item.setSelected(true);
        item.selectedProperty().bindBidirectional(tc.visibleProperty());
        columnsMenu.getItems().add(item);
    }

    private void addUserKeyColumn(String header, String metadataKey) {
        columnResolvers.put(header, r -> r.getMetadata(metadataKey));
        TableColumn<MutableEntryRow, String> tc = new TableColumn<>(header);
        tc.setCellValueFactory(cdf -> new ReadOnlyStringWrapper(cdf.getValue().getMetadata(metadataKey)));
        tc.setCellFactory(col -> new EditableMetadataCell(metadataKey, this));
        tc.setPrefWidth(preferredWidthFor(header));
        tc.setMinWidth(60);
        tc.setSortable(true);
        tc.setEditable(true);
        tc.setUserData(Boolean.TRUE);
        tc.setOnEditCommit(ev -> {
            MutableEntryRow row = ev.getRowValue();
            if (row == null) return;
            String oldValue = row.getMetadata(metadataKey);
            String newValue = ev.getNewValue();
            if (newValue == null) newValue = "";
            if (java.util.Objects.equals(oldValue, newValue))
                return;
            SetCellCommand cmd = new SetCellCommand(row.getId(), metadataKey, oldValue, newValue);
            undoStack.pushAndApply(cmd);
        });
        table.getColumns().add(tc);

        CheckMenuItem item = new CheckMenuItem(header);
        item.setSelected(true);
        item.selectedProperty().bindBidirectional(tc.visibleProperty());
        columnsMenu.getItems().add(item);
    }

    /**
     * TableCell that shows the full cell value in a tooltip on hover; read
     * only.
     */
    private static final class TooltipTextCell extends TableCell<MutableEntryRow, String> {
        TooltipTextCell() {
            setWrapText(true);
        }

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

    /**
     * Editable user-metadata cell. Paints a light-yellow background + amber
     * left-border when its value differs from the load-time snapshot.
     */
    private static final class EditableMetadataCell extends TextFieldTableCell<MutableEntryRow, String> {
        private final String metadataKey;
        private final MetadataBrowserWindow window;

        EditableMetadataCell(String metadataKey, MetadataBrowserWindow window) {
            super(new javafx.util.converter.DefaultStringConverter());
            this.metadataKey = metadataKey;
            this.window = window;
            setWrapText(true);
        }

        @Override
        public void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            if (empty) {
                setStyle("");
                return;
            }
            MutableEntryRow row = getTableRow() == null ? null : getTableRow().getItem();
            if (row != null && row.isCellDirty(metadataKey)) {
                setStyle("-fx-background-color: #fffbcc; -fx-border-color: #f1c40f; "
                        + "-fx-border-width: 0 0 0 3;");
                setAccessibleText("dirty -- not yet saved: " + row.getMetadata(metadataKey));
            } else {
                setStyle("");
            }
        }
    }

    private static double preferredWidthFor(String col) {
        switch (col) {
            case MutableEntryRow.COL_NAME: return 220;
            case MutableEntryRow.COL_ID: return 260;
            case MutableEntryRow.COL_URI: return 320;
            case MutableEntryRow.COL_DESCRIPTION: return 220;
            case MutableEntryRow.COL_TAGS: return 120;
            default: return 140;
        }
    }

    private void applyFilter() {
        String raw = searchField.getText();
        if (raw == null || raw.isBlank()) {
            filtered.setPredicate(r -> true);
            return;
        }
        String needle = raw.toLowerCase();
        Predicate<MutableEntryRow> p = row -> {
            for (TableColumn<MutableEntryRow, ?> c : table.getColumns()) {
                if (!c.isVisible())
                    continue;
                var resolver = columnResolvers.get(c.getText());
                String v = resolver == null ? "" : resolver.apply(row);
                if (v != null && v.toLowerCase().contains(needle))
                    return true;
            }
            return false;
        };
        filtered.setPredicate(p);
    }

    private void updateStatusLabel() {
        if (transientStatusMessage != null) {
            statusLabel.setText(transientStatusMessage);
        } else {
            boolean keysActive = tabPane.getSelectionModel().getSelectedItem() == keysTab;
            if (keysActive) {
                statusLabel.setText(String.format("Keys: %d shown / %d total",
                        keysTabController.getFilteredKeyCount(),
                        keysTabController.getTotalKeyCount()));
            } else {
                statusLabel.setText(String.format("Entries: %d shown / %d total",
                        filtered.size(), workingCopy.getRows().size()));
            }
        }
        int unsaved = workingCopy.unsavedChangeCount();
        if (unsaved > 0) {
            dirtyChip.setText(unsaved == 1
                    ? "1 unsaved change"
                    : unsaved + " unsaved changes");
            dirtyChip.setVisible(true);
            dirtyChip.setManaged(true);
        } else {
            dirtyChip.setVisible(false);
            dirtyChip.setManaged(false);
        }
    }

    private void showTransientStatusMessage(String message) {
        transientStatusMessage = message;
        updateStatusLabel();
        statusRevert.stop();
        statusRevert.playFromStart();
    }

    private ContextMenu buildRowContextMenu() {
        MenuItem openItem = new MenuItem("Open image");
        openItem.setOnAction(e -> {
            MutableEntryRow row = table.getSelectionModel().getSelectedItem();
            if (row != null)
                openEntry(row);
        });
        MenuItem copyItem = new MenuItem("Copy as TSV");
        copyItem.setOnAction(e -> copySelectionToClipboard());
        MenuItem pasteItem = new MenuItem("Paste from clipboard");
        pasteItem.setOnAction(e -> pasteFromClipboard());
        MenuItem editItem = new MenuItem("Edit metadata...");
        editItem.setOnAction(e -> {
            MutableEntryRow row = table.getSelectionModel().getSelectedItem();
            if (row != null)
                editMetadata(row);
        });
        ContextMenu menu = new ContextMenu(openItem, copyItem, pasteItem,
                new SeparatorMenuItem(), editItem);
        menu.setOnShowing(e -> {
            int n = table.getSelectionModel().getSelectedItems().size();
            copyItem.setDisable(n == 0);
            openItem.setDisable(n == 0);
            if (n > 1) {
                editItem.setText("Edit metadata... (only first of " + n + " selected)");
                editItem.setDisable(true);
            } else {
                editItem.setText("Edit metadata...");
                editItem.setDisable(n == 0);
            }
        });
        return menu;
    }

    private void openEntry(MutableEntryRow row) {
        if (row == null)
            return;
        try {
            qupath.openImageEntry(row.getEntry());
        } catch (Exception e) {
            logger.error("Failed to open image entry", e);
            Dialogs.showErrorNotification("Project Metadata Browser",
                    "Could not open image: " + e.getMessage());
        }
    }

    private void editMetadata(MutableEntryRow row) {
        Map<String, String> updates = MetadataEditDialog.showFor(stage, row);
        if (updates == null || updates.isEmpty())
            return;
        List<BulkSetCellsCommand.CellDelta> deltas = new ArrayList<>();
        for (Map.Entry<String, String> e : updates.entrySet()) {
            String key = e.getKey();
            if (key == null) continue;
            String oldValue = row.getMetadata(key);
            String newValue = e.getValue() == null ? "" : e.getValue();
            if (java.util.Objects.equals(oldValue, newValue))
                continue;
            deltas.add(new BulkSetCellsCommand.CellDelta(row.getId(), key, oldValue, newValue));
        }
        if (deltas.isEmpty())
            return;
        undoStack.pushAndApply(new BulkSetCellsCommand("Edit entry: " + row.getName(), deltas));
    }

    private void copySelectionToClipboard() {
        List<MutableEntryRow> rows = new ArrayList<>(table.getSelectionModel().getSelectedItems());
        if (rows.isEmpty())
            return;
        List<TableColumn<MutableEntryRow, ?>> visibleCols = visibleColumns();

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < visibleCols.size(); i++) {
            if (i > 0) sb.append('\t');
            sb.append(escapeCell(visibleCols.get(i).getText()));
        }
        sb.append('\n');
        for (MutableEntryRow r : rows) {
            for (int i = 0; i < visibleCols.size(); i++) {
                if (i > 0) sb.append('\t');
                sb.append(escapeCell(resolveCell(r, visibleCols.get(i).getText())));
            }
            sb.append('\n');
        }

        ClipboardContent cc = new ClipboardContent();
        cc.putString(sb.toString());
        Clipboard.getSystemClipboard().setContent(cc);
    }

    private void pasteFromClipboard() {
        Clipboard cb = Clipboard.getSystemClipboard();
        if (!cb.hasString()) {
            showTransientStatusMessage("Clipboard is empty.");
            return;
        }
        String text = cb.getString();
        if (text == null || text.isEmpty()) {
            showTransientStatusMessage("Clipboard is empty.");
            return;
        }
        String normalized = text.replace("\r\n", "\n").replace("\r", "\n");
        // Strip a single trailing newline -- Excel adds one.
        if (normalized.endsWith("\n"))
            normalized = normalized.substring(0, normalized.length() - 1);
        String[] lines = normalized.split("\n", -1);
        boolean anyTab = false;
        for (String line : lines) {
            if (line.indexOf('\t') >= 0) {
                anyTab = true;
                break;
            }
        }
        if (!anyTab && lines.length == 1) {
            showTransientStatusMessage(
                    "Clipboard does not look like tab-separated data. Copy a block from Excel.");
            return;
        }
        TablePosition<MutableEntryRow, ?> anchor = null;
        if (!table.getSelectionModel().getSelectedCells().isEmpty()) {
            @SuppressWarnings("unchecked")
            TablePosition<MutableEntryRow, ?> first =
                    (TablePosition<MutableEntryRow, ?>) table.getSelectionModel().getSelectedCells().get(0);
            anchor = first;
        }
        if (anchor == null || anchor.getTableColumn() == null) {
            showTransientStatusMessage("Click a cell first, then paste.");
            return;
        }
        int anchorRow = anchor.getRow();
        TableColumn<MutableEntryRow, ?> anchorCol = anchor.getTableColumn();
        if (!Boolean.TRUE.equals(anchorCol.getUserData())) {
            showTransientStatusMessage("Click a user-metadata cell first; built-in columns are read-only.");
            return;
        }
        List<TableColumn<MutableEntryRow, ?>> cols = visibleColumns();
        int anchorColIdx = cols.indexOf(anchorCol);
        if (anchorColIdx < 0) {
            showTransientStatusMessage("Could not resolve paste anchor.");
            return;
        }
        int rowsAvailable = table.getItems().size() - anchorRow;
        int rowsClipped = Math.max(0, lines.length - rowsAvailable);
        int rowsPasted = Math.min(lines.length, rowsAvailable);

        List<BulkSetCellsCommand.CellDelta> deltas = new ArrayList<>();
        int colsApplied = 0;
        int colsClipped = 0;
        LinkedHashSet<String> skippedReadOnly = new LinkedHashSet<>();
        for (int i = 0; i < rowsPasted; i++) {
            String line = lines[i];
            String[] cells = line.split("\t", -1);
            MutableEntryRow row = table.getItems().get(anchorRow + i);
            for (int j = 0; j < cells.length; j++) {
                int targetIdx = anchorColIdx + j;
                if (targetIdx >= cols.size()) {
                    if (j >= 0)
                        colsClipped = Math.max(colsClipped, cells.length - (cols.size() - anchorColIdx));
                    break;
                }
                TableColumn<MutableEntryRow, ?> col = cols.get(targetIdx);
                if (!Boolean.TRUE.equals(col.getUserData())) {
                    skippedReadOnly.add(col.getText());
                    continue;
                }
                String header = col.getText();
                String oldValue = row.getMetadata(header);
                String newValue = cells[j];
                if (java.util.Objects.equals(oldValue, newValue))
                    continue;
                deltas.add(new BulkSetCellsCommand.CellDelta(row.getId(), header, oldValue, newValue));
                if (i == 0)
                    colsApplied = Math.max(colsApplied, j + 1);
            }
        }
        if (!deltas.isEmpty()) {
            undoStack.pushAndApply(new BulkSetCellsCommand("Paste from clipboard", deltas));
        }
        StringBuilder msg = new StringBuilder();
        msg.append("Pasted ").append(rowsPasted).append(" rows by ")
                .append(colsApplied).append(" columns.");
        if (rowsClipped > 0)
            msg.append(" ").append(rowsClipped).append(" rows past table edge skipped.");
        if (colsClipped > 0)
            msg.append(" ").append(colsClipped).append(" columns past visible-column edge skipped.");
        if (!skippedReadOnly.isEmpty()) {
            int max = 3;
            int n = skippedReadOnly.size();
            StringBuilder list = new StringBuilder();
            int i = 0;
            for (String s : skippedReadOnly) {
                if (i >= max) {
                    list.append(", ...");
                    break;
                }
                if (i > 0) list.append(", ");
                list.append(s);
                i++;
            }
            msg.append(" ").append(n)
                    .append(n == 1 ? " column (" : " columns (")
                    .append(list)
                    .append(n == 1 ? ") is read-only and was skipped." : ") are read-only and were skipped.");
        }
        showTransientStatusMessage(msg.toString());
    }

    private String resolveCell(MutableEntryRow row, String header) {
        var resolver = columnResolvers.get(header);
        return resolver == null ? "" : resolver.apply(row);
    }

    private void exportTable() {
        List<TableColumn<MutableEntryRow, ?>> visibleCols = visibleColumns();
        if (visibleCols.isEmpty()) {
            Dialogs.showErrorNotification("Project Metadata Browser",
                    "No columns are visible to export. Enable at least one column from the table menu.");
            return;
        }

        FileChooser fc = new FileChooser();
        fc.setTitle("Export project metadata");
        fc.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Comma-separated values (*.csv)", "*.csv"),
                new FileChooser.ExtensionFilter("Tab-separated values (*.tsv)", "*.tsv"));
        java.io.File file = fc.showSaveDialog(stage);
        if (file == null)
            return;

        boolean tsv = file.getName().toLowerCase().endsWith(".tsv");
        char sep = tsv ? '\t' : ',';

        try (BufferedWriter w = Files.newBufferedWriter(Path.of(file.toURI()), StandardCharsets.UTF_8)) {
            for (int i = 0; i < visibleCols.size(); i++) {
                if (i > 0) w.write(sep);
                w.write(escapeForDelimiter(visibleCols.get(i).getText(), sep));
            }
            w.write('\n');
            for (MutableEntryRow r : sorted) {
                for (int i = 0; i < visibleCols.size(); i++) {
                    if (i > 0) w.write(sep);
                    w.write(escapeForDelimiter(resolveCell(r, visibleCols.get(i).getText()), sep));
                }
                w.write('\n');
            }
            Dialogs.showInfoNotification("Project Metadata Browser",
                    "Exported " + sorted.size() + " rows to " + file.getName());
        } catch (IOException e) {
            logger.error("Failed to export metadata table", e);
            Dialogs.showErrorNotification("Project Metadata Browser",
                    "Export failed: " + e.getMessage());
        }
    }

    private List<TableColumn<MutableEntryRow, ?>> visibleColumns() {
        List<TableColumn<MutableEntryRow, ?>> out = new ArrayList<>();
        for (TableColumn<MutableEntryRow, ?> c : table.getColumns()) {
            if (c.isVisible())
                out.add(c);
        }
        return out;
    }

    private static String escapeCell(String s) {
        if (s == null) return "";
        return s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    private static String escapeForDelimiter(String s, char sep) {
        if (s == null) return "";
        if (sep == ',') {
            boolean needsQuote = s.indexOf(',') >= 0 || s.indexOf('"') >= 0
                    || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0;
            if (!needsQuote) return s;
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    // ------------------------------------------------------------------
    // Save / Discard / close flow
    // ------------------------------------------------------------------

    private boolean trySave() {
        Project<BufferedImage> project = qupath.getProject();
        if (project == null) {
            Dialogs.showErrorNotification("Project Metadata Browser",
                    "No project is open. Open a project before saving.");
            return false;
        }
        if (!workingCopy.isDirty())
            return true;
        List<WorkingCopy.EntryDiff> diffs = workingCopy.diff();
        int n = workingCopy.unsavedChangeCount();
        try {
            MetadataKeyOperations.commitWorkingCopy(project, diffs);
        } catch (IOException ex) {
            logger.error("Working-copy save failed", ex);
            Dialogs.showErrorNotification("Project Metadata Browser",
                    "Could not save metadata. Reverted. Check that the project file is writable.");
            return false;
        }
        workingCopy.markClean();
        qupath.refreshProject();
        showTransientStatusMessage("Saved " + n + (n == 1 ? " change." : " changes."));
        updateTitle();
        updateSaveDiscardMenus();
        return true;
    }

    private void tryDiscard() {
        if (!workingCopy.isDirty())
            return;
        int n = workingCopy.unsavedChangeCount();
        reloadFromProject();
        showTransientStatusMessage("Discarded " + n + (n == 1 ? " change." : " changes."));
    }

    /**
     * If dirty, prompt Save/Discard/Cancel. Returns true if the caller may
     * proceed (Save chosen and succeeded, or Discard chosen), false if the
     * caller should abort (Cancel chosen or Save chosen but failed).
     */
    private boolean confirmDirtyOrCancel(String actionLabel) {
        if (!workingCopy.isDirty())
            return true;
        int n = workingCopy.unsavedChangeCount();
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Unsaved changes");
        String hdr = n == 1 ? "You have 1 unsaved metadata edit." : "You have " + n + " unsaved metadata edits.";
        alert.setHeaderText(hdr);
        alert.setContentText("Save commits them to the project file. "
                + "Discard throws them away. The on-disk project is untouched until Save.");
        if (stage != null)
            alert.initOwner(stage);
        ButtonType saveBt = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        ButtonType discardBt = new ButtonType("Discard changes", ButtonBar.ButtonData.OTHER);
        ButtonType cancelBt = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(saveBt, discardBt, cancelBt);
        Button saveBtn = (Button) alert.getDialogPane().lookupButton(saveBt);
        saveBtn.setDefaultButton(true);
        saveBtn.setTooltip(new Tooltip("Commit all queued edits and " + actionLabel + "."));
        Button discardBtn = (Button) alert.getDialogPane().lookupButton(discardBt);
        discardBtn.setStyle("-fx-base: #c33;");
        discardBtn.setTooltip(new Tooltip("Throw away every queued edit and " + actionLabel + "."));
        Button cancelBtn = (Button) alert.getDialogPane().lookupButton(cancelBt);
        cancelBtn.setCancelButton(true);
        cancelBtn.setTooltip(new Tooltip("Keep the window open. Nothing changes."));
        Optional<ButtonType> picked = alert.showAndWait();
        if (picked.isEmpty() || picked.get() == cancelBt)
            return false;
        if (picked.get() == saveBt) {
            return trySave();
        }
        // Discard
        tryDiscard();
        return true;
    }

    private void requestCloseWindow() {
        if (!confirmDirtyOrCancel("close the window"))
            return;
        stage.hide();
    }

    // ------------------------------------------------------------------
    // Workflow surface hooks
    // ------------------------------------------------------------------

    private void openTemplateExport() {
        List<String> userKeys = new ArrayList<>(workingCopy.getColumnKeys());
        String projectName = qupath.getProject() == null ? null : qupath.getProject().getName();
        TemplateExportDialog.showAndExport(stage,
                new ArrayList<>(workingCopy.getRows()), userKeys, projectName);
    }

    private void openImportWizard() {
        ImportCommand command = ImportWizard.showAndApply(stage, workingCopy);
        if (command == null)
            return;
        undoStack.pushAndApply(command);
        showTransientStatusMessage("Imported " + command.affectedCellCount()
                + " updates, " + command.getNewColumns().size() + " new columns. Save to commit.");
    }

    private void openRegexExtraction() {
        RegexExtractCommand command = RegexExtractionDialog.showAndBuild(stage, workingCopy);
        if (command == null)
            return;
        undoStack.pushAndApply(command);
        showTransientStatusMessage("Regex extracted " + command.affectedCellCount()
                + " cell values, " + command.getNewColumns().size() + " new columns. Save to commit.");
    }

    private void addColumnViaDialog() {
        TextField nameField = new TextField();
        nameField.setPromptText("new column name");
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Add column");
        alert.setHeaderText("New user-metadata column name:");
        alert.getDialogPane().setContent(nameField);
        if (stage != null)
            alert.initOwner(stage);
        ButtonType addType = new ButtonType("Add", ButtonBar.ButtonData.OK_DONE);
        alert.getButtonTypes().setAll(addType, ButtonType.CANCEL);
        Platform.runLater(nameField::requestFocus);
        Optional<ButtonType> result = alert.showAndWait();
        if (result.isEmpty() || result.get() != addType)
            return;
        String name = nameField.getText();
        if (name == null || name.isBlank())
            return;
        String trimmed = name.trim();
        if (workingCopy.getColumnKeys().contains(trimmed)) {
            showTransientStatusMessage("Column '" + trimmed + "' already exists.");
            return;
        }
        undoStack.pushAndApply(new AddColumnCommand(trimmed));
    }

    private void applyCommandFromTab(MetadataCommand command) {
        undoStack.pushAndApply(command);
    }

    private void refreshAfterCommand() {
        // Just trigger a status refresh; the working-copy tick listener
        // already refreshes the table and the keys list.
        updateStatusLabel();
    }

    /** Test-only accessor: working copy backing the table. */
    WorkingCopy workingCopy() {
        return workingCopy;
    }

    /** Test-only accessor: undo stack backing the menu items. */
    UndoStack undoStack() {
        return undoStack;
    }
}
