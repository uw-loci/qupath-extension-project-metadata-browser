package qupath.ext.projectmetadatabrowser.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
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
import qupath.ext.projectmetadatabrowser.core.MetadataKeyOperations.Result;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServerBuilder.ServerBuilder;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.projects.ResourceManager.Manager;

/**
 * Unit tests for {@link MetadataKeyOperations} -- exercise rename happy path,
 * rename with collision under both policies, remove happy path, and the
 * pre-mutation snapshot capture so an {@link IOException} from
 * {@code project.syncChanges()} rolls back every touched entry.
 *
 * <p>The {@link StubProject} fake reads its image list from an in-memory list
 * of {@link StubEntry} instances; the {@link #syncChanges()} call optionally
 * throws on the next invocation to simulate a write failure mid-batch.
 */
class MetadataKeyOperationsTest {

    @Test
    void renameAcrossProjectMovesValueOnEveryEntryWithOldKey() throws IOException {
        StubEntry a = new StubEntry("a", Map.of("typo_Antibody", "anti-CD3"));
        StubEntry b = new StubEntry("b", Map.of("typo_Antibody", "anti-CD20"));
        StubEntry c = new StubEntry("c", Map.of("Antibody", "anti-CD8"));  // unrelated
        StubProject project = new StubProject(List.of(a, b, c));

        Result result = MetadataKeyOperations.renameAcrossProject(
                project, "typo_Antibody", "Antibody", CollisionPolicy.OVERWRITE);

        assertEquals(2, result.mutated());
        assertTrue(result.failedEntryIds().isEmpty());
        assertEquals("anti-CD3", a.getMetadata().get("Antibody"));
        assertFalse(a.getMetadata().containsKey("typo_Antibody"));
        assertEquals("anti-CD20", b.getMetadata().get("Antibody"));
        assertFalse(b.getMetadata().containsKey("typo_Antibody"));
        assertEquals("anti-CD8", c.getMetadata().get("Antibody"));  // untouched
        assertEquals(1, project.syncCount);
    }

    @Test
    void renameCollisionOverwritePolicyReplacesExistingTargetValue() throws IOException {
        // Entry b has BOTH typo_Antibody and Antibody set: collision case.
        StubEntry a = new StubEntry("a", Map.of("typo_Antibody", "anti-CD3"));
        StubEntry b = new StubEntry("b", linkedMap(
                "typo_Antibody", "anti-CD20",
                "Antibody", "anti-original"));
        StubProject project = new StubProject(List.of(a, b));

        Result result = MetadataKeyOperations.renameAcrossProject(
                project, "typo_Antibody", "Antibody", CollisionPolicy.OVERWRITE);

        assertEquals(2, result.mutated());
        // b: the typo value wins, original Antibody value gone.
        assertEquals("anti-CD20", b.getMetadata().get("Antibody"));
        assertFalse(b.getMetadata().containsKey("typo_Antibody"));
        // a: simple move.
        assertEquals("anti-CD3", a.getMetadata().get("Antibody"));
        assertFalse(a.getMetadata().containsKey("typo_Antibody"));
    }

    @Test
    void renameCollisionSkipPolicyKeepsExistingTargetValueButRemovesOldKey() throws IOException {
        StubEntry a = new StubEntry("a", Map.of("typo_Antibody", "anti-CD3"));
        StubEntry b = new StubEntry("b", linkedMap(
                "typo_Antibody", "anti-CD20",
                "Antibody", "anti-original"));
        StubProject project = new StubProject(List.of(a, b));

        Result result = MetadataKeyOperations.renameAcrossProject(
                project, "typo_Antibody", "Antibody", CollisionPolicy.SKIP);

        assertEquals(2, result.mutated());
        // b: existing Antibody value preserved, typo removed.
        assertEquals("anti-original", b.getMetadata().get("Antibody"));
        assertFalse(b.getMetadata().containsKey("typo_Antibody"));
        // a: simple move.
        assertEquals("anti-CD3", a.getMetadata().get("Antibody"));
    }

