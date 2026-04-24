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
import java.util.List;
import java.util.Map;
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
import javafx.scene.control.Button;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.ext.projectmetadatabrowser.model.EntryRow;
import qupath.ext.projectmetadatabrowser.model.MetadataModel;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.projects.Project;

/**
 * Non-modal browser window. Single instance per QuPath session; reused when
 * the menu item is invoked again.
 */
public class MetadataBrowserWindow {

    private static final Logger logger = LoggerFactory.getLogger(MetadataBrowserWindow.class);

    private static MetadataBrowserWindow instance;

    private final QuPathGUI qupath;
    private final Stage stage;
    private final MetadataModel model = new MetadataModel();
    private final TableView<EntryRow> table = new TableView<>();
    private final TextField searchField = new TextField();
    private final Label statusLabel = new Label();
    private final FilteredList<EntryRow> filtered;
    private final SortedList<EntryRow> sorted;
    private final Menu columnsMenu = new Menu("Columns");

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
        stage.setTitle("Project Metadata Browser");
        stage.initOwner(qupath.getStage());
        stage.initModality(Modality.NONE);
        stage.setMinWidth(600);
        stage.setMinHeight(400);

        filtered = new FilteredList<>(model.getRows(), r -> true);
        sorted = new SortedList<>(filtered);
        sorted.comparatorProperty().bind(table.comparatorProperty());
        table.setItems(sorted);
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        table.setTableMenuButtonVisible(true);
        table.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        table.setPlaceholder(new Label("No project open, or project contains no images."));

        searchField.setPromptText("Search (all columns, case-insensitive)...");
        searchField.textProperty().addListener((obs, oldV, newV) -> applyFilter());

        Button refreshBtn = new Button("Refresh");
        refreshBtn.setTooltip(new Tooltip(
                "Reload entries and metadata from the active project (F5).\n"
                        + "Use this after a script or acquisition adds metadata."));
        refreshBtn.setOnAction(e -> reloadFromProject());

        Button exportBtn = new Button("Export...");
        exportBtn.setOnAction(e -> exportTable());

        Button closeBtn = new Button("Close");
        closeBtn.setOnAction(e -> stage.hide());

        HBox topBar = new HBox(8, new Label("Filter:"), searchField, refreshBtn);
        HBox.setHgrow(searchField, Priority.ALWAYS);
        topBar.setStyle("-fx-padding: 8;");

        HBox bottomBar = new HBox(8, statusLabel, spacer(), exportBtn, closeBtn);
        bottomBar.setStyle("-fx-padding: 8;");

        MenuBar menuBar = new MenuBar();
        Menu fileMenu = new Menu("File");
        MenuItem refreshItem = new MenuItem("Refresh");
        refreshItem.setAccelerator(new KeyCodeCombination(KeyCode.F5));
        refreshItem.setOnAction(e -> reloadFromProject());
        MenuItem exportItem = new MenuItem("Export...");
        exportItem.setOnAction(e -> exportTable());
        MenuItem closeItem = new MenuItem("Close");
        closeItem.setAccelerator(new KeyCodeCombination(KeyCode.ESCAPE));
        closeItem.setOnAction(e -> stage.hide());
        fileMenu.getItems().addAll(refreshItem, new SeparatorMenuItem(), exportItem,
                new SeparatorMenuItem(), closeItem);
        menuBar.getMenus().addAll(fileMenu, columnsMenu);

        BorderPane root = new BorderPane();
        VBox top = new VBox(menuBar, topBar);
        root.setTop(top);
        root.setCenter(table);
        root.setBottom(bottomBar);

        Scene scene = new Scene(root, 1100, 650);

