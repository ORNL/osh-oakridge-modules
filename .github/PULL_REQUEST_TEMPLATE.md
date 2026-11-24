## Summary
<!-- Concise explanation of the problem solved or capability introduced, and why this approach was chosen. -->

## Related Issues / Coordinated PRs
<!-- Link tracking issues or coordinated same-branch PRs in ORNL/oscar, ORNL/oscar-viewer, ORNL/osh-core, ORNL/osh-addons, or ORNL/oscar-regression. -->
- Roadmap / Tracking Issue:
- Matching `ORNL/oscar` branch (if cross-repo):

## Architectural & 50-Lane Scale Checklist
Please verify against [`AGENTS.md`](../AGENTS.md) before marking Ready for Review:
- [ ] **Storage & Indexing:** Preserves `PARQUET_PRIMARY` routing (`ParquetArchiveService` / `PostgisHotTierObsStore` / `DatabasePurgePolicy` updated in lockstep if adding datastreams) with zero growing SQL indexes and `<= 2` concurrent DuckDB sessions.
- [ ] **1 Hz+ Driver Allocation & Concurrency:** No per-tick SWE `DataComponent.copy()` tree cloning on `>= 1 Hz` outputs; no blocking TCP/serial connect held across `doStop()` locks; all EventBus subscriptions closed on `doStop()`.
- [ ] **Localization Parity:** Any new `ModuleConfig` `@DisplayInfo` fields or SensorHub Admin `README` edits preserve `en`/`es`/`fr`/`el` parity (`:sensorhub-webui-oscar:test` + `node tools/validate-release-metadata.js`).
- [ ] **Sanitization:** Zero private RFC1918 lab IPs, internal hostnames, or credentials in code, tests, or comments.

## Verification
<!-- Paste Gradle test commands and results executed locally. -->
- [ ] Self-contained Gradle module tests pass (`.github/workflows/ci.yml`)
