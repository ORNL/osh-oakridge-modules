# Servicio de almacenamiento en buckets

El **Servicio de almacenamiento en buckets** proporciona almacenamiento de objetos en el sistema de archivos local para archivos de OSCAR, incluidos planos del sitio, informes generados (`reports/`), adjuntos de adjudicación, complementos EML y medios retenidos de eventos (`videos/`).

## Términos en lenguaje claro

| Término | Significado |
| --- | --- |
| **Bucket** | Una carpeta lógica con nombre administrada por OSCAR (como `reports`, `videos`, `site-diagram` o `eml`) que almacena archivos en disco y los sirve a través de HTTPS autenticado. |
| **CORS (Intercambio de recursos de origen cruzado)** | Encabezados estándar de seguridad del navegador que permiten al Visor OSCAR cargar imágenes, informes y medios retenidos desde el servidor SensorHub. |

## Referencia de configuración

- **Directorio raíz del almacén de archivos (`File Store Root Directory`)**: Carpeta del disco local donde se guardan todos los buckets y archivos.
- **Buckets iniciales (`Initial Buckets`)**: Lista de nombres de buckets que se crean automáticamente al iniciar el servicio.
- **Tamaño máximo de archivo (MB) (`Max File Size (MB)`)**: Tamaño máximo permitido (en megabytes) por archivo cargado.
- **Habilitar CORS (`Enable CORS`)**: Añade encabezados CORS para que el Visor OSCAR pueda consultar y subir archivos desde el navegador.
- **Seguridad (`Security`)**: Reglas de control de acceso que determinan qué usuarios autenticados pueden leer o subir objetos.
