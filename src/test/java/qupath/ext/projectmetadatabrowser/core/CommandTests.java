package qupath.ext.projectmetadatabrowser.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import qupath.ext.projectmetadatabrowser.core.MetadataKeyOperations.CollisionPolicy;
import qupath.ext.projectmetadatabrowser.model.MutableEntryRow;
import qupath.ext.projectmetadatabrowser.model.WorkingCopy;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServerBuilder.ServerBuilder;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.projects.ResourceManager.Manager;

/**
 * Round-trip tests for each MetadataCommand implementation. Each test
 * applies the command, checks the post-apply state, then undoes the
 * command and checks the pre-apply state is restored exactly.
 */
class CommandTests {

    @Test
    void setCellCommandRoundTrips() {
        WorkingCopy wc = wcOf(entry("a", Map.of("k", "v1")));
        SetCellCommand cmd = new SetCellCommand("a", "k", "v1", "v2");

        wc.applyCommand(cmd);
        assertEquals("v2", wc.getRowById("a").getMetadata("k"));

        wc.undoCommand(cmd);
        assertEquals("v1", wc.getRowById("a").getMetadata("k"));
        assertFalse(wc.isDirty());
    }

    @Test
    void setCellCommandClearingValueRemovesKey() {
        WorkingCopy wc = wcOf(entry("a", Map.of("k", "v1")));
        SetCellCommand cmd = new SetCellCommand("a", "k", "v1", "");

        wc.applyCommand(cmd);
        assertFalse(wc.getRowById("a").hasMetadata("k"));

        wc.undoCommand(cmd);
        assertEquals("v1", wc.getRowById("a").getMetadata("k"));
    }

    @Test
    void addColumnCommandRoundTrips() {
        WorkingCopy wc = wcOf(entry("a", Map.of("k", "v")));
        AddColumnCommand cmd = new AddColumnCommand("brand_new");

        wc.applyCommand(cmd);
        assertTrue(wc.getColumnKeys().contains("brand_new"));

        wc.undoCommand(cmd);
        assertFalse(wc.getColumnKeys().contains("brand_new"));
    }

    @Test
    void removeColumnCommandRestoresEveryValueOnUndo() {
        WorkingCopy wc = wcOf(
                entry("a", Map.of("foo", "1", "bar", "x")),
                entry("b", Map.of("foo", "2")));
        RemoveColumnCommand cmd = new RemoveColumnCommand("foo");

        wc.applyCommand(cmd);
        assertFalse(wc.getRowById("a").hasMetadata("foo"));
        assertFalse(wc.getRowById("b").hasMetadata("foo"));
        assertFalse(wc.getColumnKeys().contains("foo"));
        assertEquals(2, cmd.affectedEntryCount());

        wc.undoCommand(cmd);
        assertEquals("1", wc.getRowById("a").getMetadata("foo"));
        assertEquals("2", wc.getRowById("b").getMetadata("foo"));
        assertTrue(wc.getColumnKeys().contains("foo"));
    }

    @Test
    void renameColumnCommandOverwritePolicyRoundTrips() {
        WorkingCopy wc = wcOf(
                entry("a", Map.of("typo", "v1")),
                entry("b", linkedMap("typo", "v2", "Antibody", "kept")));
        RenameColumnCommand cmd = new RenameColumnCommand("typo", "Antibody", CollisionPolicy.OVERWRITE);

        wc.applyCommand(cmd);
        assertEquals("v1", wc.getRowById("a").getMetadata("Antibody"));
        assertEquals("v2", wc.getRowById("b").getMetadata("Antibody"));
        assertFalse(wc.getRowById("a").hasMetadata("typo"));
        assertFalse(wc.getRowById("b").hasMetadata("typo"));

        wc.undoCommand(cmd);
        assertEquals("v1", wc.getRowById("a").getMetadata("typo"));
        assertEquals("v2", wc.getRowById("b").getMetadata("typo"));
        assertEquals("kept", wc.getRowById("b").getMetadata("Antibody"));
        assertFalse(wc.getRowById("a").hasMetadata("Antibody"));
    }

    @Test
    void renameColumnCommandSkipPolicyKeepsExisting() {
        WorkingCopy wc = wcOf(
                entry("a", linkedMap("typo", "src", "Antibody", "kept")));
        RenameColumnCommand cmd = new RenameColumnCommand("typo", "Antibody", CollisionPolicy.SKIP);

        wc.applyCommand(cmd);
        assertEquals("kept", wc.getRowById("a").getMetadata("Antibody"));
        assertFalse(wc.getRowById("a").hasMetadata("typo"));

        wc.undoCommand(cmd);
        assertEquals("src", wc.getRowById("a").getMetadata("typo"));
        assertEquals("kept", wc.getRowById("a").getMetadata("Antibody"));
    }

