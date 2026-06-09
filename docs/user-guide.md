# Project Metadata Browser — User Guide

> **Considering a project-metadata editor for QuPath?** Egor Zindy's
> [`qupath-extension-project-metadata-editor`](https://github.com/zindy/qupath-extension-project-metadata-editor)
> is the broader, upstream tool — start there if you do not already
> use the `qupath-catalog-mikenelson` catalog. It implements the
> CellProfiler-style regex extraction workflow he described in the
> [QuPath Metadata thread on image.sc](https://forum.image.sc/t/qupath-metadata/80733/6)
> (post #6 onward), which is also the source of inspiration for this
> extension's v1.0.0 regex feature. This extension shares its catalog
> with the rest of the LOCI tooling; that convenience is why it
> continues to ship.

**Coming from v0.2.0?** The browser now works as a buffered editor: your
edits accumulate in a working copy and commit to disk only when you click
**Save**. Rename, delete, per-image edits, inline cell edits, paste,
import, and regex extraction all behave this way. The upside: anything
you do is undoable with Ctrl+Z before you save, and the close prompt
asks before discarding unsaved work. The on-disk project is untouched
until Save.

## Buffered editing and Save / Discard

<details open>
<summary><strong>What buffered editing means</strong></summary>

When you open the Project Metadata Browser, the extension loads a
working copy of every image entry's metadata. Every edit -- a cell
change, a paste, a rename, an import, a regex extraction -- updates the
working copy, not the project file. Your changes commit to disk only
when you click **File > Save**.

> **If you used v0.2.0:** Rename and Delete on the Metadata Keys tab
> used to commit immediately. In v1.1 they queue in the working copy
> until you click Save. Upside: anything you do can be undone with
> Ctrl+Z before you save.

Screenshot: `images/buffered-editor-dirty-state.png` (to be captured
during WSL smoke in Phase 6).

</details>

<details>
<summary><strong>Undo and redo</strong></summary>

Every action is undoable. The Edit menu shows stack sizes as counters:
**Undo (3)** / **Redo (0)** (greyed out when empty). Accelerators:
Ctrl+Z to undo, Ctrl+Shift+Z to redo (Cmd+Z / Cmd+Shift+Z on macOS;
Ctrl+Y is bound as a Windows-muscle-memory alias for Redo). A new
action after an undo clears the redo stack -- standard text-editor
convention.

</details>

<details>
<summary><strong>Save, Discard, and closing the window</strong></summary>

**File > Save** writes every working-copy edit to `project.qpproj` in
one batched call. After Save the undo / redo stacks remain -- you can
still undo a just-saved edit, but would need to Save again to commit
the reverted state. **File > Discard changes** throws away the working
copy and reloads from the project file. **Closing with unsaved edits**
prompts **Save / Discard / Cancel** first.

Screenshot: `images/save-discard-cancel-prompt.png` (to be captured
during WSL smoke in Phase 6).

</details>

<details>
<summary><strong>What happens if QuPath crashes mid-edit</strong></summary>

The working copy lives in memory only -- no autosave in v1.1. A crash
or Force Quit loses in-progress edits; the on-disk project is
untouched. A normal QuPath quit with unsaved edits prompts Save /
Discard first; bypassing the prompt (Task Manager / kill -9) loses
the edits. Autosave is on the roadmap.

</details>

## Inline cell editing

<details open>
<summary><strong>Editing a single cell</strong></summary>

Double-click any user-metadata cell in the Entries table to edit it.
**Enter** commits, **Tab** commits and advances to the next column,
**Esc** cancels. A dirty cell gains a yellow left border until you Save
or Discard.

Screenshot: `images/inline-cell-edit.png` (to be captured during WSL
smoke in Phase 6).

</details>

<details>
<summary><strong>Adding a column</strong></summary>

Choose **Edit > Add column...** for a new user-metadata key. New
columns start empty; populate by typing, pasting from Excel, or
applying a regex extraction. Adding a column is itself undoable.

Cross-ref: [Pasting from Excel](#workflow-2-pasting-from-excel) for
the most common way to populate a new column.

</details>

<details>
<summary><strong>Which columns are editable</strong></summary>

Only **user-metadata key columns** are editable inline in v1.1.
Built-in columns (Name, ID, URI, Description, Tags) are sourced from
image-entry properties via different QuPath setters and are read-only
here. To rename an image, use QuPath's main project pane.

</details>

## Workflow 1: Templates and partner-supplied metadata

<details open>
<summary><strong>Why this workflow exists</strong></summary>

Often the person with the metadata is not the person sitting in front
of QuPath. The template workflow exports a CSV of Image ID + Image
Name + blank columns, your collaborator fills it in Excel, and you
import the filled-in file back into the project.

</details>

<details>
<summary><strong>Exporting a template for a collaborator</strong></summary>

Choose **File > Export > Template for fill-in...** from the browser's
menu (not QuPath's main File menu). The dialog asks which identifier
columns to include (Image ID and Image Name default on), which
existing keys to seed with current values, and which new keys to add
as blank columns. CSV by default; TSV optional.

Screenshot: `images/template-export-dialog.png` (to be captured
during WSL smoke in Phase 6).

</details>

<details>
<summary><strong>Importing the filled-in file</strong></summary>

Choose **File > Import metadata...**. Step 1 is a file picker with
auto-detected delimiter (comma, tab, semicolon for EU-Excel locales).
You can override if detection is wrong.

Screenshot: `images/import-step1-file-picker.png` (to be captured
during WSL smoke in Phase 6).

</details>

<details>
<summary><strong>Reading the dry-run preview</strong></summary>

Step 2 is the dry-run preview. Rows colored by what the import will
do:

- `[+] Add` -- file row introduces values for new columns.
- `[~] Update` -- file row matches a project entry; metadata will be
  updated.
- `[ ] Same` -- file value already matches project value; nothing to
  do.
- `[!] No match` -- file row's identifier does not match any project
  entry. Skipped.

A summary line tallies all four states across the full file. Use this
step to catch a wrong identifier column or a partner who saved over
the wrong sheet.

Screenshot: `images/import-step2-dry-run-preview.png` (to be captured
during WSL smoke in Phase 6).

</details>

<details>
<summary><strong>Applying the import</strong></summary>

Step 3: click **Apply**. The whole import is one undoable action --
Ctrl+Z reverts the entire import. The edits live in the working
copy; remember to Save when done.

</details>

## Workflow 2: Pasting from Excel

<details open>
<summary><strong>Copying a block out of Excel</strong></summary>

Select a rectangular block in Excel and Ctrl+C. Excel writes
tab-separated text to the clipboard with one row per line. You do
not need to include the header row.

</details>

<details>
<summary><strong>Pasting at a focused cell</strong></summary>

Click a single user-metadata cell to focus it, then Ctrl+V. The
clipboard fills rightward and downward from the anchor. Overflow
past the table edge is clipped with a status-line count: "Pasted
30 rows by 4 columns; 8 rows past table edge skipped." Paste does
not add new columns -- add them first via **Edit > Add column**.

Screenshot: `images/excel-paste-anchor.png` (to be captured during
WSL smoke in Phase 6). Cross-ref:
[Adding a column](#which-columns-are-editable).

</details>

## Workflow 3: Extracting metadata from filenames

<details open>
<summary><strong>Opening the dialog and choosing a source column</strong></summary>

Choose **Edit > Extract columns from filenames...** from the
browser's menu. The dialog asks which column to read source text
from -- Image Name by default; URI and existing user-metadata
columns also offered. The source column determines what your regex
sees.

Screenshot: `images/regex-dialog-source-column.png` (to be captured
during WSL smoke in Phase 6).

</details>

<details>
<summary><strong>Writing the regex and reading the preview</strong></summary>

Write a regex with named capture groups. Each named group becomes a
new metadata column. The preview table updates live as you type,
showing the first ~50 entries with source value on the left and
parsed groups on the right. Non-matching rows are greyed out.
Invalid regex is flagged inline with the parser's error.

Two worked examples:

```
Example 1 -- simple split of patient_timepoint_stain.svs:
Regex: (?<patient>P\d+)_(?<timepoint>T\d+)_(?<stain>[A-Z0-9]+)\.svs
Result: patient, timepoint, stain
P12_T03_HE.svs -> patient=P12, timepoint=T03, stain=HE
```

```
Example 2 -- optional batch group:
Regex: (?<patient>P\d+)(?:_B(?<batch>\d+))?_T(?<timepoint>\d+)\.svs
Result: patient, batch (optional), timepoint
P07_B2_T01.svs -> patient=P07, batch=2, timepoint=01
P07_T01.svs    -> patient=P07, batch=(empty), timepoint=01
```

</details>

<details>
<summary><strong>Applying the extraction</strong></summary>

If any named group's name matches an existing user-metadata key,
the dialog asks for a collision policy (Overwrite / Skip / Cancel)
before Apply is enabled -- same shape as the v0.2.0 Rename dialog.
"Skip non-matching entries" defaults ON; uncheck it only if you
want non-matching entries to have their existing values for those
columns cleared. Apply commits as one undoable action.

Screenshot: `images/regex-dialog-apply.png` (to be captured during
WSL smoke in Phase 6).

</details>

## Metadata Keys tab

<details open>
<summary><strong>What this tab does</strong></summary>

The Metadata Keys tab lists every distinct user-metadata key found
anywhere in the open project, alongside the number of image entries
that currently use each key. From this tab you can **rename** a key --
copying its value to a new key name on every entry that has it -- or
**remove** a key from every entry that has it. In v1.1 both
operations go on the undo stack and commit on Save.

This tab implements a feature request from `sebg` on the QuPath
community forum at [image.sc](https://forum.image.sc/), generalizing
Pete Bankhead's per-project rename script and adding a matching
removal operation with usage counts.

Screenshot: `images/metadata-keys-tab-overview.png` (to be captured
during WSL smoke in Phase 6).

</details>

<details>
<summary><strong>Before you start (recommended)</strong></summary>

- Back up the project (or have it under version control) before
  saving large rename / delete operations.
- Close any other QuPath sessions that might be editing the same
  project. Concurrent edits during Save can race against the
  in-memory rollback path.
- If you are unsure of a key's usage, click it and check the Used by
  count plus the Sample value before renaming or deleting.

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
5. Click **Rename**. The rename goes onto the undo stack and is
   visible in the Keys tab and in any affected Entries cells
   immediately. It commits to disk when you next click **Save**.

Validation rules for the new key:

- Cannot be empty.
- Cannot contain spaces, tabs, or newlines.
- Cannot equal the old key.

If no entry has both keys, the collision policy is moot -- the rename
is a straight move. The radios still appear so the policy choice is
visible before commit.

Screenshot: `images/rename-key-dialog.png` (to be captured during WSL
smoke in Phase 6).

</details>

<details>
<summary><strong>Removing a metadata key from the project</strong></summary>

1. Click the **Metadata Keys** tab.
2. Click the row for the key you want to remove, then click **Delete...**
   (or right-click the row and choose **Delete...**).
3. The confirmation dialog reads: **"Delete the metadata key
   \"tags_legacy\"?"** with the body explaining the change is undoable
   until you Save.
4. Click **Delete from N entries**. The key disappears from every
   entry in the working copy and goes onto the undo stack. The
   removal commits to disk when you next click **Save**. Ctrl+Z
   restores the key (and every entry's value of it) until then.

Screenshot: `images/delete-key-confirm.png` (to be captured during WSL
smoke in Phase 6).

</details>

<details>
<summary><strong>What happens behind the scenes</strong></summary>

Both operations apply a project-wide command to the working copy --
the live cells in the Entries tab and the Keys tab update
immediately. On Save, the buffered editor writes every per-entry diff
under `synchronized(getMetadata())` and calls `project.syncChanges()`
**once** at the end of the batch (not once per entry), so the on-disk
`project.qpproj` is updated in a single IO step.

If the sync call fails -- for example, the file is read-only -- the
in-memory changes for the just-saved batch are reverted to the
pre-Save snapshot for every touched entry and an error notification
is shown. The working copy stays dirty so you can fix the underlying
issue and Save again. The status line at the bottom of the window
briefly shows *"Saved N changes."* (or *"Discarded N changes."*)
before reverting to the usual per-tab count text.

</details>

<details>
<summary><strong>Limitations and known issues</strong></summary>

- **No autosave.** Edits live in memory until you click Save. If
  QuPath crashes mid-edit, the on-disk project is untouched but
  in-progress edits are lost. Roadmap item.
- **No "do not show this again"** option on the close prompt.
- **Built-in columns (Name, ID, URI, Description, Tags) are
  read-only in the browser.** Edit them in QuPath's main project
  pane. May reconsider in v1.2.
- **Paste does not add new columns.** Add the column first via
  **Edit > Add column**. The status banner names the clipped
  overflow count.
- **Import wizard does not clear metadata for entries missing from
  the file.** A partner who emails back 200 of 230 rows is
  filtering, not implicitly deleting. The dry-run preview makes
  this visible.
- **Inline editing on tables with 100+ visible columns may feel
  sluggish.** Hide unused columns via the Columns menu.
- **macOS not verified.** v1.1 built and tested on Linux (WSL2) and
  Windows.

</details>

<details>
<summary><strong>Concurrent Groovy script writes can be overwritten on Save</strong></summary>

The browser loads a working copy of every entry's metadata when you
open it (or last clicked Refresh). Subsequent edits live in that
working copy until you click Save. If another QuPath script writes
to a metadata key on an entry **while the browser is open and dirty**,
your next Save can silently overwrite that script's change -- because
the working copy started from a pre-script snapshot and Save commits
the working copy's diff back to disk.

Mitigations until v1.2 ships a three-way merge:

- Avoid concurrent Groovy script mutations of project metadata while
  the browser is open and dirty.
- If a script must run during a session, click Save (or Discard) in
  the browser first, then run the script, then click Refresh once the
  script completes -- F5 / the Refresh button is intentionally
  disabled while there are unsaved edits.
- For batch metadata rewrites the safest pattern is "browser closed
  while script runs" today.

</details>

<details>
<summary><strong>Frequently asked questions</strong></summary>

**Q. I made a mistake. How do I undo it?**
> Ctrl+Z (Cmd+Z on macOS). Every action is undoable until you Save
> -- and after Save, Ctrl+Z still rolls back the working-copy
> state; Save again to commit the reverted state to disk.

**Q. Can a partner's CSV add new metadata columns to my project?**
> Yes. The dry-run preview shows added columns and updated columns
> separately so you can confirm before Apply.

**Q. Can I paste a block with a column my project doesn't have yet?**
> No -- paste only updates existing columns. Add the column first
> (**Edit > Add column**). The status banner reports clipping.

**Q. What if QuPath crashes while I have unsaved edits?**
> The on-disk project is untouched; in-progress edits are lost.
> No autosave in v1.1.

**Q. The window prompted me to Save when I tried to close it. Why?**
> The browser has unsaved edits. Save commits; Discard throws them
> away; Cancel keeps the window open.

**Q. What counts as a "metadata key" for this tab?**
> User-defined keys on image entries. The built-in fields (Name, ID,
> URI, Description, Tags) are entry properties, not metadata keys,
> and are not listed here.

**Q. Can I rename to a key that already exists on some entries?**
> Yes -- the Rename dialog detects this and asks you to pick
> Overwrite / Skip / Cancel before committing.

**Q. The keys tab is slow to refresh on my big project. What's going on?**
> A full refresh of both tabs runs after every command application;
> on 10k+-entry projects this can pause the UI briefly. Report it if
> painful -- we can switch to in-place refresh in v1.2.

**Q. Can I do this from a script?**
> Not in v1.1 -- `MetadataKeyOperations` is not yet exposed as a
> public scripting API. Pete Bankhead's original Groovy rename
> script (in the forum thread above) still works for rename-only
> batch jobs. Public scripting surface is on the roadmap.

</details>
