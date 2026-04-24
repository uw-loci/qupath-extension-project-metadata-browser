package qupath.ext.projectmetadatabrowser;

import javafx.application.Platform;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.projectmetadatabrowser.ui.MetadataBrowserWindow;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.common.Version;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.QuPathExtension;

/**
 * QuPath extension that opens a browser window over the active project,
 * showing every {@code ProjectImageEntry} as a row and every built-in field
 * plus user metadata key as a sortable column.
 */
public class ProjectMetadataBrowserExtension implements QuPathExtension {

    private static final Logger logger = LoggerFactory.getLogger(ProjectMetadataBrowserExtension.class);

    private static final String EXTENSION_NAME = "Project Metadata Browser";
    private static final String EXTENSION_DESCRIPTION =
            "Browse, filter, and edit metadata for all images in a QuPath project.";
    private static final Version EXTENSION_QUPATH_VERSION = Version.parse("v0.6.0");

    private boolean isInstalled = false;

    @Override
    public String getName() {
        return EXTENSION_NAME;
    }

    @Override
    public String getDescription() {
        return EXTENSION_DESCRIPTION;
    }

    @Override
    public Version getQuPathVersion() {
        return EXTENSION_QUPATH_VERSION;
    }

    @Override
    public void installExtension(QuPathGUI qupath) {
        if (isInstalled) {
            logger.warn("{} is already installed", EXTENSION_NAME);
            return;
        }
        logger.info("Installing extension: {}", EXTENSION_NAME);
        Platform.runLater(() -> addMenuItems(qupath));
        isInstalled = true;
    }

    private void addMenuItems(QuPathGUI qupath) {
        Menu menu = qupath.getMenu("Extensions>" + EXTENSION_NAME, true);
        MenuItem browseItem = new MenuItem("Browse Metadata...");
        browseItem.setOnAction(e -> {
            if (qupath.getProject() == null) {
                Dialogs.showInfoNotification(EXTENSION_NAME,
                        "No project is open. Open a QuPath project first.");
                return;
            }
            MetadataBrowserWindow.showFor(qupath);
        });
        menu.getItems().add(browseItem);
    }
}
