package qupath.ext.projectmetadatabrowser.core;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.ext.projectmetadatabrowser.model.WorkingCopy;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

/**
 * Project-wide rename and remove operations for a single user-metadata key.
 *
 * <p>Both operations follow the same protocol:
 * <ol>
 *   <li>Build a pre-mutation snapshot of every touched entry's metadata map.</li>
 *   <li>For each entry in {@code project.getImageList()}, apply the rename or
 *       remove while holding the per-entry {@code synchronized(md)} guard --
 *       mirrors {@code EntryRow.applyMetadataChanges} (EntryRow.java:108-122).</li>
 *   <li>Call {@code project.syncChanges()} once at the end of the batch.</li>
 *   <li>If {@code syncChanges()} throws, revert every touched entry from the
 *       snapshot, then re-throw the {@link IOException}.</li>
 * </ol>
 *
 * <p>This is the only place in the extension that mutates metadata across
 * the whole project. The tab controller and any future scripting API both
 * call the same static methods.
 */
public final class MetadataKeyOperations {

    private static final Logger logger = LoggerFactory.getLogger(MetadataKeyOperations.class);

    private MetadataKeyOperations() {
        // utility class -- no instances
    }

    /**
     * Collision policy for {@link #renameAcrossProject}. When the new key
     * already exists on an entry that also has the old key, the policy
     * decides which value survives.
     */
    public enum CollisionPolicy {
        /**
         * Replace the existing value of the new key with the value from the
         * old key. The old key is removed.
         */
        OVERWRITE,
        /**
         * Keep the existing value of the new key untouched. The old key is
         * still removed from that entry.
         */
        SKIP
    }

    /**
     * Result of a {@link #renameAcrossProject} or {@link #removeAcrossProject}
     * call. Reports how many entries were mutated and which ones failed.
     *
     * @param mutated the number of entries that had at least one metadata
     *                map change applied (a rename that moved old -> new, or a
     *                remove of the old key).
     * @param failedEntryIds entries that could not be mutated for any reason
     *                       (e.g. a {@code null} metadata map). Empty in the
     *                       happy path. Not the same as a rollback -- a
     *                       failed sync triggers an {@link IOException}
     *                       instead.
     */
    public record Result(int mutated, List<String> failedEntryIds) {
        public Result {
            // Defensive copy so the caller cannot mutate our state.
            failedEntryIds = List.copyOf(failedEntryIds);
        }
    }

    /**
     * Rename {@code oldKey} to {@code newKey} on every entry in the project
     * that has {@code oldKey} set. Calls {@code project.syncChanges()} once
     * at the end. On {@link IOException} from sync, reverts every touched
     * entry from the pre-mutation snapshot and re-throws.
     *
     * <p>Behaviour:
     * <ul>
     *   <li>If an entry has {@code oldKey} but not {@code newKey}: the value
     *       is moved (old removed, new set to old's value). Policy is moot.</li>
     *   <li>If an entry has both keys and policy is {@link CollisionPolicy#OVERWRITE}:
     *       {@code newKey} is overwritten with the value of {@code oldKey},
     *       then {@code oldKey} is removed.</li>
     *   <li>If an entry has both keys and policy is {@link CollisionPolicy#SKIP}:
     *       {@code newKey} keeps its existing value; {@code oldKey} is
     *       removed.</li>
     *   <li>If an entry has only {@code newKey} or neither: untouched.</li>
     * </ul>
     *
     * @param project the project to mutate. Must not be null.
     * @param oldKey the existing key to rename. Must not be null or blank.
     * @param newKey the new key name. Must not be null or blank; must not
     *               equal {@code oldKey} (caller's responsibility -- this
     *               method does not validate the new key's format).
     * @param policy how to resolve collisions where both keys exist on the
     *               same entry. Must not be null.
     * @return a {@link Result} summarising how many entries were mutated.
     * @throws IOException if {@code project.syncChanges()} fails; in-memory
     *                     changes are reverted before the throw.
     */
    public static Result renameAcrossProject(Project<BufferedImage> project,
                                              String oldKey,
                                              String newKey,
                                              CollisionPolicy policy) throws IOException {
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(oldKey, "oldKey");
        Objects.requireNonNull(newKey, "newKey");
        Objects.requireNonNull(policy, "policy");
        if (oldKey.isBlank())
            throw new IllegalArgumentException("oldKey must not be blank");
        if (newKey.isBlank())
            throw new IllegalArgumentException("newKey must not be blank");

        Map<String, Map<String, String>> snapshots = new HashMap<>();
        List<ProjectImageEntry<BufferedImage>> touched = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        int mutated = 0;

        for (ProjectImageEntry<BufferedImage> entry : project.getImageList()) {
            Map<String, String> md = entry.getMetadata();
            if (md == null) {
                failed.add(entry.getID());
                continue;
            }
            synchronized (md) {
                if (!md.containsKey(oldKey))
                    continue;
                // Capture snapshot BEFORE mutation so revert restores exactly
                // this entry's pre-mutation state.
                snapshots.put(entry.getID(), new HashMap<>(md));
                touched.add(entry);
                String oldValue = md.get(oldKey);
                boolean hasNew = md.containsKey(newKey);
                if (hasNew) {
                    if (policy == CollisionPolicy.OVERWRITE) {
                        md.put(newKey, oldValue);
                    }
                    // SKIP: keep existing newKey value; do not touch it.
                    md.remove(oldKey);
                } else {
                    md.put(newKey, oldValue);
                    md.remove(oldKey);
                }
                mutated++;
            }
        }

        try {
            project.syncChanges();
        } catch (IOException e) {
            logger.error("syncChanges failed during rename of '{}' -> '{}'; rolling back {} entries",
                    oldKey, newKey, touched.size(), e);
            List<Throwable> revertFailures = new ArrayList<>();
            try {
                revertSnapshots(touched, snapshots, revertFailures);
            } finally {
                // Attempt to persist the rolled-back state. If this second sync
                // also fails, attach it as a suppressed exception on the
                // original IOException so the user-visible chain preserves the
                // original cause.
                try {
                    project.syncChanges();
                } catch (IOException syncEx) {
                    logger.error("Second syncChanges() after rollback also failed for rename of '{}' -> '{}'",
                            oldKey, newKey, syncEx);
                    e.addSuppressed(syncEx);
                }
                for (Throwable revertEx : revertFailures)
                    e.addSuppressed(revertEx);
            }
            throw e;
        }
        return new Result(mutated, failed);
    }