    @Test
    void bulkSetCellsCommandAppliesAllOrNothing() {
        WorkingCopy wc = wcOf(
                entry("a", Map.of("k", "v1")),
                entry("b", Map.of("k", "v2")));
        List<BulkSetCellsCommand.CellDelta> deltas = List.of(
                new BulkSetCellsCommand.CellDelta("a", "k", "v1", "A"),
                new BulkSetCellsCommand.CellDelta("b", "k", "v2", "B"),
                new BulkSetCellsCommand.CellDelta("a", "new", "", "C"));
        BulkSetCellsCommand cmd = new BulkSetCellsCommand("Paste", deltas);

        wc.applyCommand(cmd);
        assertEquals("A", wc.getRowById("a").getMetadata("k"));
        assertEquals("B", wc.getRowById("b").getMetadata("k"));
        assertEquals("C", wc.getRowById("a").getMetadata("new"));
        assertTrue(wc.getColumnKeys().contains("new"));

        wc.undoCommand(cmd);
        assertEquals("v1", wc.getRowById("a").getMetadata("k"));
        assertEquals("v2", wc.getRowById("b").getMetadata("k"));
        assertFalse(wc.getRowById("a").hasMetadata("new"));
        assertFalse(wc.getColumnKeys().contains("new"));
    }

    @Test
    void importCommandRoundTripsAddsNewColumnsAndDeltas() {
        WorkingCopy wc = wcOf(entry("a", Map.of("k", "v")));
        List<BulkSetCellsCommand.CellDelta> deltas = List.of(
                new BulkSetCellsCommand.CellDelta("a", "Condition", "", "Tumor"));
        ImportCommand cmd = new ImportCommand("metadata.csv", List.of("Condition"), deltas);

        wc.applyCommand(cmd);
        assertEquals("Tumor", wc.getRowById("a").getMetadata("Condition"));
        assertTrue(wc.getColumnKeys().contains("Condition"));

        wc.undoCommand(cmd);
        assertFalse(wc.getRowById("a").hasMetadata("Condition"));
        assertFalse(wc.getColumnKeys().contains("Condition"));
    }

    @Test
    void regexExtractCommandRoundTrips() {
        WorkingCopy wc = wcOf(entry("a", Map.of("dummy", "v")));
        List<BulkSetCellsCommand.CellDelta> deltas = List.of(
                new BulkSetCellsCommand.CellDelta("a", "patient", "", "P12"),
                new BulkSetCellsCommand.CellDelta("a", "tp", "", "T03"));
        RegexExtractCommand cmd = new RegexExtractCommand(
                "(?<patient>P\\d+)_(?<tp>T\\d+).*",
                List.of("patient", "tp"),
                deltas);

        wc.applyCommand(cmd);
        assertEquals("P12", wc.getRowById("a").getMetadata("patient"));
        assertEquals("T03", wc.getRowById("a").getMetadata("tp"));

        wc.undoCommand(cmd);
        assertFalse(wc.getRowById("a").hasMetadata("patient"));
        assertFalse(wc.getRowById("a").hasMetadata("tp"));
    }

    @Test
    void undoStackPushApplyUndoRedoClear() {
        WorkingCopy wc = wcOf(entry("a", Map.of("k", "v1")));
        UndoStack stack = new UndoStack(wc);

        stack.pushAndApply(new SetCellCommand("a", "k", "v1", "v2"));
        assertEquals(1, stack.undoSize());
        assertEquals(0, stack.redoSize());

        stack.undo();
        assertEquals(0, stack.undoSize());
        assertEquals(1, stack.redoSize());
        assertEquals("v1", wc.getRowById("a").getMetadata("k"));

        stack.redo();
        assertEquals("v2", wc.getRowById("a").getMetadata("k"));
        assertEquals(1, stack.undoSize());
        assertEquals(0, stack.redoSize());

        stack.clear();
        assertEquals(0, stack.undoSize());
        assertEquals(0, stack.redoSize());
    }

    @Test
    void newCommandAfterUndoClearsRedoStack() {
        WorkingCopy wc = wcOf(entry("a", Map.of("k", "v1")));
        UndoStack stack = new UndoStack(wc);

        stack.pushAndApply(new SetCellCommand("a", "k", "v1", "v2"));
        stack.undo();
        assertEquals(1, stack.redoSize());
        stack.pushAndApply(new SetCellCommand("a", "k", "v1", "v3"));
        assertEquals(0, stack.redoSize());
    }

