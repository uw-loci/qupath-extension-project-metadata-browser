package qupath.ext.projectmetadatabrowser.ui;

import java.awt.image.BufferedImage;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
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

        filtered = new FilteredList<>(model.getRows(), r -> true);
        sorted = new SortedList<>(filtered);
        sorted.comparatorProperty().bind(table.comparatorProperty());
        table.setItems(sorted);
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        table.setTableMenuButtonVisible(true);

        searchField.setPromptText("Search (all columns, case-insensitive)...");
        searchField.textProperty().addListener((obs, oldV, newV) -> applyFilter());

        Button exportBtn = new Button("Export...");
        exportBtn.setOnAction(e -> exportTable());

        Button closeBtn = new Button("Close");
        closeBtn.setOnAction(e -> stage.hide());

        HBox topBar = new HBox(8, new Label("Filter:"), searchField);
        HBox.setHgrow(searchField, Priority.ALWAYS);
        topBar.setStyle("-fx-padding: 8;");

        HBox bottomBar = new HBox(8, statusLabel, spacer(), exportBtn, closeBtn);
        bottomBar.setStyle("-fx-padding: 8;");

        MenuBar menuBar = new MenuBar();
        Menu fileMenu = new Menu("File");
        MenuItem refreshItem = new MenuItem("Refresh");
        refreshItem.setOnAction(e -> reloadFromProject());
        MenuItem exportItem = new MenuItem("Export...");
        exportItem.setOnAction(e -> exportTable());
        MenuItem closeItem = new MenuItem("Close");
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
        model.loadFrom(project);
        rebuildColumns();
        updateStatusLabel();
    }

    private void rebuildColumns() {
        table.getColumns().clear();
        columnsMenu.getItems().clear();

        List<String> allColumns = new ArrayList<>();
        allColumns.add(EntryRow.COL_NAME);
        allColumns.add(EntryRow.COL_ID);
        allColumns.add(EntryRow.COL_URI);
        allColumns.add(EntryRow.COL_DESCRIPTION);
        allColumns.add(EntryRow.COL_TAGS);
        allColumns.addAll(model.getMetadataKeys());

        for (String col : allColumns) {
            TableColumn<EntryRow, String> tc = new TableColumn<>(col);
            tc.setCellValueFactory(cdf ->
                    new ReadOnlyStringWrapper(cdf.getValue().getValueForColumn(col)));
            tc.setPrefWidth(preferredWidthFor(col));
            tc.setSortable(true);
            table.getColumns().add(tc);

            CheckMenuItem item = new CheckMenuItem(col);
            item.setSelected(true);
            item.selectedProperty().bindBidirectional(tc.visibleProperty());
            columnsMenu.getItems().add(item);
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
                String v = row.getValueForColumn(c.getText());
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
        return new ContextMenu(openItem, copyItem, new SeparatorMenuItem(), editItem);
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
        boolean changed = MetadataEditDialog.showFor(stage, row);
        if (!changed)
            return;
        Project<BufferedImage> project = qupath.getProject();
        if (project == null)
            return;
        try {
            project.syncChanges();
        } catch (IOException e) {
            logger.error("Failed to sync project changes", e);
            Dialogs.showErrorNotification("Project Metadata Browser",
                    "Metadata updated in memory, but saving failed: " + e.getMessage());
        }
        qupath.refreshProject();
        reloadFromProject();
    }

    private void copySelectionToClipboard() {
        List<EntryRow> rows = table.getSelectionModel().getSelectedItems();
        if (rows == null || rows.isEmpty())
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
                sb.append(escapeCell(r.getValueForColumn(visibleCols.get(i).getText())));
            }
            sb.append('\n');
        }

        ClipboardContent cc = new ClipboardContent();
        cc.putString(sb.toString());
        Clipboard.getSystemClipboard().setContent(cc);
    }

    private void exportTable() {
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
        List<TableColumn<EntryRow, ?>> visibleCols = visibleColumns();

        try (BufferedWriter w = Files.newBufferedWriter(Path.of(file.toURI()), StandardCharsets.UTF_8)) {
            for (int i = 0; i < visibleCols.size(); i++) {
                if (i > 0) w.write(sep);
                w.write(escapeForDelimiter(visibleCols.get(i).getText(), sep));
            }
            w.write('\n');
            for (EntryRow r : sorted) {
                for (int i = 0; i < visibleCols.size(); i++) {
                    if (i > 0) w.write(sep);
                    w.write(escapeForDelimiter(r.getValueForColumn(visibleCols.get(i).getText()), sep));
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