    /**
     * Remove {@code key} from every entry in the project that has it. Calls
     * {@code project.syncChanges()} once at the end. On {@link IOException}
     * from sync, reverts every touched entry from the pre-mutation snapshot
     * and re-throws.
     *
     * @param project the project to mutate. Must not be null.
     * @param key the key to remove. Must not be null or blank.
     * @return a {@link Result} summarising how many entries were mutated.
     * @throws IOException if {@code project.syncChanges()} fails; in-memory
     *                     changes are reverted before the throw.
     */
    public static Result removeAcrossProject(Project<BufferedImage> project,
                                              String key) throws IOException {
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(key, "key");
        if (key.isBlank())
            throw new IllegalArgumentException("key must not be blank");

        Map<String, Map<String, String>> snapshots = new HashMap<>();
        List<ProjectImageEntry<BufferedImage>> touched = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        int mutated = 0;

        for (ProjectImageEntry<BufferedImage> entry : project.getImageList()) {
            Map<String, String> md = entry.getMetadata();
            if (md == null) {
                failed.add(entry.getID());
                continue;
            }
            synchronized (md) {
                if (!md.containsKey(key))
                    continue;
                snapshots.put(entry.getID(), new HashMap<>(md));
                touched.add(entry);
                md.remove(key);
                mutated++;
            }
        }

        try {
            project.syncChanges();
        } catch (IOException e) {
            logger.error("syncChanges failed during remove of '{}'; rolling back {} entries",
                    key, touched.size(), e);
            List<Throwable> revertFailures = new ArrayList<>();
            try {
                revertSnapshots(touched, snapshots, revertFailures);
            } finally {
                // Attempt to persist the rolled-back state. If this second sync
                // also fails, attach it as a suppressed exception on the
                // original IOException so the user-visible chain preserves the
                // original cause.
                try {
                    project.syncChanges();
                } catch (IOException syncEx) {
                    logger.error("Second syncChanges() after rollback also failed for remove of '{}'",
                            key, syncEx);
                    e.addSuppressed(syncEx);
                }
                for (Throwable revertEx : revertFailures)
                    e.addSuppressed(revertEx);
            }
            throw e;
        }
        return new Result(mutated, failed);
    }

    /**
     * Restore each touched entry's metadata map from its pre-mutation
     * snapshot. Each map is rebuilt: cleared, then refilled. Keys that a
     * concurrent script added between mutation and revert are lost -- the
     * snapshot is the source of truth for the entries we touched. Keys we
     * never touched (on entries that were not in {@code touched}) are
     * untouched.
     *
     * <p>Resilience: each entry is reverted inside its own try/catch so a
     * single failing entry does not abort the rest of the revert loop. Any
     * collected failures are logged at ERROR and returned to the caller via
     * the supplied {@code revertFailures} list -- the caller decides whether
     * to surface them as suppressed exceptions on the user-visible throw.
     */
    private static void revertSnapshots(List<ProjectImageEntry<BufferedImage>> touched,
                                         Map<String, Map<String, String>> snapshots,
                                         List<Throwable> revertFailures) {
        Set<String> reverted = new HashSet<>();
        for (ProjectImageEntry<BufferedImage> entry : touched) {
            try {
                Map<String, String> snap = snapshots.get(entry.getID());
                if (snap == null)
                    continue;
                Map<String, String> md = entry.getMetadata();
                if (md == null)
                    continue;
                synchronized (md) {
                    md.clear();
                    md.putAll(snap);
                }
                reverted.add(entry.getID());
            } catch (RuntimeException ex) {
                logger.error("Failed to revert metadata snapshot for entry '{}'",
                        entry.getID(), ex);
                revertFailures.add(ex);
            }
        }
        logger.info("Reverted metadata on {} entries from pre-mutation snapshot", reverted.size());
    }

