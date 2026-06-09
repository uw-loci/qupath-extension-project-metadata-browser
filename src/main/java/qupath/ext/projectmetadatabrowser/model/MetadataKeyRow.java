package qupath.ext.projectmetadatabrowser.model;

import java.util.Objects;

/**
 * One row in the Metadata Keys tab -- a pure data carrier describing a
 * distinct user-metadata key seen across the project, along with how many
 * image entries currently set it and a sample value (with the name of the
 * entry the sample came from).
 *
 * <p>Snapshot view -- no JavaFX properties; cell value factories pull the
 * fields directly. The row is immutable; a new list is rebuilt by
 * {@code MetadataModel.loadFrom} on every reload.
 */
public final class MetadataKeyRow {

    private final String key;
    private final int entryCount;
    private final String sampleValue;
    private final String sampleEntryName;

    /**
     * @param key the metadata key string. Must not be null.
     * @param entryCount the number of image entries in the project that
     *                   currently have {@code key} set.
     * @param sampleValue the value of {@code key} on the first entry (in
     *                    {@code project.getImageList()} order) that has it.
     *                    Empty string if no sample is available.
     * @param sampleEntryName the name of the entry the sample came from.
     *                        Empty string if no sample is available.
     */
    public MetadataKeyRow(String key, int entryCount, String sampleValue, String sampleEntryName) {
        this.key = Objects.requireNonNull(key, "key");
        this.entryCount = entryCount;
        this.sampleValue = sampleValue == null ? "" : sampleValue;
        this.sampleEntryName = sampleEntryName == null ? "" : sampleEntryName;
    }

    public String getKey() {
        return key;
    }

    public int getEntryCount() {
        return entryCount;
    }

    public String getSampleValue() {
        return sampleValue;
    }

    public String getSampleEntryName() {
        return sampleEntryName;
    }

    /**
     * Display string for the "Used by" column. "1 entry" for a single entry,
     * otherwise "N entries". ASCII only.
     */
    public String getEntryCountDisplay() {
        return entryCount == 1 ? "1 entry" : entryCount + " entries";
    }
}
