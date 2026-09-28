package qupath.ext.projectmetadatabrowser;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import qupath.lib.gui.prefs.PathPrefs;

/**
 * Persistent user preferences for the Project Metadata Browser. Backed by
 * QuPath's standard preference store via {@link PathPrefs}.
 */
public final class Preferences {

    /**
     * Maximum column width in pixels used by the "Fit Columns" button.
     * Cells whose content exceeds this width wrap to multiple lines rather
     * than expanding the column.
     */
    public static final IntegerProperty MAX_COLUMN_WIDTH =
            PathPrefs.createPersistentPreference(
                    "projectMetadataBrowser.maxColumnWidth", 400);

    /**
     * Whether the read-only image-metadata columns (image type, pixel size,
     * size, channels, magnification) are shown in the Entries tab.
     */
    public static final BooleanProperty SHOW_IMAGE_METADATA =
            PathPrefs.createPersistentPreference(
                    "projectMetadataBrowser.showImageMetadata", true);

    private Preferences() {
        // utility class -- no instances
    }
}
