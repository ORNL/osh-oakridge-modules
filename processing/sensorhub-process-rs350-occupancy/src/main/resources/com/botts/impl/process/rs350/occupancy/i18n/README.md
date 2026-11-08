# RS350 Occupancy Process

The **RS350 Occupancy Process** monitors a lane equipped with an **RS-350** spectroscopic radiation detector, packages each occupancy or spectroscopic alarm window into an OSCAR **Occupancy** event record (including foreground/background spectra and isotope analysis), and coordinates retained video/frame capture on the parent lane.

## Plain-Language Terms

| Term | Meaning |
| --- | --- |
| **RS-350 Backpack / Portal Detector** | A spectroscopic gamma/neutron detector that outputs full energy spectra (`N42` format) in addition to gross count rates, enabling automated isotope identification (WebID). |
| **Occupancy** | A single passage or measurement window bounded by occupancy start and stop triggers on the lane. |

## Configuration Reference

- **Serial Number**: Unique identifier suffix for this RS-350 occupancy process instance.
- **Parent System (Containing RPM)**: System UID of the parent lane containing the RS-350 driver and cameras. Leave blank to automatically bind to the enclosing lane system.
