# OSCAR operator-manual assets

The localized OSCAR 3.11.8 administrator and operator manuals are loaded directly by the OpenSensorHub administration interface from the OSCAR Service configuration package:

- [English](../../services/sensorhub-service-oscar/src/main/resources/com/botts/impl/service/oscar/i18n/README.md)
- [Español](../../services/sensorhub-service-oscar/src/main/resources/com/botts/impl/service/oscar/i18n/README_es.md)
- [Français](../../services/sensorhub-service-oscar/src/main/resources/com/botts/impl/service/oscar/i18n/README_fr.md)
- [Ελληνικά](../../services/sensorhub-service-oscar/src/main/resources/com/botts/impl/service/oscar/i18n/README_el.md)

The `images` directory contains the shared, ordered training screenshots. Keep image names stable because all localized manuals reference their upstream raw GitHub URLs so the pictures also render inside the administration README panel. Companion bite-sized `1080p` `30 fps` workflow screen recordings are maintained in [`ORNL/oscar-video-guides`](https://github.com/ORNL/oscar-video-guides); all 29 bundled offline video guides ship with native English (`en`), Spanish (`es`), French (`fr`), and Greek (`el`) subtitle tracks that switch automatically with the operator's selected UI language.

## Console navigation and toolbar icon symbols

When introducing an OSCAR Viewer page or toolbar/lane action in the manuals, always pair the page or control name with its on-screen icon symbol (both **Guided Walkthrough** and **Operator Help (`?`)** also provide 1-click **Highlight Icon** buttons for **Status of Health** and every other page/tool):

| Page or control | Route / location | Visual icon symbol |
| --- | --- | --- |
| **Dashboard** | `/` | Four-tile grid icon |
| **Events** | `/event-log` | Warning-triangle (`!`) icon |
| **Status of Health** | `/health` | Heart-pulse monitor icon |
| **Map** | `/map` | Map-pin icon |
| **National** | `/national-view` | Connected-nodes network icon |
| **Report Generator** | `/report` | Bar-chart icon |
| **Alarm Transfer** | `/alarm-transfer` | QR-scanner icon |
| **Users & Access** | `/users` | Person-and-gear icon |
| **USB Backups** | `/usb-backups` | USB-trident icon |
| **Runtime Extensions** | `/extensions` | Puzzle-piece icon |
| **Servers** | `/servers` | Cloud icon |
| **Guided Walkthrough / Training Coach** | Top bar & Help (`?`) | Graduation-cap icon |
| **Operator Help (`?`)** | Top bar | Circled question-mark (`?`) icon |
| **Settings** | Top bar / navigation | Gear icon |
| **Lights & Sound (`WebRelay-Quad`)** | `/health` & `/lane-view` | Megaphone speaker icon |

## Optional side-by-side CAS Rapiscan RPM Edge Proxy (`oscar-rpm-proxy`)

Portals running OSCAR 3.11.8 side-by-side with an existing Central Alarm Station (CAS) can fan out `1–50` Rapiscan RPM TCP feeds in real time (`< 1 ms` forwarding latency) inside an isolated `oscar-rpm-proxy` container (`sudo bash oscar.sh rpm-proxy` on Ubuntu Server 24.04 or `.\oscar.bat rpm-proxy` on Windows 11 + WSL 2) so OSCAR restarts or upgrades never interrupt the existing CAS. The proxy supports both Port-Mapped mode (`11601..11650`) and Ubuntu 24.04 Transparent Virtual-IP `:1600` mode, automatic `SG1..SG3` / `SN1..SN2` setup replay, line-aligned framing, and `<= 4 s` upstream-gated disconnect detection:

```sh
sudo bash oscar.sh rpm-proxy configure --from-csv config.csv --emit-oscar-csv oscar-proxied-lanes.csv && sudo bash oscar.sh rpm-proxy start
```
