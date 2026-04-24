package qupath.ext.projectmetadatabrowser.model;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServerBuilder.ServerBuilder;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.projects.ResourceManager.Manager;

/**
 * Minimal {@link ProjectImageEntry} implementation for unit tests -- only the
 * methods the metadata browser actually calls need real behaviour; everything
 * else throws so a misuse is loud.
 */
final class StubEntry implements ProjectImageEntry<BufferedImage> {

    private final String id;
    private String name;
    private String description = "";
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
    @Override public void setImageName(String name) { this.name = name; }
    @Override public String getDescription() { return description; }
    @Override public void setDescription(String description) { this.description = description; }
    @Override public Map<String, String> getMetadata() { return metadata; }
    @Override public Set<String> getTags() { return tags; }

    @Override public Collection<URI> getURIs() { return Collections.emptyList(); }
    @Override public boolean updateURIs(Map<URI, URI> replacements) { return false; }

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
