# Bucket Storage Service

The **Bucket Storage Service** provides local filesystem-backed object storage for OSCAR files, including site diagrams, generated reports (`reports/`), adjudication attachments, EML analysis plugins, and retained event media (`videos/`).

## Plain-Language Terms

| Term | Meaning |
| --- | --- |
| **Bucket** | A named logical folder managed by OSCAR (such as `reports`, `videos`, `site-diagram`, or `eml`) that stores uploaded or generated files on disk and serves them over authenticated HTTPS endpoints. |
| **CORS (Cross-Origin Resource Sharing)** | Standard browser security headers that allow the OSCAR Viewer web interface to load images, reports, and retained media from the SensorHub backend. |

## Configuration Reference

- **File Store Root Directory**: Local disk folder where all bucket subfolders and stored files are kept.
- **Initial Buckets**: List of bucket names created automatically when the service starts.
- **Max File Size (MB)**: Maximum allowed size (in megabytes) for a single uploaded file.
- **Enable CORS**: Adds Cross-Origin Resource Sharing headers so the browser-based OSCAR Viewer can fetch and upload files.
- **Security**: Access-control rules governing which authenticated users can read or upload bucket objects.
