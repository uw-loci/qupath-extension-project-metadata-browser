package qupath.ext.projectmetadatabrowser.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.Test;

import qupath.lib.images.servers.ImageChannel;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServerMetadata;
import qupath.lib.images.servers.PixelType;

class ImageInfoTest {

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ImageServerMetadata.Builder builder(int w, int h) {
        return new ImageServerMetadata.Builder((Class<? extends ImageServer<?>>) (Class) ImageServer.class,
                "test", w, h);
    }

    @Test
    void calibratedRgbBrightfield() {
        ImageServerMetadata md = builder(20000, 15000)
                .pixelSizeMicrons(0.25, 0.25)
                .magnification(40)
                .rgb(true)
                .pixelType(PixelType.UINT8)
                .channels(ImageChannel.getDefaultRGBChannels())
                .build();
        ImageInfo info = ImageInfo.from(md);
        assertEquals("0.25 um", info.getPixelSizeText());
        assertEquals("20000 x 15000", info.getSizeText());
        assertEquals("3", info.getChannelCountText());
        assertEquals("40x", info.getMagnificationText());
        assertTrue(info.getChannelNamesText().contains("Red"));
    }

    @Test
    void uncalibratedMultichannelStack() {
        List<ImageChannel> channels = new ArrayList<>();
        channels.add(ImageChannel.getInstance("DAPI", 0xFF0000FF));
        channels.add(ImageChannel.getInstance("CD3", 0xFF00FF00));
        ImageServerMetadata md = builder(512, 512)
                .sizeZ(7)
                .pixelType(PixelType.UINT16)
                .channels(channels)
                .build();
        ImageInfo info = ImageInfo.from(md);
        assertEquals("uncalibrated", info.getPixelSizeText());
        assertEquals("512 x 512 x 7 z", info.getSizeText());
        assertEquals("2", info.getChannelCountText());
        assertEquals("DAPI\nCD3", info.getChannelNamesText());
        assertEquals("", info.getMagnificationText());
    }

    @Test
    void anisotropicPixelSize() {
        ImageServerMetadata md = builder(10, 10).pixelSizeMicrons(0.5, 1.0).build();
        assertEquals("0.5 x 1 um", ImageInfo.from(md).getPixelSizeText());
    }

    @Test
    void emptyInfoIsBlankEverywhere() {
        ImageInfo info = ImageInfo.from((ImageServerMetadata) null);
        assertTrue(info.isEmpty());
        assertEquals("", info.getPixelSizeText());
        assertEquals("", info.getSizeText());
        assertEquals("", info.getChannelCountText());
        assertEquals("", info.getMagnificationText());
    }

    @Test
    void leadingNumberComparatorSortsNumericallyWithBlanksLast() {
        Comparator<String> c = ImageInfo.leadingNumberComparator();
        List<String> values = new ArrayList<>(List.of("10 um", "", "0.25 um", "uncalibrated", "2 um"));
        values.sort(c);
        assertEquals(List.of("0.25 um", "2 um", "10 um", "", "uncalibrated"), values);
        assertTrue(c.compare("512 x 512", "2048 x 2048") < 0);
        assertTrue(c.compare("20x", "4x") > 0);
    }

    @Test
    void trimNumberDropsTrailingZeros() {
        assertEquals("0.25", ImageInfo.trimNumber(0.25));
        assertEquals("0.3245", ImageInfo.trimNumber(0.32451));
        assertEquals("20", ImageInfo.trimNumber(20.0));
        assertEquals("0.5", ImageInfo.trimNumber(0.5));
    }
}