    // ------------------------------------------------------------------
    // Helpers + stubs
    // ------------------------------------------------------------------

    private static Map<String, String> linkedMap(String k1, String v1, String k2, String v2) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(k1, v1);
        m.put(k2, v2);
        return m;
    }

    private static StubEntry entry(String id, Map<String, String> md) {
        return new StubEntry(id, md);
    }

    private static WorkingCopy wcOf(StubEntry... entries) {
        StubProject project = new StubProject(List.of(entries));
        WorkingCopy wc = new WorkingCopy();
        wc.loadFrom(project);
        return wc;
    }

    static final class StubEntry implements ProjectImageEntry<BufferedImage> {
        private final String id;
        private final Map<String, String> metadata = new LinkedHashMap<>();
        StubEntry(String id, Map<String, String> initial) {
            this.id = id;
            metadata.putAll(initial);
        }
        @Override public String getID() { return id; }
        @Override public String getImageName() { return id; }
        @Override public String getOriginalImageName() { return id; }
        @Override public void setImageName(String name) { }
        @Override public String getDescription() { return ""; }
        @Override public void setDescription(String description) { }
        @Override public Map<String, String> getMetadata() { return metadata; }
        @Override public Set<String> getTags() { return new HashSet<>(); }
        @Override public Collection<java.net.URI> getURIs() { return Collections.emptyList(); }
        @Override public boolean updateURIs(Map<java.net.URI, java.net.URI> replacements) { return false; }
        @Override public java.nio.file.Path getEntryPath() { return null; }
        @Override public ServerBuilder<BufferedImage> getServerBuilder() { return null; }
        @Override public ImageData<BufferedImage> readImageData() { throw new UnsupportedOperationException(); }
        @Override public void saveImageData(ImageData<BufferedImage> imageData) { throw new UnsupportedOperationException(); }
        @Override public PathObjectHierarchy readHierarchy() { throw new UnsupportedOperationException(); }
        @Override public boolean hasImageData() { return false; }
        @Override public String getSummary() { return id; }
        @Override public BufferedImage getThumbnail() { return null; }
        @Override public void setThumbnail(BufferedImage img) { }
        @Override public Manager<ImageServer<BufferedImage>> getImages() { return null; }
    }

    static final class StubProject implements Project<BufferedImage> {
        private final List<ProjectImageEntry<BufferedImage>> entries;
        StubProject(List<StubEntry> entries) {
            this.entries = new ArrayList<>(entries);
        }
        @Override public List<ProjectImageEntry<BufferedImage>> getImageList() {
            return Collections.unmodifiableList(entries);
        }
        @Override public void syncChanges() { }
        @Override public ProjectImageEntry<BufferedImage> addImage(ServerBuilder<BufferedImage> server) { throw new UnsupportedOperationException(); }
        @Override public ProjectImageEntry<BufferedImage> addDuplicate(ProjectImageEntry<BufferedImage> entry, boolean copyData) { throw new UnsupportedOperationException(); }
        @Override public ProjectImageEntry<BufferedImage> getEntry(ImageData<BufferedImage> imageData) { return null; }
        @Override public void removeImage(ProjectImageEntry<?> entry, boolean removeAllData) { }
        @Override public void removeAllImages(Collection<ProjectImageEntry<BufferedImage>> entries, boolean removeAllData) { }
        @Override public String getName() { return "stub"; }
        @Override public java.nio.file.Path getPath() { return null; }
        @Override public java.net.URI getURI() { return null; }
        @Override public java.net.URI getPreviousURI() { return null; }
        @Override public List<PathClass> getPathClasses() { return Collections.emptyList(); }
        @Override public boolean setPathClasses(Collection<? extends PathClass> pathClasses) { return false; }
        @Override public boolean getMaskImageNames() { return false; }
        @Override public void setMaskImageNames(boolean mask) { }
        @Override public Project<BufferedImage> createSubProject(String name, Collection<ProjectImageEntry<BufferedImage>> entries) { throw new UnsupportedOperationException(); }
        @Override public long getCreationTimestamp() { return 0L; }
        @Override public long getModificationTimestamp() { return 0L; }
        @Override public String getVersion() { return null; }
        @Override public boolean isEmpty() { return entries.isEmpty(); }
        @Override public Map<String, String> getMetadata() { return new HashMap<>(); }
    }
}
