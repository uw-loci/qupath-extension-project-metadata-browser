package qupath.ext.projectmetadatabrowser.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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

class RegexPreviewModelTest {

    @Test
    void parseNamedGroupsReturnsLeftToRight() {
        List<String> groups = RegexPreviewModel.parseNamedGroups(
                "(?<patient>P\\d+)_(?<tp>T\\d+)_(?<stain>HE|CD3).*");
        assertEquals(List.of("patient", "tp", "stain"), groups);
    }

    @Test
    void invalidPatternProducesErrorAndEmptyPreview() {
        RegexPreviewModel m = new RegexPreviewModel();
        m.update("(?<bad", List.of(), MutableEntryRow::getName);
        assertNotNull(m.getCompileError());
        assertNull(m.getCompiled());
    }

    @Test
    void matchesAndCapturesPerRowGroups() {
        StubEntry a = new StubEntry("a", "P12_T03_HE.tiff");
        StubEntry b = new StubEntry("b", "P13_T01_CD3.tiff");
        StubEntry c = new StubEntry("c", "qc_calibration.svs");
        StubProject project = new StubProject(List.of(a, b, c));
        WorkingCopy wc = new WorkingCopy();
        wc.loadFrom(project);

        RegexPreviewModel m = new RegexPreviewModel();
        m.update("(?<patient>P\\d+)_(?<tp>T\\d+)_(?<stain>HE|CD3).*",
                wc.getRows(), MutableEntryRow::getName);
        assertEquals(2, m.getTotalMatched());
        assertEquals(1, m.getTotalUnmatched());
        List<String> groups = m.getGroupNames();
        assertEquals(List.of("patient", "tp", "stain"), groups);
        assertTrue(m.getPreviewRows().size() == 3);
        RegexPreviewModel.PreviewRow rowA = m.getPreviewRows().get(0);
        assertEquals("P12", rowA.groupValues().get("patient"));
        assertEquals("T03", rowA.groupValues().get("tp"));
        assertEquals("HE", rowA.groupValues().get("stain"));
    }

    static final class StubEntry implements ProjectImageEntry<BufferedImage> {
        private final String id;
        private final String name;
        StubEntry(String id, String name) { this.id = id; this.name = name; }
        @Override public String getID() { return id; }
        @Override public String getImageName() { return name; }
        @Override public String getOriginalImageName() { return name; }
        @Override public void setImageName(String n) { }
        @Override public String getDescription() { return ""; }
        @Override public void setDescription(String description) { }
        @Override public Map<String, String> getMetadata() { return new LinkedHashMap<>(); }
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
