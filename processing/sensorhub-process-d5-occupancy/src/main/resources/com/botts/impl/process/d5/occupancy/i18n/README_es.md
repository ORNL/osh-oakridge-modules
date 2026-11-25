# Proceso de ocupación Kromek D5

El **Proceso de ocupación Kromek D5** supervisa un detector móvil o carril padre equipado con un detector portátil **Kromek D5**, agrupa las señales de alarma de dosis, gamma y neutrones de 1 Hz en episodios discretos y publica cada episodio como un evento de **Ocupación** de OSCAR (incluidos conteos máximos, categoría de alarma y coordenadas GPS).

## Términos en lenguaje claro

| Término | Significado |
| --- | --- |
| **Detector portátil Kromek D5** | Detector espectroscópico portátil de rayos gamma y neutrones consultado por TCP o serie/USB que informa alarmas activas, espectros de 4.096 canales y coordenadas GPS. |
| **Episodio de alarma (`Occupancy`)** | Ventana de alarma radiológica delimitada por la activación de una alarma del D5 y su cierre tras 3 segundos sin alarma activa. |

## Referencia de configuración

- **Número de serie (`Serial Number`)**: Sufijo identificador único para esta instancia del proceso de ocupación Kromek D5.
- **Sistema padre (`Parent System (Containing RPM)`)**: UID del sistema padre que contiene el controlador Kromek D5. Déjelo en blanco para vincularlo automáticamente al sistema contenedor.
