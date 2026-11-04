# How to use the Metabase plugin

Run saved questions and native queries, refresh database metadata, and read or watch dashboards in [Metabase](https://www.metabase.com/) from Kestra flows. The plugin calls the [Metabase REST API](https://www.metabase.com/docs/latest/api-documentation) through Kestra's own HTTP client.

## Authentication

Every task and trigger needs `url` (required), the base URL of your Metabase instance. Set exactly one authentication mode, otherwise the task fails with an error naming the properties:

- `apiKey` (secret): sent in the `x-api-key` header. Requires Metabase v0.47 or later.
- `sessionToken` (secret): sent in the `X-Metabase-Session` header.
- `username` and `password` (secret): both required together. The plugin opens a session with `POST /api/session` and deletes it with `DELETE /api/session` when the task or evaluation ends.

Optional `requestTimeout` (default 60 seconds) sets the connect and read timeout of each HTTP request, it does not limit the task duration. Store credentials as [secrets](https://kestra.io/docs/concepts/secret), for example `{{ secret('METABASE_API_KEY') }}`, and use [plugin defaults](https://kestra.io/docs/workflow-components/plugin-defaults) to avoid repeating the connection on every task.

## Tasks

A failed HTTP call is reported with the status and what to check: 401 and 403 point at the credentials and permissions, 404 at the id you passed.

### Cards and queries

`cards.Run` runs a saved question (`cardId`, required, optional `parameters`). `queries.Run` runs a native SQL query (`databaseId` and `nativeQuery`, required, optional `templateTags`). Both take `fetchType`: `FETCH` (default), `FETCH_ONE`, `STORE` or `NONE`, and output `row`, `rows`, `uri` and `size` depending on it.

- `FETCH`, `FETCH_ONE` and `NONE` use the regular query endpoints, which Metabase caps at 2,000 rows. `FETCH` and `NONE` fail when Metabase flags the result as truncated, `FETCH_ONE` only needs the first row.
- `STORE` uses the JSON export endpoints and streams every row to an Ion file in internal storage. The row keys are the ones Metabase writes in its JSON export, which can differ from the column names of the other fetch types.
- Metabase answers a failed SQL query with HTTP 202 and `"status": "failed"`. The task fails with the Metabase error text.

### Databases

`databases.SyncSchema` and `databases.RescanValues` each take a `databaseId` (required) and ask Metabase to start the operation. They have no output and fail if Metabase does not answer `{"status": "ok"}`. Metabase runs the operation asynchronously, the task does not wait for it to finish.

### Dashboards

`dashboards.List` outputs `dashboards` and `size`. `dashboards.Get` takes a `dashboardId` (required) and outputs `dashboard` (metadata) and `cards` (the dashboard's cards, returned only there).

## Triggers

`dashboards.Trigger` polls `GET /api/dashboard` every `interval` (default 5 minutes) and fires when a dashboard's `updated_at` (or `created_at`) is newer than the stored watermark. The watermark is kept in the namespace KV store, keyed by flow and trigger id. By default the first evaluation only records the watermark, set `onStartup: true` to fire for all existing dashboards instead. Output is the same as `dashboards.List`: `{{ trigger.dashboards }}` and `{{ trigger.size }}`.
