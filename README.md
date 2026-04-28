# QuPath Project Metadata Browser

A [QuPath](https://qupath.github.io) extension that opens a table view of
every image in the current project, with every built-in field and user
metadata key as a sortable, filterable column. Modelled on QuPath's
built-in TMA Results Viewer, but for whole projects.

![screenshot placeholder](docs/screenshot.png)

## Features

- One row per `ProjectImageEntry`; built-in columns (Name, ID, URI,
  Description, Tags) plus one column per user-metadata key used anywhere
  in the project.
- Global case-insensitive **Filter rows** search and per-column sort.
- **Fit Columns** button auto-sizes each visible column to its widest
  content, capped at the **Max column width** preference at the bottom
  of the window. Cells longer than the cap wrap to multiple lines so
  nothing is truncated. The cap is saved across QuPath sessions.
- **Columns** menu lists every column as a checkbox plus **Select All**
  and **Select None** for bulk show/hide of large metadata keysets.
- Multi-row selection with Ctrl+C (TSV) and export to TSV / CSV.
- Double-click or right-click > Open image.
- Right-click > Edit metadata... for in-place editing, persisted via
  `project.syncChanges()`.
- Refresh (F5) picks up metadata added by scripts or acquisitions while
  the browser is open.

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

## License

Apache License 2.0 -- see [LICENSE](LICENSE).
