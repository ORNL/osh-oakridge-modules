# Proceso de datos de ocupación

El **Proceso de datos de ocupación** supervisa el flujo del monitor de pórtico de radiación (RPM) de un carril, agrupa cada paso de vehículo o peatón en un registro estructurado de **Ocupación** (con hora de inicio, hora de fin, indicadores de alarma gamma/neutrones, conteos máximos y fotogramas/clips de cámara asociados) y activa la captura de medios retenidos cuando ocurre una alarma.

## Términos en lenguaje claro

| Término | Significado |
| --- | --- |
| **Ocupación (`Occupancy`)** | Un único paso de un vehículo, contenedor o persona por un carril de detección entre el inicio (`GS`) y el fin (`GX`) de ocupación. |
| **UUID de evento (`Event UUID`)** | Identificador único global asignado a cada evento de ocupación para buscarlo, adjudicarlo y referenciarlo en informes y nodos federados. |
| **UID del sistema padre (`Parent System UID`)** | Identificador único (`urn:osh:system:lane:...`) del sistema de carril que agrupa el RPM y las cámaras. |

## Referencia de configuración

- **Número de serie (`Serial Number`)**: Sufijo identificador único para esta instancia del proceso de ocupación (normalmente coincide con el identificador del carril).
- **Sistema padre (`Parent System (Containing RPM)`)**: UID del sistema de carril que contiene el controlador RPM y las cámaras. Si se deja en blanco, utiliza automáticamente su sistema de carril contenedor.
