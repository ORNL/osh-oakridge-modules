# Lane Sensor System 

Specialized sensor system module to be used to create a lane with an RPM and video drivers. 

## Configuration

Configuring the sensor requires:
Select `Sensors` from the left-hand accordion control and right-click for the context-sensitive menu in the
accordion control.
Click 'Add New Module' and select 'Lane System' from the list of available modules.

**- General Tab:**
  - Module Name: A unique name for the Lane, must be less than 12 characters
  - UniqueID: The platform's serial number, or a unique identifier, this will be used for all submodules and must be unique.
  - Auto Start: Check the box to start this module when OSH node is launched
  - Operational View Keys: Add one or more workstation view keys (for example, `north-gate`). A lane can belong to multiple views. Leave empty to show the lane only in the unscoped view.
  - Delete Data on Lane Removal: Check the box to remove systems data from database if lane is deleted from node.

**- Fixed Location:**
  - Latitude:
  - Longitude:

**- Lane Options Config:**
- Click `Add` to configure the submodules 
  - *Initial RPM Config:*
    - Click `Add` and select between the `Rapiscan`, `Aspect`, and `RS350` RPMs. To configure the RPMs, enter the host IP and TCP port of the device (`10001` typical for Rapiscan, `502` typical for Aspect Modbus, `1627` typical for RS-350).
      - **Rapiscan / Aspect / RS-350 RPM**
        - Remote Host:
        - Remote Port:
      - **Rapiscan RPM (Specific)**
        - EML Configuration: Enable and configure Energy Metric Ratios (`EML Enabled`, `One Panel Mode`, `Custom Parameters`, `Use Auto Accumulate Time`) when an EML `.jar` plugin is loaded in OSCAR Service.
      - **Aspect RPM (Specific)**
        - Address Range: Modbus register range (`From` and `To`).

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