        // Keyboard shortcuts
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN),
                this::copySelectionToClipboard);
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.F, KeyCombination.SHORTCUT_DOWN),
                searchField::requestFocus);

        table.setRowFactory(tv -> {
            TableRow<EntryRow> row = new TableRow<>();
            row.setOnMouseClicked(ev -> {
                if (ev.getClickCount() == 2 && !row.isEmpty()) {
                    openEntry(row.getItem());
                }
            });
            row.setContextMenu(buildRowContextMenu());
            return row;
        });

        // Filter live as status updates
        filtered.predicateProperty().addListener((obs, o, n) -> updateStatusLabel());
        model.getRows().addListener((ListChangeListener<EntryRow>) c -> updateStatusLabel());

        stage.setScene(scene);

        projectListener = (obs, oldProj, newProj) -> {
            if (stage.isShowing())
                Platform.runLater(this::reloadFromProject);
        };
        qupath.projectProperty().addListener(projectListener);
    }

    private static Node spacer() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    private void reloadFromProject() {
        Project<BufferedImage> project = qupath.getProject();

        // Preserve selection, sort, and column visibility across reload.
        Set<String> selectedIds = new HashSet<>();
        for (EntryRow r : table.getSelectionModel().getSelectedItems()) {
            if (r != null)
                selectedIds.add(r.getId());
        }

        Map<String, Boolean> visibilityByHeader = new HashMap<>();
        for (TableColumn<EntryRow, ?> c : table.getColumns())
            visibilityByHeader.put(c.getText(), c.isVisible());

        String sortHeader = null;
        TableColumn.SortType sortType = null;
        if (!table.getSortOrder().isEmpty()) {
            TableColumn<EntryRow, ?> primary = table.getSortOrder().get(0);
            sortHeader = primary.getText();
            sortType = primary.getSortType();
        }

        model.loadFrom(project);
        rebuildColumns();

        // Restore column visibility for headers that still exist.
        for (TableColumn<EntryRow, ?> c : table.getColumns()) {
            Boolean visible = visibilityByHeader.get(c.getText());
            if (visible != null)
                c.setVisible(visible);
        }

        // Restore sort.
        if (sortHeader != null) {
            for (TableColumn<EntryRow, ?> c : table.getColumns()) {
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

        updateStatusLabel();

        if (!selectedIds.isEmpty()) {
            for (EntryRow r : table.getItems()) {
                if (selectedIds.contains(r.getId()))
                    table.getSelectionModel().select(r);
            }
            EntryRow focus = table.getSelectionModel().getSelectedItem();
            if (focus != null)
                table.scrollTo(focus);
        }
    }

    /**
     * Map of column-header text -> resolver that produces the cell value for
     * a given row. We use an explicit map (instead of looking keys up by
     * column text inside {@link EntryRow#getValueForColumn}) so that a user
     * metadata key that collides with a built-in column name can be
     * disambiguated in the header (e.g. {@code "ID (metadata)"}) while still
     * reading from the right source.
     */
    private final Map<String, java.util.function.Function<EntryRow, String>> columnResolvers = new HashMap<>();

    private void rebuildColumns() {
        table.getColumns().clear();
        columnsMenu.getItems().clear();
        columnResolvers.clear();

        Set<String> builtInNames = Set.of(
                EntryRow.COL_NAME, EntryRow.COL_ID, EntryRow.COL_URI,
                EntryRow.COL_DESCRIPTION, EntryRow.COL_TAGS);

        addColumn(EntryRow.COL_NAME, EntryRow::getName);
        addColumn(EntryRow.COL_ID, EntryRow::getId);
        addColumn(EntryRow.COL_URI, EntryRow::getUri);
        addColumn(EntryRow.COL_DESCRIPTION, EntryRow::getDescription);
        addColumn(EntryRow.COL_TAGS, EntryRow::getTags);

        for (String key : model.getMetadataKeys()) {
            String header = builtInNames.contains(key) ? key + " (metadata)" : key;
            final String metadataKey = key;
            addColumn(header, r -> r.getMetadata(metadataKey));
        }
    }

    private void addColumn(String header, java.util.function.Function<EntryRow, String> resolver) {
        columnResolvers.put(header, resolver);
        TableColumn<EntryRow, String> tc = new TableColumn<>(header);
        tc.setCellValueFactory(cdf -> new ReadOnlyStringWrapper(resolver.apply(cdf.getValue())));
        tc.setCellFactory(col -> new TooltipTextCell());
        tc.setPrefWidth(preferredWidthFor(header));
        tc.setMinWidth(60);
        tc.setSortable(true);
        table.getColumns().add(tc);

        CheckMenuItem item = new CheckMenuItem(header);
        item.setSelected(true);
        item.selectedProperty().bindBidirectional(tc.visibleProperty());
        columnsMenu.getItems().add(item);
    }

    /**
     * TableCell that shows the full cell value in a tooltip on hover. Cheap
     * enough to use on every cell; the tooltip is only instantiated when the
     * cell actually has text.
     */
    private static final class TooltipTextCell extends TableCell<EntryRow, String> {
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

    private static double preferredWidthFor(String col) {
        switch (col) {
            case EntryRow.COL_NAME: return 220;
            case EntryRow.COL_ID: return 260;
            case EntryRow.COL_URI: return 320;
            case EntryRow.COL_DESCRIPTION: return 220;
            case EntryRow.COL_TAGS: return 120;
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
        Predicate<EntryRow> p = row -> {
            for (TableColumn<EntryRow, ?> c : table.getColumns()) {
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
        statusLabel.setText(String.format("Entries: %d shown / %d total",
                filtered.size(), model.getRows().size()));
    }

    private ContextMenu buildRowContextMenu() {
        MenuItem openItem = new MenuItem("Open image");
        openItem.setOnAction(e -> {
            EntryRow row = table.getSelectionModel().getSelectedItem();
            if (row != null)
                openEntry(row);
        });
        MenuItem copyItem = new MenuItem("Copy as TSV");
        copyItem.setOnAction(e -> copySelectionToClipboard());
        MenuItem editItem = new MenuItem("Edit metadata...");
        editItem.setOnAction(e -> {
            EntryRow row = table.getSelectionModel().getSelectedItem();
            if (row != null)
                editMetadata(row);
        });
        ContextMenu menu = new ContextMenu(openItem, copyItem, new SeparatorMenuItem(), editItem);
        // The edit dialog only supports one row. Reflect that in the menu so
        // users with a multi-row selection don't think they're editing all.
        menu.setOnShowing(e -> {
            int n = table.getSelectionModel().getSelectedItems().size();
            if (n > 1) {
                editItem.setText("Edit metadata... (only first of " + n + " selected)");
                editItem.setDisable(true);
            } else {
                editItem.setText("Edit metadata...");
                editItem.setDisable(false);
            }
        });
        return menu;
    }

    private void openEntry(EntryRow row) {
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

    private void editMetadata(EntryRow row) {
        // Snapshot the entry's metadata before the edit so we can roll back
        // if syncChanges() fails -- but only revert the keys the user
        // actually changed, so a concurrent script that added a new key
        // during the edit session is not clobbered.
        Map<String, String> snapshot = row.snapshotMetadata();
        Map<String, String> updates = MetadataEditDialog.showFor(stage, row);
        if (updates == null || updates.isEmpty())
            return;
        Project<BufferedImage> project = qupath.getProject();
        if (project == null)
            return;
        try {
            project.syncChanges();
        } catch (IOException e) {
            logger.error("Failed to sync project changes; rolling back edited keys", e);
            row.revertChanges(updates, snapshot);
            Dialogs.showErrorNotification("Project Metadata Browser",
                    "Saving failed; your edits were reverted: " + e.getMessage());
            reloadFromProject();
            return;
        }
        qupath.refreshProject();
        reloadFromProject();
    }

    private void copySelectionToClipboard() {
        // Snapshot to decouple from the live selection list -- iteration
        // must not race with any selection-change handlers.
        List<EntryRow> rows = new ArrayList<>(table.getSelectionModel().getSelectedItems());
        if (rows.isEmpty())
            return;
        List<TableColumn<EntryRow, ?>> visibleCols = visibleColumns();

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < visibleCols.size(); i++) {
            if (i > 0) sb.append('\t');
            sb.append(escapeCell(visibleCols.get(i).getText()));
        }
        sb.append('\n');
        for (EntryRow r : rows) {
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

    private String resolveCell(EntryRow row, String header) {
        var resolver = columnResolvers.get(header);
        return resolver == null ? "" : resolver.apply(row);
    }

    private void exportTable() {
        List<TableColumn<EntryRow, ?>> visibleCols = visibleColumns();
        if (visibleCols.isEmpty()) {
            Dialogs.showErrorNotification("Project Metadata Browser",
                    "No columns are visible to export. Enable at least one column from the table menu.");
            return;
        }

        FileChooser fc = new FileChooser();
        fc.setTitle("Export project metadata");
        fc.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Tab-separated values (*.tsv)", "*.tsv"),
                new FileChooser.ExtensionFilter("Comma-separated values (*.csv)", "*.csv"));
        java.io.File file = fc.showSaveDialog(stage);
        if (file == null)
            return;

        boolean csv = file.getName().toLowerCase().endsWith(".csv");
        char sep = csv ? ',' : '\t';

        try (BufferedWriter w = Files.newBufferedWriter(Path.of(file.toURI()), StandardCharsets.UTF_8)) {
            for (int i = 0; i < visibleCols.size(); i++) {
                if (i > 0) w.write(sep);
                w.write(escapeForDelimiter(visibleCols.get(i).getText(), sep));
            }
            w.write('\n');
            for (EntryRow r : sorted) {
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

    private List<TableColumn<EntryRow, ?>> visibleColumns() {
        List<TableColumn<EntryRow, ?>> out = new ArrayList<>();
        for (TableColumn<EntryRow, ?> c : table.getColumns()) {
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
        // CSV quoting rules (RFC 4180) when sep is a comma; TSV just strips tabs/newlines.
        if (sep == ',') {
            boolean needsQuote = s.indexOf(',') >= 0 || s.indexOf('"') >= 0
                    || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0;
            if (!needsQuote) return s;
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }
}
