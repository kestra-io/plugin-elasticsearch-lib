# AGENTS.md

## What

- Shared kernel library published as `io.kestra.plugin:plugin-elasticsearch-lib`, consumed by `plugin-elasticsearch` (OSS) and `plugin-ee-elasticsearch` (EE).
- Provides classes under `io.kestra.plugin.elasticsearch.shared`: `ElasticsearchConnection` (hosts, basic auth, headers, TLS, `targetServerVersion` / compatibility headers) and `BulkService` (buffered bulk indexing with `requests.count` / `records` / `requests.duration` metrics).

## Why

- OSS and EE each shipped a near-verbatim copy of the connection-building and bulk-indexing code. This library removes that duplication so both consumers evolve the connection and bulk logic in one place instead of drifting apart.

## Local rules

- This library is shared between OSS and EE **only** — it is not a general-purpose Elasticsearch client wrapper. Do not add task/trigger classes, plugin docs, or plugin icons here; each consumer keeps its own.
- It is a plain library, not a plugin: no `package-info.java` with `@PluginSubGroup`, no `shadowJar`, no `X-Kestra-*` jar manifest.
- Keep the ES client version (`elasticsearchVersion` in `gradle.properties`) in lockstep across both consumers — a version drift between OSS and EE would make the `connection` schema diverge between the two task groups.
- Preserve metric names (`requests.count`, `records`, `requests.duration`) and error message text byte-for-byte; both consumers' users template outputs and grep logs on these.

## References

- https://kestra.io/docs/plugin-developer-guide
- https://kestra.io/docs/plugin-developer-guide/contribution-guidelines
