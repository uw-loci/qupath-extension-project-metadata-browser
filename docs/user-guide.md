# Project Metadata Browser — User Guide

This guide covers the Metadata Keys tab added in v0.2.0. Other browser
features (entries table, filter, export) are not yet user-guided here; see
the README for a quick reference.

## Metadata Keys tab

<details open>
<summary><strong>What this tab does</strong></summary>

The Metadata Keys tab lists every distinct user-metadata key found anywhere
in the open project, alongside the number of image entries that currently
use each key. From this tab you can **rename** a key — copying its value to
a new key name on every entry that has it — or **remove** a key from every
entry that has it. Both operations touch the whole project at once and run
under a single `project.syncChanges()`; both are irreversible.

This tab implements a feature request from sebg on the QuPath community
forum, generalizing Pete Bankhead's per-project rename script and adding a
matching removal operation with usage counts and a no-undo confirmation
gate. Forum thread:
[<FORUM_THREAD_URL>](<FORUM_THREAD_URL>).

<!-- TODO: backfill <FORUM_THREAD_URL> at release time; see
     metadata-keys-tab/02b_design_docs.md Blocking questions. -->

Screenshot: `images/metadata-keys-tab-overview.png` (to be captured during
WSL smoke in Phase 6).

</details>

<details>
<summary><strong>Before you start (recommended)</strong></summary>

- Back up the project (or have it under version control). The Metadata
  Keys tab does not snapshot the project file for you.
- Close any other QuPath sessions that might be editing the same project.
  Concurrent edits during a rename or delete can race against the
  in-memory rollback path.
- If you are unsure of a key's usage, click it and check the Used by
  count plus the Sample value before renaming or deleting.
- For audit-conscious workflows, the operation is recorded to QuPath's
  log at INFO level but is not written to a per-project audit trail.
  Note the original key name yourself if you need to undo later.

</details>

<details>
<summary><strong>Renaming a metadata key across the project</strong></summary>

1. Click the **Metadata Keys** tab.
2. Click the row for the key you want to rename, then click **Rename...**
   (or right-click the row and choose **Rename...**).
3. Enter the new key name. The dialog header shows the affected-entry
   count -- for example, *Rename "typo_Antibody" (used by 18 entries)*.
4. If any of those entries **already have** the new key, the dialog asks
   you to choose a collision policy before confirming:
   - **Overwrite** -- the source key's value replaces any existing value
     of the target key.
   - **Skip** -- entries that already have the target key keep their
     existing target value; the source key is removed from them anyway.
   - **Cancel** -- close the dialog without changing anything. This is
     the default radio on open, so pressing Enter immediately on a
     freshly opened dialog does nothing.
5. Click **Rename** to commit. The Rename button is disabled until the
   new key passes validation **and** you have chosen Overwrite or Skip.

Validation rules for the new key:

- Cannot be empty.
- Cannot contain spaces, tabs, or newlines.
- Cannot equal the old key.

If no entry has both keys, the collision policy is moot — the rename is a
straight move. The radios still appear so the policy choice is visible
before commit.

Screenshot: `images/rename-key-dialog.png` (to be captured during WSL smoke
in Phase 6).

</details>

<details>
<summary><strong>Removing a metadata key from the project</strong></summary>

1. Click the **Metadata Keys** tab.
2. Click the row for the key you want to remove, then click **Delete...**
   (or right-click the row and choose **Delete...**).
3. The confirmation dialog reads: **"Delete the metadata key
   \"tags_legacy\"?"** with the body *"This key will be removed from 12
   entries. This cannot be undone. Back up the project first if you are
   unsure."* No checkbox to suppress this prompt (intentional -- see
   *Limitations*).
4. Click **Delete from N entries** to commit. The key disappears from
   every entry in the project. The Delete button is styled red and is
   **not** the default button; pressing Enter on the Delete confirmation
   triggers Cancel (the focused button), which closes the dialog without
   removing any keys.

There is no undo. If you remove a key by accident, your only recovery is
to restore `project.qpproj` from a backup or version control — close
QuPath first, swap the file, reopen.

Screenshot: `images/delete-key-confirm.png` (to be captured during WSL
smoke in Phase 6).

</details>

<details>
<summary><strong>What happens behind the scenes</strong></summary>

Both operations iterate over `project.getImageList()` and mutate each
image entry's metadata map in place (under the same
`synchronized(getMetadata())` guard the per-image edit dialog uses), then
call `project.syncChanges()` **once** at the end of the batch (not once
per entry) so the on-disk `project.qpproj` is updated in a single IO step.

If the sync call fails — for example, the file is read-only — the
in-memory changes are reverted to the pre-mutation snapshot for every
touched entry and an error notification is shown. Both tabs refresh
automatically after a successful mutation so the renamed column appears,
or the removed column disappears, without a manual Refresh (F5). The
status line at the bottom of the window briefly shows
*"Renamed 'oldKey' to 'newKey' across N entries."* (or
*"Removed 'oldKey' from N entries."*) before reverting to the usual
per-tab count text.

</details>

<details>
<summary><strong>Limitations and known issues</strong></summary>

- **No undo.** By design — see *Removing a metadata key* for recovery.
- **No "do not show this again"** option on the no-undo dialog;
  suppression would weaken the warning. May reconsider in v1.1.
- **Single-select only in v0.2.0.** You can rename or remove one key at a
  time. Multi-select Delete is deferred to v1.1.
- **Whitespace in new key names is rejected.** Tab, newline, and embedded
  spaces in metadata keys have caused TSV/CSV export grief in the past;
  v0.2.0 rejects whitespace in renames. The existing per-image
  Edit metadata... dialog still accepts spaces (it trims rather than
  splits). If you need to rename to a key with embedded spaces, file an
  issue and we will relax the validation.
- **Large-project refresh cost not yet measured.** Both tabs refresh
  from scratch after a mutation; on 10k+-entry projects this may briefly
  hang the UI. Report it on the forum if it hurts; we can switch to
  in-place refresh in v1.1.
- **Windows cp1252 round-trip of metadata key strings is not verified**
  for high-bit characters in key names. Report any encoding glitches.
- **macOS not verified.** v0.2.0 was built and tested on Linux (WSL2)
  and Windows.
- **Open per-image Edit metadata... dialogs do not refresh** after a
  key rename — they snapshot at open time. Close and reopen.

</details>

<details>
<summary><strong>Frequently asked questions</strong></summary>

**Q. How do I undo a rename or delete?**
> You cannot from inside QuPath. Close QuPath, restore `project.qpproj`
> from a backup or version control (e.g., `git checkout`), then reopen.
> Image files are never touched by these operations — only the metadata
> sidecar.

**Q. What counts as a "metadata key" for this tab?**
> User-defined keys on image entries. The built-in fields (Name, ID,
> URI, Description, Tags) are entry properties, not metadata keys, and
> are not listed here.

**Q. Can I rename to a key that already exists on some entries?**
> Yes — the Rename dialog detects this and asks you to pick
> Overwrite / Skip / Cancel before committing.

**Q. The keys tab is slow to refresh on my big project. What's going on?**
> A full refresh of both tabs runs after every mutation; on 10k+-entry
> projects this can pause the UI briefly. Report it if painful — we can
> switch to in-place refresh in v1.1.

**Q. Can I do this from a script?**
> Not in v0.2.0 — `MetadataKeyOperations` is not yet exposed as a public
> scripting API. Pete Bankhead's original Groovy rename script (in the
> forum thread above) still works for rename-only batch jobs. Public
> scripting surface is on the v1.1 roadmap.

</details>
