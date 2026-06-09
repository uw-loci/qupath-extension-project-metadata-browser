package qupath.ext.projectmetadatabrowser.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
import org.junit.jupiter.api.io.TempDir;

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

class ImportPreviewModelTest {

    @Test
    void detectsCommaSeparator(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("data.csv");
        Files.writeString(f, "Image ID,Condition\nA,Tumor\nB,Control\n", StandardCharsets.UTF_8);
        ImportPreviewModel m = ImportPreviewModel.read(f, null);
        assertEquals(',', m.getSeparator());
        assertEquals(List.of("Image ID", "Condition"), m.getHeaders());
        assertEquals(2, m.dataRowCount());
    }

    @Test
    void detectsTabSeparator(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("data.tsv");
        Files.writeString(f, "Image ID\tCondition\nA\tTumor\nB\tControl\n", StandardCharsets.UTF_8);
        ImportPreviewModel m = ImportPreviewModel.read(f, null);
        assertEquals('\t', m.getSeparator());
    }

    @Test
    void detectsSemicolonSeparator(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("data.csv");
        Files.writeString(f, "Image ID;Condition\nA;Tumor\nB;Control\n", StandardCharsets.UTF_8);
        ImportPreviewModel m = ImportPreviewModel.read(f, null);
        assertEquals(';', m.getSeparator());
    }

    @Test
    void computePreviewCategorizesAddUpdateUnchangedAndNoMatch(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("data.csv");
        // Headers: Image ID, Condition, NewCol
        // a has Condition=existing; matches -> ADD-or-UPDATE depending on values
        // b has Condition=new value -> UPDATE
        // c not in project -> NO_MATCH
        Files.writeString(f,
                "Image ID,Condition,NewCol\n"
                        + "a,existing,nv\n"
                        + "b,changed,nv\n"
                        + "c,whatever,nv\n", StandardCharsets.UTF_8);
        ImportPreviewModel m = ImportPreviewModel.read(f, null);

        StubEntry a = new StubEntry("a", linkedMap("Condition", "existing"));
        StubEntry b = new StubEntry("b", linkedMap("Condition", "before"));
        StubProject project = new StubProject(List.of(a, b));
        WorkingCopy wc = new WorkingCopy();
        wc.loadFrom(project);

        m.computePreview(wc, "Image ID");
        // a: NewCol added (was absent), Condition unchanged -> ADD
        // b: NewCol added + Condition changed -> UPDATE
        // c: NO_MATCH
        ImportPreviewModel.Counts c = m.getCounts();
        assertEquals(1, c.update());
        assertEquals(1, c.add());
        assertEquals(1, c.noMatch());
        assertTrue(m.getNewColumns().contains("NewCol"));
        assertTrue(m.getPendingDeltas().size() > 0);
    }

    private static Map<String, String> linkedMap(String k, String v) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(k, v);
        return m;
    }

    static final class StubEntry implements ProjectImageEntry<BufferedImage> {
        private final String id;
        private final Map<String, String> metadata = new LinkedHashMap<>();
        StubEntry(String id, Map<String, String> initial) { this.id = id; metadata.putAll(initial); }
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
        StubProject(List<StubEntry> entries) { this.entries = new ArrayList<>(entries); }
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
