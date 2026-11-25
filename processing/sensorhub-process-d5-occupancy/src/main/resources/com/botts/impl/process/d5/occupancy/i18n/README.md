# D5 Occupancy Data Process

The **D5 Occupancy Data Process** monitors a mobile detector lane or parent system equipped with a **Kromek D5** handheld/walker radiation detector, edge-detects 1 Hz dose, gamma, and neutron alarm flags into discrete alarm episodes, and publishes each episode as an OSCAR **Occupancy** event record (including peak count rates, alarm category, and GPS fix coordinates).

## Plain-Language Terms

| Term | Meaning |
| --- | --- |
| **Kromek D5 Handheld / Walker** | A portable gamma/neutron spectroscopic detector polled over TCP or USB/serial that reports live alarm flags, 4,096-channel spectra, and GPS coordinates. |
| **Alarm Episode (`Occupancy`)** | A bounded radiation alarm window starting on the rising edge of a D5 dose/gamma/neutron alarm and closing once alarm flags stay clear for 3 seconds. |

## Configuration Reference

- **Serial Number**: Unique identifier suffix for this Kromek D5 occupancy process instance.
- **Parent System (Containing RPM)**: System UID of the parent mobile detector or lane system containing the Kromek D5 driver. Leave blank to automatically bind to the enclosing system.
