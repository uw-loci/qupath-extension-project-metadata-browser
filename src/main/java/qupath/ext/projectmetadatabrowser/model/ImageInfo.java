package qupath.ext.projectmetadatabrowser.model;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.lib.images.servers.ImageChannel;
import qupath.lib.images.servers.ImageServerBuilder.ServerBuilder;
import qupath.lib.images.servers.ImageServerMetadata;
import qupath.lib.images.servers.PixelCalibration;
import qupath.lib.projects.ProjectImageEntry;

/**
 * The image-level facts a project entry carries in its cached server metadata:
 * dimensions, pixel size, channels and magnification. Read from
 * {@link ServerBuilder#getMetadata()}, which QuPath stores in the project
 * file, so no image is opened. Immutable; {@link #EMPTY} when the entry has no
 * cached metadata (an image that was never opened after being added).
 */
public final class ImageInfo {

    private static final Logger logger = LoggerFactory.getLogger(ImageInfo.class);

    public static final ImageInfo EMPTY = new ImageInfo(-1, -1, Double.NaN, Double.NaN,
            Collections.emptyList(), Double.NaN, -1, -1);

    private final int width;
    private final int height;
    private final double pixelWidthMicrons;
    private final double pixelHeightMicrons;
    private final List<String> channelNames;
    private final double magnification;
    private final int sizeZ;
    private final int sizeT;

    ImageInfo(int width, int height, double pixelWidthMicrons, double pixelHeightMicrons,
              List<String> channelNames, double magnification, int sizeZ, int sizeT) {
        this.width = width;
        this.height = height;
        this.pixelWidthMicrons = pixelWidthMicrons;
        this.pixelHeightMicrons = pixelHeightMicrons;
        this.channelNames = List.copyOf(channelNames);
        this.magnification = magnification;
        this.sizeZ = sizeZ;
        this.sizeT = sizeT;
    }

    /** Info for a project entry; {@link #EMPTY} if it carries no cached server metadata. */
    public static ImageInfo from(ProjectImageEntry<BufferedImage> entry) {
        if (entry == null)
            return EMPTY;
        try {
            ServerBuilder<BufferedImage> builder = entry.getServerBuilder();
            if (builder == null)
                return EMPTY;
            Optional<ImageServerMetadata> md = builder.getMetadata();
            return md.map(ImageInfo::from).orElse(EMPTY);
        } catch (Exception e) {
            logger.debug("No server metadata for entry {}: {}", entry.getID(), e.getMessage());
            return EMPTY;
        }
    }

    /** Info from server metadata. */
    public static ImageInfo from(ImageServerMetadata md) {
        if (md == null)
            return EMPTY;
        PixelCalibration cal = md.getPixelCalibration();
        double pw = Double.NaN;
        double ph = Double.NaN;
        if (cal != null && cal.hasPixelSizeMicrons()) {
            pw = cal.getPixelWidthMicrons();
            ph = cal.getPixelHeightMicrons();
        }
        List<String> names = new ArrayList<>();
        for (ImageChannel c : md.getChannels()) {
            names.add(c == null || c.getName() == null ? "" : c.getName());
        }
        return new ImageInfo(md.getWidth(), md.getHeight(), pw, ph, names,
                md.getMagnification(), md.getSizeZ(), md.getSizeT());
    }

    public boolean isEmpty() {
        return width < 0;
    }

    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public double getPixelWidthMicrons() { return pixelWidthMicrons; }
    public double getPixelHeightMicrons() { return pixelHeightMicrons; }
    public List<String> getChannelNames() { return channelNames; }
    public double getMagnification() { return magnification; }
    public int getSizeZ() { return sizeZ; }
    public int getSizeT() { return sizeT; }

    public boolean hasPixelSize() {
        return !Double.isNaN(pixelWidthMicrons) && pixelWidthMicrons > 0;
    }

    /** {@code "0.2500 um"}, {@code "0.2500 x 0.5000 um"} if anisotropic, {@code "uncalibrated"}, or blank. */
    public String getPixelSizeText() {
        if (isEmpty())
            return "";
        if (!hasPixelSize())
            return "uncalibrated";
        if (Math.abs(pixelWidthMicrons - pixelHeightMicrons) > 1e-6 * pixelWidthMicrons)
            return trimNumber(pixelWidthMicrons) + " x " + trimNumber(pixelHeightMicrons) + " um";
        return trimNumber(pixelWidthMicrons) + " um";
    }

    /** {@code "W x H"} in pixels, with {@code " x Z z"} / {@code " x T t"} appended when > 1; blank if unknown. */
    public String getSizeText() {
        if (isEmpty())
            return "";
        StringBuilder sb = new StringBuilder();
        sb.append(width).append(" x ").append(height);
        if (sizeZ > 1)
            sb.append(" x ").append(sizeZ).append(" z");
        if (sizeT > 1)
            sb.append(" x ").append(sizeT).append(" t");
        return sb.toString();
    }

    /** Channel count as text; blank if unknown. */
    public String getChannelCountText() {
        return isEmpty() ? "" : Integer.toString(channelNames.size());
    }

    /** Channel names, one per line, for a tooltip; blank if unknown. */
    public String getChannelNamesText() {
        if (isEmpty() || channelNames.isEmpty())
            return "";
        return String.join("\n", channelNames);
    }

    /** {@code "20x"}, or blank if unknown. */
    public String getMagnificationText() {
        if (isEmpty() || Double.isNaN(magnification) || magnification <= 0)
            return "";
        return trimNumber(magnification) + "x";
    }

    /**
     * Comparator for the text columns above: compares the leading number of
     * each value numerically (so {@code 0.25 um} sorts before {@code 10 um}
     * and {@code 2048 x 2048} after {@code 512 x 512}), with blank and
     * non-numeric values last.
     */
    public static Comparator<String> leadingNumberComparator() {
        return (a, b) -> {
            double da = leadingNumber(a);
            double db = leadingNumber(b);
            boolean na = Double.isNaN(da);
            boolean nb = Double.isNaN(db);
            if (na && nb)
                return String.CASE_INSENSITIVE_ORDER.compare(nullSafe(a), nullSafe(b));
            if (na)
                return 1;
            if (nb)
                return -1;
            int c = Double.compare(da, db);
            if (c != 0)
                return c;
            return String.CASE_INSENSITIVE_ORDER.compare(nullSafe(a), nullSafe(b));
        };
    }

    static double leadingNumber(String s) {
        if (s == null)
            return Double.NaN;
        String t = s.trim();
        int end = 0;
        while (end < t.length()) {
            char ch = t.charAt(end);
            if (Character.isDigit(ch) || ch == '.' || (end == 0 && ch == '-'))
                end++;
            else
                break;
        }
        if (end == 0)
            return Double.NaN;
        try {
            return Double.parseDouble(t.substring(0, end));
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    static String trimNumber(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e9)
            return Long.toString((long) v);
        String s = String.format(Locale.ROOT, "%.4f", v);
        // Drop trailing zeros but keep at least one decimal digit.
        int i = s.length();
        while (i > 0 && s.charAt(i - 1) == '0')
            i--;
        if (i > 0 && s.charAt(i - 1) == '.')
            i++;
        return s.substring(0, Math.min(i, s.length()));
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