    @Test
    void removeAcrossProjectDeletesKeyEverywhereButLeavesOthersAlone() throws IOException {
        StubEntry a = new StubEntry("a", Map.of("foo", "1", "bar", "x"));
        StubEntry b = new StubEntry("b", Map.of("foo", "2"));
        StubEntry c = new StubEntry("c", Map.of("baz", "z"));
        StubProject project = new StubProject(List.of(a, b, c));

        Result result = MetadataKeyOperations.removeAcrossProject(project, "foo");

        assertEquals(2, result.mutated());
        assertTrue(result.failedEntryIds().isEmpty());
        assertFalse(a.getMetadata().containsKey("foo"));
        assertEquals("x", a.getMetadata().get("bar"));
        assertFalse(b.getMetadata().containsKey("foo"));
        assertEquals("z", c.getMetadata().get("baz"));
        assertEquals(1, project.syncCount);
    }

    @Test
    void renameRollsBackEveryTouchedEntryOnSyncIOException() {
        StubEntry a = new StubEntry("a", Map.of("typo", "v1"));
        StubEntry b = new StubEntry("b", linkedMap("typo", "v2", "Antibody", "original"));
        StubEntry c = new StubEntry("c", Map.of("untouched", "stays"));
        StubProject project = new StubProject(List.of(a, b, c));
        project.failNextSync = true;

        IOException thrown = assertThrows(IOException.class, () ->
                MetadataKeyOperations.renameAcrossProject(
                        project, "typo", "Antibody", CollisionPolicy.OVERWRITE));
        assertEquals("simulated sync failure", thrown.getMessage());

        // Both touched entries reverted to their pre-mutation state.
        assertEquals("v1", a.getMetadata().get("typo"));
        assertFalse(a.getMetadata().containsKey("Antibody"));
        assertEquals("v2", b.getMetadata().get("typo"));
        assertEquals("original", b.getMetadata().get("Antibody"));
        // Untouched entry untouched.
        assertEquals("stays", c.getMetadata().get("untouched"));
        // The initial sync failed, then the post-revert sync persisted the
        // rolled-back state -- two sync calls in total.
        assertEquals(2, project.syncCount);
    }

    @Test
    void removeRollsBackEveryTouchedEntryOnSyncIOException() {
        StubEntry a = new StubEntry("a", Map.of("foo", "1", "keep", "x"));
        StubEntry b = new StubEntry("b", Map.of("foo", "2"));
        StubProject project = new StubProject(List.of(a, b));
        project.failNextSync = true;

        assertThrows(IOException.class, () ->
                MetadataKeyOperations.removeAcrossProject(project, "foo"));

        assertEquals("1", a.getMetadata().get("foo"));
        assertEquals("x", a.getMetadata().get("keep"));
        assertEquals("2", b.getMetadata().get("foo"));
    }

    @Test
    void renameRejectsBlankKeys() {
        StubProject project = new StubProject(List.of());
        assertThrows(IllegalArgumentException.class, () ->
                MetadataKeyOperations.renameAcrossProject(project, "", "newKey", CollisionPolicy.SKIP));
        assertThrows(IllegalArgumentException.class, () ->
                MetadataKeyOperations.renameAcrossProject(project, "oldKey", "   ", CollisionPolicy.SKIP));
    }

    @Test
    void removeRejectsBlankKeys() {
        StubProject project = new StubProject(List.of());
        assertThrows(IllegalArgumentException.class, () ->
                MetadataKeyOperations.removeAcrossProject(project, ""));
        assertThrows(IllegalArgumentException.class, () ->
                MetadataKeyOperations.removeAcrossProject(project, " \t "));
    }

    @Test
    void renameSnapshotPreservesKeysAddedByConcurrentScriptBeforeMutation() throws IOException {
        // Capture-snapshot is per-entry-pre-mutation; if the rename happy path
        // succeeds we expect the existing extra key to survive. Confirms that
        // touching one key doesn't accidentally remove others.
        StubEntry a = new StubEntry("a", linkedMap("typo", "1", "extra", "from_script"));
        StubProject project = new StubProject(List.of(a));

        MetadataKeyOperations.renameAcrossProject(
                project, "typo", "Antibody", CollisionPolicy.OVERWRITE);

        assertEquals("1", a.getMetadata().get("Antibody"));
        assertFalse(a.getMetadata().containsKey("typo"));
        assertEquals("from_script", a.getMetadata().get("extra"));
    }

