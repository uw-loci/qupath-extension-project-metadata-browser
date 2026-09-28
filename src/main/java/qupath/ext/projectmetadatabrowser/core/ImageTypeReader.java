package qupath.ext.projectmetadatabrowser.core;

import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.lib.images.ImageData;
import qupath.lib.images.ImageData.ImageType;
import qupath.lib.io.PathIO;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.projects.ProjectImageEntry;

/**
 * Reads just the {@link ImageType} out of a project entry's {@code data.qpdata}
 * without deserializing the object hierarchy.
 *
 * <p>QuPath writes the data file as a sequential object stream (see
 * {@link PathIO#writeImageData}): a version header, the server-builder JSON,
 * the locale, the image type, colour-deconvolution stains, the workflow and
 * only then the hierarchy. Stopping as soon as the image type arrives costs a
 * few hundred bytes per entry instead of the whole hierarchy, which is what
 * makes an Image Type column affordable on a large project.
 */
public final class ImageTypeReader {

    private static final Logger logger = LoggerFactory.getLogger(ImageTypeReader.class);

    private static final String DATA_FILE_NAME = "data.qpdata";

    private ImageTypeReader() {}

    /**
     * Image type recorded for a project entry.
     *
     * @param entry the project entry.
     * @return the stored type; {@link ImageType#UNSET} when the entry has no
     *         data file yet; {@code null} when the file exists but could not
     *         be read.
     */
    public static ImageType readForEntry(ProjectImageEntry<BufferedImage> entry) {
        if (entry == null || !entry.hasImageData())
            return ImageType.UNSET;
        Path dir = entry.getEntryPath();
        if (dir == null)
            return ImageType.UNSET;
        Path file = dir.resolve(DATA_FILE_NAME);
        if (!Files.exists(file))
            return ImageType.UNSET;
        try {
            return read(file);
        } catch (IOException e) {
            logger.warn("Could not read image type for entry {}: {}", entry.getID(), e.getMessage());
            return null;
        }
    }

    /**
     * Image type stored in a {@code .qpdata} file.
     *
     * @param file the data file.
     * @return the stored type, or {@link ImageType#UNSET} if the stream ends
     *         before one is found (very old data files).
     * @throws IOException if the file is not a QuPath data file or cannot be read.
     */
    public static ImageType read(Path file) throws IOException {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file));
             ObjectInputStream stream = PathIO.createObjectInputStream(in)) {
            String header = stream.readUTF();
            if (header == null || !header.startsWith("Data file version"))
                throw new IOException("Not a QuPath data file: " + file);
            // Server-builder JSON; not needed here.
            stream.readObject();
            while (true) {
                Object next;
                try {
                    next = stream.readObject();
                } catch (EOFException e) {
                    return ImageType.UNSET;
                } catch (ClassNotFoundException e) {
                    logger.debug("Skipping unknown class in {}: {}", file, e.getMessage());
                    continue;
                }
                if (next instanceof ImageData.ImageType type)
                    return type;
                if ("EOF".equals(next))
                    return ImageType.UNSET;
                if (next instanceof PathObjectHierarchy)
                    logger.debug("Hierarchy precedes image type in {}; partial read gave no saving", file);
            }
        } catch (ClassNotFoundException e) {
            throw new IOException("Unreadable QuPath data file: " + file, e);
        }
    }
}
