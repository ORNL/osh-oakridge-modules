# Mobile Detector System

Deploys a mobile radiation detector — an **RS-350 backpack** or a **Kromek D5** walker — as a trackable OSCAR system. Use this module type instead of raw RS-350/D5 drivers or a fixed-portal **Lane System** (which exposes cameras, WebRelay, and vehicle OCR options that do not apply to a mobile unit).

The detector appears in the OSCAR Viewer with live GPS map tracking, real-time alarm markers, spectroscopic isotope identification (`WebID` for RS-350), and alarm episode adjudication in the event table.

## Plain-Language Terms

| Term | Meaning |
| --- | --- |
| **RS-350 Backpack** | Wearable spectroscopic gamma/neutron detector streaming `N42` foreground/background spectra, alarms, and GPS fixes over TCP. |
| **Kromek D5 Walker** | Handheld spectroscopic gamma/neutron detector polled at 1 Hz over TCP for radiometric status, 4,096-channel spectra, and GPS fixes. |
| **Detector Connection** | Single authoritative TCP host and port for the mobile detector; automatically synchronized with the underlying child driver. |

## Configuration Reference

- **Module Name**: Display name for the mobile detector (12 characters or fewer).
- **Unique ID**: Unique identifier suffix (for example `backpack-01`), mapped to `urn:osh:system:lane:<uniqueID>`.
- **Operational View Keys**: Optional lowercase tags scoping which operators see this mobile unit.
- **Detector Connection**: Select **RS-350 Backpack** or **Kromek D5**, then enter **Remote Host** and **Remote Port**.