    @Test
    void countCollisionsReturnsEntriesWithBothKeys() {
        // Verifies: countCollisions returns the number of entries that have
        // BOTH the old and new keys set, ignoring entries that have only one
        // or neither.
        StubEntry a = new StubEntry("a", linkedMap("typo", "v1", "Antibody", "ov1"));
        StubEntry b = new StubEntry("b", linkedMap("typo", "v2", "Antibody", "ov2"));
        StubEntry c = new StubEntry("c", Map.of("typo", "only_old"));
        StubEntry d = new StubEntry("d", Map.of("Antibody", "only_new"));
        StubEntry e = new StubEntry("e", Map.of("unrelated", "value"));
        StubProject project = new StubProject(List.of(a, b, c, d, e));

        int collisions = MetadataKeyOperations.countCollisions(project, "typo", "Antibody");

        assertEquals(2, collisions);
        // No side effects -- sync must not have been called.
        assertEquals(0, project.syncCount);
    }

    @Test
    void countCollisionsReturnsZeroForBlankOrEqualKeys() {
        // Verifies: countCollisions short-circuits to 0 for null / blank
        // arguments and for oldKey.equals(newKey).
        StubEntry a = new StubEntry("a", linkedMap("typo", "v", "Antibody", "v2"));
        StubProject project = new StubProject(List.of(a));

        assertEquals(0, MetadataKeyOperations.countCollisions(project, "typo", ""));
        assertEquals(0, MetadataKeyOperations.countCollisions(project, "", "Antibody"));
        assertEquals(0, MetadataKeyOperations.countCollisions(project, "typo", null));
        assertEquals(0, MetadataKeyOperations.countCollisions(project, "typo", "typo"));
    }

    @Test
    void renameRollbackPreservesOriginalIOExceptionAsTheThrown() {
        // Verifies: when syncChanges throws and the revert succeeds, the
        // ORIGINAL IOException is the one thrown (not a wrapping/replacement),
        // and the in-memory state matches the pre-mutation snapshot.
        StubEntry a = new StubEntry("a", Map.of("typo", "v1"));
        StubEntry b = new StubEntry("b", linkedMap("typo", "v2", "Antibody", "kept"));
        StubProject project = new StubProject(List.of(a, b));
        project.failNextSync = true;

        IOException thrown = assertThrows(IOException.class, () ->
                MetadataKeyOperations.renameAcrossProject(
                        project, "typo", "Antibody", CollisionPolicy.OVERWRITE));
        assertEquals("simulated sync failure", thrown.getMessage());
        // No suppressed exceptions when the revert path itself succeeds.
        assertEquals(0, thrown.getSuppressed().length);

        // In-memory state matches the pre-mutation snapshot.
        assertEquals("v1", a.getMetadata().get("typo"));
        assertFalse(a.getMetadata().containsKey("Antibody"));
        assertEquals("v2", b.getMetadata().get("typo"));
        assertEquals("kept", b.getMetadata().get("Antibody"));
        // Initial failing sync + one successful post-revert sync.
        assertEquals(2, project.syncCount);
    }

    @Test
    void emptyProjectIsHandledWithoutSync() throws IOException {
        StubProject project = new StubProject(List.of());

        Result rename = MetadataKeyOperations.renameAcrossProject(
                project, "x", "y", CollisionPolicy.OVERWRITE);
        Result remove = MetadataKeyOperations.removeAcrossProject(project, "x");

        assertEquals(0, rename.mutated());
        assertEquals(0, remove.mutated());
        // Per the design, syncChanges is always called even on a zero-mutation
        // batch so the project file's mtime reflects the user's intent.
        assertEquals(2, project.syncCount);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static Map<String, String> linkedMap(String k1, String v1, String k2, String v2) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(k1, v1);
        m.put(k2, v2);
        return m;
    }

