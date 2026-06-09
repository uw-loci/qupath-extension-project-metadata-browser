package qupath.ext.projectmetadatabrowser.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import qupath.lib.projects.ProjectImageEntry;

class MetadataModelTest {

    @Test
    void unionMetadataKeysMergesDifferentKeysets() {
        ProjectImageEntry<BufferedImage> a = new StubEntry("a",
                Map.of("modality", "ppm", "objective", "20x"));
        ProjectImageEntry<BufferedImage> b = new StubEntry("b",
                Map.of("modality", "brightfield", "sample_name", "slide1"));
        ProjectImageEntry<BufferedImage> c = new StubEntry("c", Map.of());

        List<String> keys = MetadataModel.unionMetadataKeys(Arrays.asList(a, b, c));

        assertEquals(List.of("modality", "objective", "sample_name"), keys);
    }

    @Test
    void mutableEntryRowExposesBuiltInFieldsAndMetadata() {
        ProjectImageEntry<BufferedImage> entry = new StubEntry("img-1",
                Map.of("modality", "ppm"));
        MutableEntryRow row = new MutableEntryRow(entry);

        assertEquals("img-1", row.getName());
        assertEquals("img-1", row.getId());
        assertEquals("ppm", row.getMetadata("modality"));
        assertEquals("", row.getMetadata("nonexistent"));
    }

    @Test
    void putWorkingValueAddsUpdatesAndRemoves() {
        StubEntry entry = new StubEntry("e",
                Map.of("keepMe", "1", "removeMe", "bye"));
        MutableEntryRow row = new MutableEntryRow(entry);

        row.putWorkingValue("keepMe", "2");
        row.removeWorkingKey("removeMe");
        row.putWorkingValue("newKey", "fresh");

        Map<String, String> md = row.snapshotWorking();
        assertEquals("2", md.get("keepMe"));
        assertEquals("fresh", md.get("newKey"));
        assertFalse(md.containsKey("removeMe"));
    }

    @Test
    void putWorkingValueEmptyOrNullTreatsAsRemove() {
        StubEntry entry = new StubEntry("e", Map.of("k", "v"));
        MutableEntryRow row = new MutableEntryRow(entry);

        row.putWorkingValue("k", "");
        assertFalse(row.hasMetadata("k"));

        row.putWorkingValue("k", "v");
        row.putWorkingValue("k", null);
        assertFalse(row.hasMetadata("k"));
    }

    @Test
    void isDirtyTrackesPerCellChanges() {
        StubEntry entry = new StubEntry("e", Map.of("a", "1"));
        MutableEntryRow row = new MutableEntryRow(entry);

        assertFalse(row.isDirty());

        row.putWorkingValue("a", "2");
        assertTrue(row.isDirty());
        assertTrue(row.isCellDirty("a"));

        row.putWorkingValue("a", "1");
        assertFalse(row.isDirty());
        assertFalse(row.isCellDirty("a"));
    }

    @Test
    void markCleanResetsOriginalSnapshot() {
        StubEntry entry = new StubEntry("e", Map.of("a", "1"));
        MutableEntryRow row = new MutableEntryRow(entry);

        row.putWorkingValue("a", "2");
        assertTrue(row.isDirty());
        row.markClean();
        assertFalse(row.isDirty());
    }
}
