package qupath.ext.projectmetadatabrowser.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
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

import qupath.ext.projectmetadatabrowser.core.MetadataKeyOperations.Result;
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
 * Save / commit primitive tests: happy path applies every diff, rollback
 * reverts every touched entry on {@code IOException}, empty diff still
 * calls {@code syncChanges()} once.
 */
class CommitWorkingCopyTest {

    @Test
    void commitAppliesEveryDiffOnHappyPath() throws IOException {
        StubEntry a = new StubEntry("a", linkedMap("k", "v1"));
        StubEntry b = new StubEntry("b", linkedMap("k", "v2"));
        StubProject project = new StubProject(List.of(a, b));
        WorkingCopy wc = new WorkingCopy();
        wc.loadFrom(project);
        wc.getRowById("a").putWorkingValue("k", "newA");
        wc.getRowById("b").putWorkingValue("k", "newB");
        wc.getRowById("b").putWorkingValue("extra", "x");
        wc.applyCommand(new NoOpCmd());

        List<WorkingCopy.EntryDiff> diffs = wc.diff();
        Result r = MetadataKeyOperations.commitWorkingCopy(project, diffs);

        assertEquals(2, r.mutated());
        assertEquals("newA", a.getMetadata().get("k"));
        assertEquals("newB", b.getMetadata().get("k"));
        assertEquals("x", b.getMetadata().get("extra"));
        assertEquals(1, project.syncCount);
    }

    @Test
    void commitRollsBackOnIOException() {
        StubEntry a = new StubEntry("a", linkedMap("k", "v1"));
        StubEntry b = new StubEntry("b", linkedMap("k", "v2"));
        StubProject project = new StubProject(List.of(a, b));
        WorkingCopy wc = new WorkingCopy();
        wc.loadFrom(project);
        wc.getRowById("a").putWorkingValue("k", "newA");
        wc.getRowById("b").putWorkingValue("k", "newB");
        wc.applyCommand(new NoOpCmd());
        project.failNextSync = true;

        IOException thrown = assertThrows(IOException.class, () ->
                MetadataKeyOperations.commitWorkingCopy(project, wc.diff()));
        assertEquals("simulated sync failure", thrown.getMessage());

        // Both entries reverted in-memory.
        assertEquals("v1", a.getMetadata().get("k"));
        assertEquals("v2", b.getMetadata().get("k"));
        // First failing + post-revert second.
        assertEquals(2, project.syncCount);
    }

    @Test
    void commitEmptyDiffStillCallsSync() throws IOException {
        StubEntry a = new StubEntry("a", linkedMap("k", "v"));
        StubProject project = new StubProject(List.of(a));
        WorkingCopy wc = new WorkingCopy();
        wc.loadFrom(project);

        Result r = MetadataKeyOperations.commitWorkingCopy(project, wc.diff());
        assertEquals(0, r.mutated());
        assertEquals(1, project.syncCount);
    }

    @Test
    void commitRemovesKeyOnDiff() throws IOException {
        StubEntry a = new StubEntry("a", linkedMap("k", "v"));
        StubProject project = new StubProject(List.of(a));
        WorkingCopy wc = new WorkingCopy();
        wc.loadFrom(project);
        wc.getRowById("a").removeWorkingKey("k");
        wc.applyCommand(new NoOpCmd());

        Result r = MetadataKeyOperations.commitWorkingCopy(project, wc.diff());
        assertEquals(1, r.mutated());
        assertFalse(a.getMetadata().containsKey("k"));
    }

    @Test
    void commitUnknownEntryIdReportedAsFailedNotThrown() throws IOException {
        StubEntry a = new StubEntry("a", linkedMap("k", "v"));
        StubProject project = new StubProject(List.of(a));
        List<WorkingCopy.EntryDiff> diffs = new ArrayList<>();
        diffs.add(new WorkingCopy.EntryDiff("ghost", Map.of("k", "v2"), new HashSet<>()));

        Result r = MetadataKeyOperations.commitWorkingCopy(project, diffs);
        assertTrue(r.failedEntryIds().contains("ghost"));
        assertEquals(0, r.mutated());
    }

    private static final class NoOpCmd implements MetadataCommand {
        @Override public void apply(WorkingCopy wc) { }
        @Override public void undo(WorkingCopy wc) { }
        @Override public String description() { return "no-op"; }
    }

    private static Map<String, String> linkedMap(String k, String v) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(k, v);
        return m;
    }

    // ------------------------------------------------------------------
    // Stubs
    // ------------------------------------------------------------------

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
        boolean failNextSync = false;
        int syncCount = 0;
        StubProject(List<StubEntry> entries) {
            this.entries = new ArrayList<>(entries);
        }
        @Override public List<ProjectImageEntry<BufferedImage>> getImageList() {
            return Collections.unmodifiableList(entries);
        }
        @Override public void syncChanges() throws IOException {
            syncCount++;
            if (failNextSync) {
                failNextSync = false;
                throw new IOException("simulated sync failure");
            }
        }
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