    /**
     * Minimal {@link ProjectImageEntry} that just exposes a mutable metadata
     * map -- mirrors the StubEntry in model tests, kept local so this test
     * file is self-contained.
     */
    static final class StubEntry implements ProjectImageEntry<BufferedImage> {

        private final String id;
        private final String name;
        private final Map<String, String> metadata = new HashMap<>();
        private final Set<String> tags = new HashSet<>();

        StubEntry(String id, Map<String, String> initialMetadata) {
            this.id = id;
            this.name = id;
            if (initialMetadata != null)
                this.metadata.putAll(initialMetadata);
        }

        @Override public String getID() { return id; }
        @Override public String getImageName() { return name; }
        @Override public String getOriginalImageName() { return name; }
        @Override public void setImageName(String name) { /* no-op */ }
        @Override public String getDescription() { return ""; }
        @Override public void setDescription(String description) { /* no-op */ }
        @Override public Map<String, String> getMetadata() { return metadata; }
        @Override public Set<String> getTags() { return tags; }
        @Override public Collection<java.net.URI> getURIs() { return Collections.emptyList(); }
        @Override public boolean updateURIs(Map<java.net.URI, java.net.URI> replacements) { return false; }
        @Override public Path getEntryPath() { return null; }
        @Override public ServerBuilder<BufferedImage> getServerBuilder() { return null; }
        @Override public ImageData<BufferedImage> readImageData() throws IOException {
            throw new UnsupportedOperationException("not needed for tests");
        }
        @Override public void saveImageData(ImageData<BufferedImage> imageData) {
            throw new UnsupportedOperationException("not needed for tests");
        }
        @Override public PathObjectHierarchy readHierarchy() {
            throw new UnsupportedOperationException("not needed for tests");
        }
        @Override public boolean hasImageData() { return false; }
        @Override public String getSummary() { return name; }
        @Override public BufferedImage getThumbnail() { return null; }
        @Override public void setThumbnail(BufferedImage img) { /* no-op */ }
        @Override public Manager<ImageServer<BufferedImage>> getImages() { return null; }
    }

    /**
     * Minimal {@link Project} fake that returns the supplied entries and
     * counts syncChanges() calls. Setting {@code failNextSync = true} makes
     * the next sync throw an {@link IOException}.
     */
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

        // Everything below is unused by the rename/remove paths.
        @Override public ProjectImageEntry<BufferedImage> addImage(ServerBuilder<BufferedImage> server) {
            throw new UnsupportedOperationException("not needed for tests");
        }
        @Override public ProjectImageEntry<BufferedImage> addDuplicate(ProjectImageEntry<BufferedImage> entry, boolean copyData) {
            throw new UnsupportedOperationException("not needed for tests");
        }
        @Override public ProjectImageEntry<BufferedImage> getEntry(ImageData<BufferedImage> imageData) {
            return null;
        }
        @Override public void removeImage(ProjectImageEntry<?> entry, boolean removeAllData) { /* no-op */ }
        @Override public void removeAllImages(Collection<ProjectImageEntry<BufferedImage>> entries, boolean removeAllData) { /* no-op */ }
        @Override public String getName() { return "stub"; }
        @Override public Path getPath() { return null; }
        @Override public java.net.URI getURI() { return null; }
        @Override public java.net.URI getPreviousURI() { return null; }
        @Override public List<PathClass> getPathClasses() { return Collections.emptyList(); }
        @Override public boolean setPathClasses(Collection<? extends PathClass> pathClasses) { return false; }
        @Override public boolean getMaskImageNames() { return false; }
        @Override public void setMaskImageNames(boolean mask) { /* no-op */ }
        @Override public Project<BufferedImage> createSubProject(String name, Collection<ProjectImageEntry<BufferedImage>> entries) {
            throw new UnsupportedOperationException("not needed for tests");
        }
        @Override public long getCreationTimestamp() { return 0L; }
        @Override public long getModificationTimestamp() { return 0L; }
        @Override public String getVersion() { return null; }
        @Override public boolean isEmpty() { return entries.isEmpty(); }
        @Override public Map<String, String> getMetadata() { return new HashMap<>(); }
    }
}
