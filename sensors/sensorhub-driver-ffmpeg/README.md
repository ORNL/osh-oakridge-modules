# FFmpeg Video Driver

The **FFmpeg Video Driver** connects to an IP camera stream (RTSP/HTTP) or recorded video file, serves low-latency live video to the OSCAR Viewer, and extracts retained JPEG evidence frames (`frame-01.jpg` through `frame-05.jpg`) when an occupancy alarm occurs.

## Plain-Language Terms

| Term | Meaning |
| --- | --- |
| **Keyframe / GOP Interval (`1–2 s`)** | A full reference video frame (`I-frame`). Cameras should be configured to send a keyframe every `1–2 seconds` (`GOP 30–60` at `30 fps`) so live streams start immediately and alarm snapshots align cleanly with the vehicle passage. |
| **Retained 5-Frame Media** | For every alarm occupancy, OSCAR extracts 5 evenly spaced JPEG frames across the occupancy window. After 30 days (`retainedSecondaryFramesDays = 30`), secondary frames (`01`, `02`, `04`, `05`) are pruned while the midpoint keyframe (`frame-03.jpg`) is kept for the full media retention window. |
| **Loopback RTSP Proxy (`mediamtx`)** | When **Live Stream Mode** in OSCAR Service is set to `RTSP_PROXY` or `HYBRID`, OSCAR publishes H.264 frames to the local `mediamtx` loopback server (`127.0.0.1:8554`) so multiple operator browsers can view live video without opening duplicate connections to the physical lane camera. |

## Connection

- **Connection String** / **File Path**: Set **Connection String** for a live RTSP/HTTP camera stream, or **File Path** for recorded video playback.
- **Use TCP**: Enable TCP transport for RTSP cameras on lossy or firewalled networks; otherwise UDP is used.
- **Ignore Data Timestamps**: Enable when the camera's internal clock is unset or unreliable so OSCAR stamps frames using the server clock.

## Outputs

- **Video Frames**: Publishes encoded H.264 video frames used for live MJPEG/WebRTC/HLS viewing and retained 5-frame alarm extraction.
- **HLS**: Publishes an HTTP Live Streaming playlist when file-backed HLS output is required.
- Configure stream ID, connection timeout, and automatic reconnection intervals as needed.
