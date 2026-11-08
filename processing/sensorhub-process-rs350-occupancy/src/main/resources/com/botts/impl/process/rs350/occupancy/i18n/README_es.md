# Proceso de ocupación RS350

El **Proceso de ocupación RS350** supervisa un carril equipado con un detector de radiación espectroscópico **RS-350**, empaqueta cada ventana de ocupación o alarma espectroscópica en un evento de **Ocupación** de OSCAR (incluidos espectros de primer plano/fondo y análisis de isótopos) y coordina la captura de vídeo/fotogramas retenidos en el carril padre.

## Términos en lenguaje claro

| Término | Significado |
| --- | --- |
| **Detector espectroscópico RS-350** | Detector gamma/neutrones que genera espectros completos de energía (formato `N42`) además de tasas de conteo bruto, lo que permite la identificación automática de isótopos (`WebID`). |
| **Ocupación (`Occupancy`)** | Un paso o ventana de medición delimitada por el inicio y el fin de ocupación en el carril. |

## Referencia de configuración

- **Número de serie (`Serial Number`)**: Sufijo identificador único para esta instancia del proceso de ocupación RS-350.
- **Sistema padre (`Parent System (Containing RPM)`)**: UID del sistema de carril padre que contiene el controlador RS-350 y las cámaras. Déjelo en blanco para vincularlo automáticamente al carril contenedor.
