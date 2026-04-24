package qupath.ext.projectmetadatabrowser.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

        // Alphabetically sorted union
        assertEquals(List.of("modality", "objective", "sample_name"), keys);
    }

    @Test
    void entryRowExposesBuiltInFieldsAndMetadata() {
        ProjectImageEntry<BufferedImage> entry = new StubEntry("img-1",
                Map.of("modality", "ppm"));
        EntryRow row = new EntryRow(entry);

        assertEquals("img-1", row.getName());
        assertEquals("img-1", row.getId());
        assertEquals("ppm", row.getValueForColumn("modality"));
        assertEquals("", row.getValueForColumn("nonexistent"));
        assertEquals("ppm", row.getValueForColumn("modality"));
    }

    @Test
    void applyMetadataChangesAddsUpdatesAndRemoves() {
        StubEntry entry = new StubEntry("e",
                Map.of("keepMe", "1", "removeMe", "bye"));
        EntryRow row = new EntryRow(entry);

        row.applyMetadataChanges(Map.of(
                "keepMe", "2",          // update
                "removeMe", "",         // empty -> remove
                "newKey", "fresh"       // add
        ));

        Map<String, String> md = entry.getMetadata();
        assertEquals("2", md.get("keepMe"));
        assertEquals("fresh", md.get("newKey"));
        assertTrue(!md.containsKey("removeMe"));
    }
}
