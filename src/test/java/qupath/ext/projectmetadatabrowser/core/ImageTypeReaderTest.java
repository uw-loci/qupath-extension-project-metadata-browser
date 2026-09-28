package qupath.ext.projectmetadatabrowser.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import qupath.lib.images.ImageData;
import qupath.lib.images.ImageData.ImageType;
import qupath.lib.images.servers.WrappedBufferedImageServer;
import qupath.lib.io.PathIO;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.roi.ROIs;

/**
 * The partial reader must recover the image type QuPath itself wrote, for
 * each type, from a real data file that also carries a hierarchy.
 */
class ImageTypeReaderTest {

    @TempDir
    Path tmp;

    private Path writeDataFile(ImageType type, int nObjects) throws IOException {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
        var server = new WrappedBufferedImageServer("test", img);
        PathObjectHierarchy hierarchy = new PathObjectHierarchy();
        for (int i = 0; i < nObjects; i++) {
            hierarchy.addObject(PathObjects.createAnnotationObject(
                    ROIs.createRectangleROI(i, i, 2, 2, null)));
        }
        ImageData<BufferedImage> data = new ImageData<>(server, hierarchy, type);
        Path file = tmp.resolve("data-" + type.name() + ".qpdata");
        PathIO.writeImageData(file.toFile(), data);
        return file;
    }

    @Test
    void readsEveryTypeQuPathWrites() throws IOException {
        for (ImageType type : ImageType.values()) {
            Path file = writeDataFile(type, 3);
            assertEquals(type, ImageTypeReader.read(file), "type " + type);
        }
    }

    @Test
    void readsTypeFromFileWithManyObjects() throws IOException {
        Path file = writeDataFile(ImageType.FLUORESCENCE, 500);
        assertEquals(ImageType.FLUORESCENCE, ImageTypeReader.read(file));
    }

    @Test
    void rejectsNonQuPathFile() throws IOException {
        Path file = tmp.resolve("not-qupath.qpdata");
        Files.writeString(file, "definitely not a data file", StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> ImageTypeReader.read(file));
    }
}
