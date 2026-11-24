# Lane Sensor System

Specialized sensor system module used to create a lane with an RPM and video drivers.

For a mobile radiation detector (RS-350 backpack, Kromek D5 walker), use the
**Mobile Detector System** module type instead — it shares this runtime but only
exposes the options that matter for a mobile unit (see `README-mobile-detector.md`).

## Configuration

Select `Sensors` from the left-hand accordion control and right-click for the context-sensitive menu in the
accordion control.
Click 'Add New Module' and select 'Lane System' from the list of available modules.

**- General Tab:**
  - Module Name: A unique name for the Lane, must be less than 12 characters
  - UniqueID: The platform's serial number, or a unique identifier, this will be used for all submodules and must be unique.
  - Auto Start: Check the box to start this module when OSH node is launched
  - Operational View Keys: Add one or more workstation view keys (for example, `north-gate`). A lane can belong to multiple views. Leave empty to show the lane only in the unscoped view.
  - Extended Occupancy Threshold (min): Duration in minutes (`1`–`1440`, default `1`) before an active occupancy logs `Fault - Extended Occupancy` on `occupancyStatus` and enables automatic `Code 9998: Extended Occupancy Gamma Alarm` adjudication for gamma-only extended occupancy alarms (any extended occupancy with a neutron alarm is never auto-adjudicated).
  - RPM Lights & Sound (WebRelay-Quad): Optional per-lane network relay integration (`Enable WebRelay Control = false` by default) for ControlByWeb WebRelay-Quad (`X-WR-4R3-E` / `X-410`) HTTP/XML modules (`stateFull.xml` / `state.xml`). Supports `Automatic (EML / Occupancy Gatekeeper)` (`AUTO`), `Pass-Through (Direct RPM Signals)` (`PASS_THROUGH`), and `Muted (Suppress All Lights & Sound)` (`MUTED`) modes across a Primary Pillar unit and optional Ancillary Dry-Contact unit, suppressing physical beacon/siren pulses on `Code 9999: EML Non-Threat` and (by default) `Code 9998: Extended Occupancy Gamma Alarm`, pulsing Channel 1 (`R3`, Gamma Red Light + Audible Siren) and/or Channel 2 (`R4`, Neutron Blue Light) on actionable alarms, and failing safe to hardware pass-through (`0,0,0,0`) when stopped.
  - Delete Data on Lane Removal: Check the box to remove systems data from database if lane is deleted from node.

**- Fixed Location:**
  - Latitude:
  - Longitude:

**- Lane Options Config:**
- Click `Add` to configure the submodules
  - *Initial RPM Config:*
    - Click `Add` and select between the `Rapiscan`, `Aspect`, `RS350`, and `D5` RPMs. To configure the RPMs, enter the host IP and TCP port of the device (`10001` typical for Rapiscan, `502` typical for Aspect Modbus, `1627` typical for RS-350).
      - **Rapiscan / Aspect / RS-350 / D5 RPM**
        - Remote Host:
        - Remote Port:
      - **Rapiscan RPM (Specific)**
        - EML Configuration: Enable and configure Enhanced Machine Learning (`EML Enabled`, `One Panel Mode`, `Custom Parameters`, `Use Auto Accumulate Time`) when an EML `.jar` plugin is loaded in OSCAR Service.
      - **Aspect RPM (Specific)**
        - Address Range:
    - The RPM driver submodule is created from this config the first time the lane starts.
      **The lane-level Remote Host/Port stays authoritative afterwards**: on every lane
      (re)initialization it is pushed down into the RPM driver's communication provider,
      so this is the one place to edit the RPM's address. (A warning is logged if the
      driver's comm settings had diverged.)
    - RS350 and D5 detectors don't report occupancies natively, so the lane manages an
      occupancy process submodule for them (named `<lane> - Occupancy`). It is created
      once and reused across restarts.

  - *Initial Camera Config:*
    - Click `Add` and select between the `Axis`, `Sony` and the `Custom` video cameras. To configure the video cameras you need the host IP of the device. If applicable, you can add the username and password of the camera. Additional configurations may be necessary for different camera types.

      - **Sony/Axis/Custom**
        - Remote Host: Enter your camera's `ip.ip.ip.ip:port` (e.g., `192.168.8.77:8554`).
        - Username: Enter your camera's username in this field.
        - Password: Enter your camera's password in this field.
      - **Axis (Specific):**
        - Stream Codec:
      - **Custom (Specific):**
        - Stream Path: Enter everything that comes after your camera's `ip.ip.ip.ip:port` (e.g., `/lane04_cam`).

## Tests

The default lane-module test suite is self-contained. Adjudication is tested with a local H2 system-driver database and an inserted occupancy, so it does not need a live RPM.

The four tests that start real Rapiscan or Aspect drivers are skipped unless explicitly enabled. Run them only against an authorized test RPM or emulator:

```sh
OSCAR_RUN_RPM_HARDWARE_TESTS=true \
RPM_HOST=192.0.2.10 \
../../gradlew :sensorhub-system-lane:test --tests LaneTests
```

`testSingleRapiscan` connects to port `1601`, `testSingleAspect` connects to port `502`, and the two multi-Rapiscan tests require endpoints on ports `1601` through `1624`. These tests no longer depend on an external camera stream.
