# Occupancy Data Process

The **Occupancy Data Process** monitors a lane's Radiation Portal Monitor (RPM) stream, packages each vehicle or pedestrian passage into a structured **Occupancy** record (with start time, end time, gamma/neutron alarm flags, peak counts, and associated camera clips/frames), and triggers retained media capture when an alarm occurs.

## Plain-Language Terms

| Term | Meaning |
| --- | --- |
| **Occupancy** | A single passage of a vehicle, container, or person through a detection lane between occupancy start (`GS`) and occupancy end (`GX`). |
| **Event UUID** | A globally unique identifier (`UUID`) assigned to each occupancy event so operators can search, adjudicate, and cross-reference the exact event across reports and federated nodes. |
| **Parent System UID** | The unique SensorHub identifier (`urn:osh:system:lane:...`) of the lane system that owns the RPM and cameras for this process. |

## Configuration Reference

- **Serial Number**: Unique identifier suffix for this occupancy process instance (usually matches the lane name or ID).
- **Parent System (Containing RPM)**: System UID of the parent lane containing the RPM driver and camera streams. When left blank, the process automatically uses its enclosing parent lane system.
