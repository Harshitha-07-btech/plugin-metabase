# Kestra Metabase Plugin

## What

- Provides plugin components under `io.kestra.plugin.metabase`:
  - `databases.SyncSchema`, `databases.RescanValues`
  - `cards.Run`, `queries.Run`
  - `dashboards.List`, `dashboards.Get`, `dashboards.Trigger`
- Shared: `MetabaseConnectionInterface` (connection properties and client factory for tasks and trigger), `MetabaseClient`, `QueryOutputHelper`

## Why

- What user problem does this solve? Orchestrates Metabase BI tasks (schema syncs, queries, dashboards, and triggers) natively in Kestra pipelines.
- Why would a team adopt this plugin in a workflow? Allows automated reporting, database synchronization after ELT/dbt runs, and event-driven workflows triggered by Metabase updates.
- What operational/business outcome does it enable? Bridges data pipelines with BI consumers, automates dashboards and cards querying, and eliminates manual Metabase syncing.

## How

### Architecture

Single-module plugin. Source packages under `io.kestra.plugin.metabase`:

- `databases`
- `cards`
- `queries`
- `dashboards`

### Key Plugin Classes

- `io.kestra.plugin.metabase.databases.SyncSchema`
- `io.kestra.plugin.metabase.databases.RescanValues`
- `io.kestra.plugin.metabase.cards.Run`
- `io.kestra.plugin.metabase.queries.Run`
- `io.kestra.plugin.metabase.dashboards.List`
- `io.kestra.plugin.metabase.dashboards.Get`
- `io.kestra.plugin.metabase.dashboards.Trigger`

### Project Structure

```
plugin-metabase/
├── src/main/java/io/kestra/plugin/metabase/
├── src/test/java/io/kestra/plugin/metabase/
├── build.gradle
└── README.md
```

## Local rules

- Base the wording on the implemented packages and classes, not on template README text.

## References

- https://kestra.io/docs/plugin-developer-guide
- https://kestra.io/docs/plugin-developer-guide/contribution-guidelines
