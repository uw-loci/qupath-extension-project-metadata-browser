# QuPath Project Metadata Browser

A [QuPath](https://qupath.github.io) extension that opens a table view of
every image in the current project, with every built-in field and user
metadata key as a sortable, filterable column. Modelled on QuPath's
built-in TMA Results Viewer, but for whole projects.

**New in v1.1:** the browser is now a buffered editor -- your edits stay
in memory until you click **Save**, and every action (cell edit, rename,
paste, import, regex extract) is undoable. New workflows in this release:
template export + reimport for partner-supplied metadata, Excel-style
copy/paste, and regex extraction from filenames. See
[Buffered editing in v1.1](docs/user-guide.md#buffered-editing-and-save--discard)
in the user guide.

**Coming from v0.2.0?** The browser now works as a buffered editor: your
edits accumulate in a working copy and commit to disk only when you click
**Save**. Rename, delete, per-image edits, inline cell edits, paste,
import, and regex extraction all behave this way. The upside: anything
you do is undoable with Ctrl+Z before you save, and the close prompt
asks before discarding unsaved work. The on-disk project is untouched
until Save.

![Project Metadata Browser window showing the Filter rows box, sortable Name/ID/URI/Description/Tags columns alongside OCR_* metadata columns, an entry count of 467 shown / 467 total, and the Refresh, Fit Columns, Max column width, Export, and Close controls.](images/metadata-browser-window.png)

## Features

- One row per `ProjectImageEntry`; built-in columns (Name, ID, URI,
  Description, Tags) plus one column per user-metadata key used anywhere
  in the project.

![Detail of user-metadata columns: per-image OCR_field_0 through OCR_field_6 values plus an angle column, each shown as its own sortable column.](images/metadata-columns-detail.png)
- Global case-insensitive **Filter rows** search and per-column sort.
- **Fit Columns** button auto-sizes each visible column to its widest
  content, capped at the **Max column width** preference at the bottom
  of the window. Cells longer than the cap wrap to multiple lines so
  nothing is truncated. The cap is saved across QuPath sessions.
- **Columns** menu lists every column as a checkbox plus **Select All**
  and **Select None** for bulk show/hide of large metadata keysets.
- Multi-row selection with Ctrl+C (TSV) and export to CSV (default) or TSV.
- Double-click or right-click > Open image.
- Right-click > Edit metadata... for per-image editing. In v1.1 the edit
  lives in the working copy and is committed on Save (no longer
  immediately on dialog OK). Undoable.
- **Metadata Keys** tab lists every distinct metadata key in the project
  with a usage count, and lets you **rename** a key across every image,
  or **remove** it from every image, in one operation. In v1.1 both
  actions go on the undo stack and commit on Save. See the
  [user guide](docs/user-guide.md#metadata-keys-tab) for step-by-step.
  Originating from a request by `sebg` (with Pete Bankhead's per-project
  rename script) on the QuPath community forum at
  [image.sc](https://forum.image.sc/).
- **Buffered editor + undo / redo (new in v1.1).** Edits live in a
  working copy until you click **Save**. Every action is undoable
  (Ctrl+Z) and redoable (Ctrl+Shift+Z); Discard reverts to the last
  saved state.
- **Template export + reimport (new in v1.1).** **File > Export >
  Template for fill-in...** writes a CSV (or TSV) of Image ID + Name +
  existing-and-blank columns for a collaborator to fill in Excel.
  **File > Import metadata...** opens a 3-step wizard with a dry-run
  preview of adds / updates / left-alone rows.
- **Excel copy/paste (new in v1.1).** Copy a block out of Excel, click
  a cell, paste. Clipboard fills rightward and downward; overflow past
  the table edge is clipped with a warn count.
- **Regex extraction from filenames (new in v1.1).** **Edit > Extract
  columns from filenames...** opens a dialog with a live preview.
  Write a regex with named groups (`(?<patient>P\d+)`) -- each named
  group becomes a new metadata column. Existing-column collisions
  reuse the v0.2.0 rename's Overwrite / Skip / Cancel policy.
- Refresh (F5) picks up metadata added by scripts or acquisitions while
  the browser is open. Disabled while there are unsaved edits.

## Install

Drop the shadow JAR from `build/libs/` into QuPath's `extensions/`
folder, or drag it onto the main QuPath window.

## Use

**Extensions > Project Metadata Browser > Browse Metadata...**

## Build

```
./gradlew shadowJar
```

Requires QuPath 0.6.0+ and Java 21.

## Support

For general support and feature requests, please post on the [image.sc forum](https://forum.image.sc/) with the `#qupath` tag and mention `@Mike_Nelson` to flag the topic for my attention.

## Source / prior art

**If you are looking for a QuPath project-metadata editor, look at
[`zindy/qupath-extension-project-metadata-editor`](https://github.com/zindy/qupath-extension-project-metadata-editor)
first.** Egor Zindy (`@EP.Zindy`) extracted QuPath's built-in
project-metadata editor into a standalone extension and has been
extending it actively — undo / redo, column add / rename / remove /
copy, regex extraction from filenames, Search & Replace, train / val /
test split assignment, and CSV import / export. The buffered-editor
architecture and the CellProfiler-style regex-from-filenames idea in
this extension's v1.0.0 release both come from his work; the
**[QuPath Metadata](https://forum.image.sc/t/qupath-metadata/80733/6)**
thread on the image.sc forum (starting at post #6) is where he
described the workflow that inspired the regex extraction surface here.

This extension's continued existence is a convenience: it already ships
in the `qupath-catalog-mikenelson` catalog and shares a code path with
the rest of the LOCI extensions. If you do not already use this catalog,
Zindy's extension is the better starting point — it has a wider feature
set, sees more development, and is the upstream reference for the
architectural patterns adopted here.

The project-wide rename script that the Metadata Keys tab's rename
operation is modelled on was written by Pete Bankhead and shared on
the QuPath community forum at [image.sc](https://forum.image.sc/) in
response to a request by `sebg`. The tab generalizes that script and
adds the matching removal operation, a usage count per key, and a
no-undo confirmation gate.

The v1.0.0 buffered editor model -- a working copy of project metadata
edited in memory, with every action undoable and an explicit Save to
commit -- follows the design QuPath core used in its built-in
project-metadata editor and is the same architectural choice Zindy
makes in his extension. The pattern is established and not original to
either project. This extension's implementation is a clean-room rewrite
inside the extension's existing code; no code was lifted from Zindy's
extension or any other source.

## License

Apache License 2.0 -- see [LICENSE](LICENSE).
