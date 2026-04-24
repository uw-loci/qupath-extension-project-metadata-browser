# QuPath Project Metadata Browser

A QuPath extension that opens a dedicated browser window listing every image in
the currently-open QuPath project as a row, with every metadata key, built-in
field (name, ID, URI, description, tags), and user-added metadata value as a
column. Modelled on QuPath's built-in TMA Results Viewer.

## Features

- Table view of every `ProjectImageEntry` in the active project.
- Columns for built-in fields (Name, ID, URI, Description, Tags) plus the union
  of every user metadata key across the project.
- Global case-insensitive text search across all visible columns.
- Native TableView sorting on any column.
- Multi-row selection with Ctrl+C / context menu copy (tab-separated).
- Export the current filtered + sorted view to CSV or TSV.
- Double-click a row to open that image in the main QuPath viewer.
- Right-click > Edit metadata... to change values in bulk; changes persist via
  `project.syncChanges()`.

## Requirements

- QuPath 0.6.0 or later
- Java 21

## Install

Drop the shadow JAR from `build/libs/` into QuPath's `extensions/` folder, or
drag it onto the main QuPath window.

## Usage

With a project open, choose:

**Extensions > Project Metadata Browser > Browse Metadata...**

## Build

```
./gradlew shadowJar
```
