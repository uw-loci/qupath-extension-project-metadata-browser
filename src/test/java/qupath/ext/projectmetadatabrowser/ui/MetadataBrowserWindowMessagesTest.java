package qupath.ext.projectmetadatabrowser.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Plain-data tests for user-visible-string helpers in
 * {@link MetadataBrowserWindow}. The window itself needs a JavaFX runtime,
 * but {@code formatPartialFailureMessage} is a package-private pure
 * function and can be exercised directly.
 *
 * <p>Covers Phase 5 FIX-2: the partial-save failure toast must include the
 * count and the first three failed entry IDs, with a "+M more" suffix when
 * the list is longer.
 */
class MetadataBrowserWindowMessagesTest {

    @Test
    void singleFailureMessageNamesTheEntry() {
        String msg = MetadataBrowserWindow.formatPartialFailureMessage(List.of("entry-001"));
        assertEquals(
                "Could not save 1 entry. Check the project file is writable. "
                        + "Failed entries: entry-001.",
                msg);
    }

    @Test
    void twoFailuresUsePluralAndListBoth() {
        String msg = MetadataBrowserWindow.formatPartialFailureMessage(
                List.of("a", "b"));
        assertEquals(
                "Could not save 2 entries. Check the project file is writable. "
                        + "Failed entries: a, b.",
                msg);
    }

    @Test
    void exactlyThreeFailuresShowAllNoSuffix() {
        String msg = MetadataBrowserWindow.formatPartialFailureMessage(
                List.of("a", "b", "c"));
        assertEquals(
                "Could not save 3 entries. Check the project file is writable. "
                        + "Failed entries: a, b, c.",
                msg);
    }

    @Test
    void fourFailuresShowFirstThreeAndSuffix() {
        String msg = MetadataBrowserWindow.formatPartialFailureMessage(
                List.of("a", "b", "c", "d"));
        assertEquals(
                "Could not save 4 entries. Check the project file is writable. "
                        + "Failed entries: a, b, c (+1 more).",
                msg);
    }

    @Test
    void manyFailuresShowFirstThreeAndCorrectMoreCount() {
        String msg = MetadataBrowserWindow.formatPartialFailureMessage(
                List.of("id-1", "id-2", "id-3", "id-4", "id-5", "id-6", "id-7"));
        assertTrue(msg.startsWith("Could not save 7 entries."));
        assertTrue(msg.contains("Failed entries: id-1, id-2, id-3 (+4 more)."));
    }
}