    /**
     * Count how many entries in {@code project} have BOTH {@code oldKey} and
     * {@code newKey} set -- i.e. the entries on which the collision policy
     * would actually decide an outcome. Returns 0 if either key is null or
     * blank (the UI gates on validation before calling).
     *
     * <p>This is a read-only scan and does not call {@code syncChanges()}.
     * Uses the same {@code synchronized(getMetadata())} discipline as the
     * mutating paths.
     *
     * @param project the project to inspect. Must not be null.
     * @param oldKey the source key.
     * @param newKey the prospective new key.
     * @return number of entries with both keys set; 0 if either argument is
     *         null or blank.
     */
    /**
     * Commit a list of per-entry diffs from a buffered-editor working copy.
     * Same protocol as {@link #renameAcrossProject}: per-entry snapshot,
     * synchronized mutation, single {@code project.syncChanges()}, rollback
     * on {@link IOException} with suppressed-exception chaining.
     *
     * <p>This is the only {@code syncChanges()} callsite for normal save
     * operations -- per-image edits, inline cell edits, Excel paste, import,
     * regex extraction, key rename, and key delete all flow through here.
     *
     * @param project the project to mutate. Must not be null.
     * @param diffs per-entry diffs produced by {@code workingCopy.diff()}.
     *              May be empty; an empty list still calls
     *              {@code syncChanges()} so the project file mtime
     *              reflects the user's Save intent.
     * @return a {@link Result} reporting how many entries were mutated.
     * @throws IOException if {@code project.syncChanges()} fails; in-memory
     *                     changes are reverted before the throw.
     */
    public static Result commitWorkingCopy(Project<BufferedImage> project,
                                            List<WorkingCopy.EntryDiff> diffs) throws IOException {
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(diffs, "diffs");

        Map<String, ProjectImageEntry<BufferedImage>> byId = new HashMap<>();
        for (ProjectImageEntry<BufferedImage> entry : project.getImageList())
            byId.put(entry.getID(), entry);

        Map<String, Map<String, String>> snapshots = new HashMap<>();
        List<ProjectImageEntry<BufferedImage>> touched = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        int mutated = 0;

        for (WorkingCopy.EntryDiff diff : diffs) {
            ProjectImageEntry<BufferedImage> entry = byId.get(diff.entryId());
            if (entry == null) {
                failed.add(diff.entryId());
                continue;
            }
            Map<String, String> md = entry.getMetadata();
            if (md == null) {
                failed.add(diff.entryId());
                continue;
            }
            synchronized (md) {
                snapshots.put(entry.getID(), new HashMap<>(md));
                touched.add(entry);
                for (Map.Entry<String, String> e : diff.toSet().entrySet()) {
                    if (e.getKey() == null)
                        continue;
                    String value = e.getValue();
                    if (value == null || value.isEmpty())
                        md.remove(e.getKey());
                    else
                        md.put(e.getKey(), value);
                }
                for (String key : diff.toRemove()) {
                    if (key != null)
                        md.remove(key);
                }
                mutated++;
            }
        }

        try {
            project.syncChanges();
        } catch (IOException e) {
            logger.error("syncChanges failed during working-copy commit; rolling back {} entries",
                    touched.size(), e);
            List<Throwable> revertFailures = new ArrayList<>();
            try {
                revertSnapshots(touched, snapshots, revertFailures);
            } finally {
                try {
                    project.syncChanges();
                } catch (IOException syncEx) {
                    logger.error("Second syncChanges() after rollback also failed for working-copy commit",
                            syncEx);
                    e.addSuppressed(syncEx);
                }
                for (Throwable revertEx : revertFailures)
                    e.addSuppressed(revertEx);
            }
            throw e;
        }
        return new Result(mutated, failed);
    }

    public static int countCollisions(Project<BufferedImage> project,
                                       String oldKey,
                                       String newKey) {
        Objects.requireNonNull(project, "project");
        if (oldKey == null || oldKey.isBlank() || newKey == null || newKey.isBlank())
            return 0;
        if (oldKey.equals(newKey))
            return 0;
        int count = 0;
        for (ProjectImageEntry<BufferedImage> entry : project.getImageList()) {
            Map<String, String> md = entry.getMetadata();
            if (md == null)
                continue;
            synchronized (md) {
                if (md.containsKey(oldKey) && md.containsKey(newKey))
                    count++;
            }
        }
        return count;
    }
}
