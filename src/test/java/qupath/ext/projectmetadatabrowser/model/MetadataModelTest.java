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

    @Test
    void applyMetadataChangesTreatsWhitespaceValueAsDelete() {
        StubEntry entry = new StubEntry("e", Map.of("k", "v"));
        EntryRow row = new EntryRow(entry);

        row.applyMetadataChanges(java.util.Collections.singletonMap("k", "   "));

        assertTrue(!entry.getMetadata().containsKey("k"));
    }

    @Test
    void applyMetadataChangesIgnoresNullKey() {
        StubEntry entry = new StubEntry("e", Map.of("k", "v"));
        EntryRow row = new EntryRow(entry);

        row.applyMetadataChanges(java.util.Collections.singletonMap(null, "x"));

        assertEquals("v", entry.getMetadata().get("k"));
        assertEquals(1, entry.getMetadata().size());
    }

    @Test
    void revertChangesUndoesOnlyEditedKeys() {
        StubEntry entry = new StubEntry("e", Map.of("a", "1"));
        EntryRow row = new EntryRow(entry);
        Map<String, String> snap = row.snapshotMetadata();

        // Simulate the user's edit: change "a" from 1 -> 2, add "b".
        Map<String, String> updates = new java.util.HashMap<>();
        updates.put("a", "2");
        updates.put("b", "new");
        row.applyMetadataChanges(updates);
        assertEquals("2", entry.getMetadata().get("a"));
        assertEquals("new", entry.getMetadata().get("b"));

        // Simulate a concurrent script adding a different key.
        entry.getMetadata().put("script_key", "script_val");

        // Revert the user's edits -- script_key must survive.
        row.revertChanges(updates, snap);
        assertEquals("1", entry.getMetadata().get("a"));
        assertTrue(!entry.getMetadata().containsKey("b"));
        assertEquals("script_val", entry.getMetadata().get("script_key"));
    }
}
