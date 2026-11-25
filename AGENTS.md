# AI Agent & Contributor Guide — `ORNL/osh-oakridge-modules`

This repository contains Oak Ridge National Laboratory's OpenSensorHub (OSH) radiation portal monitoring (RPM), camera/HLS/MediaMTX, cargo NII (`uff`), lane orchestration, and storage/lifecycle modules built inside the [`ORNL/oscar`](https://github.com/ORNL/oscar) multi-repository release workspace.

Both human contributors and AI coding agents (`Codex`, `Claude Code`, `Gemini` / `Antigravity`, `Cursor`, `Copilot`) **must** uphold the architectural invariants below before proposing or modifying code. For cross-repository release planning, GitHub Project / Milestone tracking, and `v3.12.0` feature blueprints, read [`ORNL/oscar/AGENTS.md`](https://github.com/ORNL/oscar/blob/main/AGENTS.md) and [`ORNL/oscar/docs/ARCHITECTURAL_ROADMAP.md`](https://github.com/ORNL/oscar/blob/main/docs/ARCHITECTURAL_ROADMAP.md).

---

## 1. Non-Negotiable Scale & Storage Invariants (50+ Lane Portals)

1. **`PARQUET_PRIMARY` Storage Routing & 22-Stream Allowlist Lockstep:**
   - High-rate telemetry (`gammaGrossCount`, `neutronGrossCount`, `LiveDriverLocationOutput`, `ConnectionStatus`, `ThresholdOutput`, `SpeedOutput`, RS-350/D5 `ForegroundOutput` & `BackgroundOutput`, etc.) **must never** persist unbounded rows into PostgreSQL `oscar.obs`.
   - [`OscarTieredObsStore`](https://github.com/ORNL/osh-addons/blob/OSCAR/persistence/sensorhub-datastore-postgis/src/main/java/org/sensorhub/impl/datastore/postgis/store/obs/OscarTieredObsStore.java), [`dist/config/standard/config.json`](https://github.com/ORNL/oscar/blob/main/dist/config/standard/config.json) (`archive.outputNames`), and [`DatabasePurger`](services/sensorhub-service-oscar/src/main/java/com/botts/impl/service/oscar/purge/DatabasePurger.java) govern the 22-stream `PARQUET_PRIMARY` allowlist and hot-tier retention lifecycle.
   - Whenever a new datastream is introduced, evaluate whether it belongs in `PARQUET_PRIMARY` 30-minute cold archival or singleton hot configuration storage across **all three locations** in lockstep, with unit tests in `:sensorhub-service-oscar:test`.
2. **Zero Growing SQL Indexes & Bounded DuckDB Concurrency (`max 2` sessions):**
   - Never add growing B-tree/GIN indexes on archive/cold observation tables, and never introduce background polling loops that open concurrent DuckDB Parquet scan sessions (`DuckDB` is strictly capped at `2` concurrent sessions per JVM to protect heap and file-descriptor budgets on 50-lane deployments).
3. **Zero Per-Tick SWE `DataComponent.copy()` Tree Cloning on 1 Hz+ Streams:**
   - Drivers publishing at `>= 1 Hz` (e.g., [`RS350Sensor`](sensors/sensorhub-driver-rs350/src/main/java/com/botts/impl/sensor/rs350/RS350Sensor.java) [`BackgroundOutput`](sensors/sensorhub-driver-rs350/src/main/java/com/botts/impl/sensor/rs350/output/BackgroundOutput.java) / [`ForegroundOutput`](sensors/sensorhub-driver-rs350/src/main/java/com/botts/impl/sensor/rs350/output/ForegroundOutput.java), Kromek D5, Rapiscan, Aspect) must initialize a reusable `liveDataStruct = dataStruct.copy()` once in `init()`, reuse `liveDataStruct.renewDataBlock()` (when variable-size arrays do not mutate schema dimensions) or update `liveDataStruct` in place without mutating the canonical `dataStruct` returned by `getRecordDescription()`, and assign `latestRecord = dataBlock` before publishing `DataEvent`.
4. **Non-Blocking Sensor Reconnect & Clean `doStop()` Teardown:**
   - Never hold a coarse lock shared with `doStop()` across a blocking TCP/serial `tryConnect()` timeout (`3–30 s`). Perform network connection attempts outside the swap lock, close superseded `ICommProvider` instances cleanly, and double-check `isStarted()` before installing a reconnected transport (see [`RS350Sensor.reestablishConnection`](sensors/sensorhub-driver-rs350/src/main/java/com/botts/impl/sensor/rs350/RS350Sensor.java)).
5. **EventBus Subscription Hygiene & Submodule Lifecycle (`LaneSystem` / `MobileDetectorSystem`):**
   - Never subscribe to hub-wide wildcard topics (`ModuleRegistry.EVENT_GROUP_ID`) without storing and closing the `Subscription` in `doStop()`, and never spawn unbounded `while` spin-threads inside module event handlers.
   - Sub-processes whose `init()` sets `initAsync = true` (such as [`Rs350OccupancyProcessModule`](processing/sensorhub-process-rs350-occupancy/src/main/java/com/botts/impl/process/rs350/occupancy/Rs350OccupancyProcessModule.java) and `ProcessChainModule`) return from `init()` in `ModuleState.INITIALIZING`. Always coordinate late detector startup through [`OccupancyProcessStarter.startIfStranded`](sensors/sensorhub-system-lane/src/main/java/com/botts/impl/system/lane/helpers/occupancy/OccupancyProcessStarter.java) on the parent system's `ExecutorService` (never blocking the OSH EventBus thread).
6. **4-Locale SensorHub Admin Parity (`en`, `es`, `fr`, `el`):**
   - Any `@DisplayInfo` label/description added to a `ModuleConfig` class or any update to SensorHub Admin `README` documentation must maintain 4-locale parity (`ConfigMessages*.properties` and `README{,_es,_fr,_el}.md`) verified by `:sensorhub-webui-oscar:test` and `node tools/validate-release-metadata.js` in `ORNL/oscar`.
7. **Repository Hygiene:**
   - Never commit private RFC1918 lab IP addresses, internal hostnames, credentials, `.DS_Store`, or IDE scratch files.

---

## 2. Planned `v3.12.0` Architecture: `MobileDetectorSystem` (`ORNL/osh-oakridge-modules#57`)

Contributors working on shared `v3.12.0` branches for `sensorhub-system-mobile` ([`ORNL/osh-oakridge-modules#57`](https://github.com/ORNL/osh-oakridge-modules/pull/57)) should implement against the current `main` baseline (`v3.11.14`+):

- **Shared Lifecycle Helpers:** Reuse [`OccupancyProcessStarter`](sensors/sensorhub-system-lane/src/main/java/com/botts/impl/system/lane/helpers/occupancy/OccupancyProcessStarter.java) (supporting both synchronous `INITIALIZED` and `initAsync = true` `INITIALIZING -> INITIALIZED` occupancy modules) and [`RpmRecoverySupervisor`](sensors/sensorhub-system-lane/src/main/java/com/botts/impl/system/lane/helpers/RpmRecoverySupervisor.java) patterns rather than duplicating ad-hoc module-start listeners.
- **No Unbounded `ConcurrentHashMap` Caches Across Module Reloads:** Scope camera/HLS capability discovery to the parent `MobileDetectorSystem` instance lifecycle (cleared in `doStop()`), rather than process-global static maps keyed by mutable UIDs.
- **Storage & Hot-Tier Verification:** Verify mobile `occupancy`, `adjudication`, and `MobileHealthOutput` vs `PARQUET_PRIMARY` allowlists in [`OscarTieredObsStore`](https://github.com/ORNL/osh-addons/blob/OSCAR/persistence/sensorhub-datastore-postgis/src/main/java/org/sensorhub/impl/datastore/postgis/store/obs/OscarTieredObsStore.java) and [`DatabasePurger`](services/sensorhub-service-oscar/src/main/java/com/botts/impl/service/oscar/purge/DatabasePurger.java) so high-rate mobile GPS (`LiveDriverLocationOutput`) and 1 Hz spectra archive strictly to Parquet without growing `ONLY oscar.obs`.
- **Self-Contained Unit Tests:** Add JUnit tests under `sensors/sensorhub-system-mobile/src/test/java` wired into [`.github/workflows/ci.yml`](.github/workflows/ci.yml).

---

## 3. Branch Workflow & Required Local Verification

Review-group developers have **Write (branch-maintainer)** access to collaborate directly on shared feature/fix branches in `ORNL/osh-oakridge-modules` (`feat/*`, `fix/*`), which triggers `.github/workflows/ci.yml` automatically. Merging to `main` and pushing `v*` release tags are restricted to the Repository Admin via GitHub Rulesets.

> **Cross-Repo CI Tip:** `.github/workflows/ci.yml` checks whether `ORNL/oscar` has a branch with the **exact same branch name** as your PR branch; if found, CI checks out that matching `ORNL/oscar` branch as the build root (otherwise `ORNL/oscar@main`).

Run from the `ORNL/oscar` workspace root before opening or updating a PR:

```bash
node tools/validate-release-metadata.js
./gradlew --no-parallel \
  :sensorhub-service-oscar:test \
  :sensorhub-webui-oscar:test \
  :sensorhub-service-bucket:test \
  :sensorhub-service-dailyfile-journal:test \
  :sensorhub-system-lane:test \
  :sensorhub-security-basic-session:test \
  :sensorhub-driver-aspect:test \
  :sensorhub-driver-rs350:test \
  :sensorhub-driver-kromek-d3s:test \
  :sensorhub-driver-kromek-d5:test \
  :sensorhub-driver-rapiscan:test
./gradlew assemble -x osgi
```
