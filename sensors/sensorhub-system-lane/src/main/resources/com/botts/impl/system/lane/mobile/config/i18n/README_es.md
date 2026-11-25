# Sistema de detector móvil

Despliega un detector de radiación móvil — una **mochila RS-350** o un detector portátil **Kromek D5** — como sistema rastreable en OSCAR. Utilice este tipo de módulo en lugar de los controladores individuales RS-350/D5 o de un **Sistema de carril** fijo (que expone opciones de cámaras, WebRelay y OCR de vehículos que no se aplican a una unidad móvil).

El detector aparece en OSCAR Viewer con seguimiento GPS en tiempo real sobre el mapa, marcadores de alarma, identificación espectroscópica de isótopos (`WebID` para RS-350) y adjudicación de episodios de alarma en la tabla de eventos.

## Términos en lenguaje claro

| Término | Significado |
| --- | --- |
| **Mochila RS-350** | Detector espectroscópico portátil de rayos gamma y neutrones que transmite espectros `N42`, alarmas y posiciones GPS por TCP. |
| **Kromek D5 portátil** | Detector espectroscópico de mano consultado a 1 Hz por TCP para obtener estado radiométrico, espectros de 4.096 canales y posiciones GPS. |
| **Conexión del detector (`Detector Connection`)** | Dirección TCP y puerto únicos del detector móvil, sincronizados automáticamente con el controlador secundario. |

## Referencia de configuración

- **Nombre del módulo (`Module Name`)**: Nombre visible del detector móvil (máximo 12 caracteres).
- **Identificador único (`Unique ID`)**: Sufijo identificador único (por ejemplo `backpack-01`), asignado a `urn:osh:system:lane:<uniqueID>`.
- **Claves de vista operativa (`Operational View Keys`)**: Etiquetas opcionales en minúsculas para delimitar qué operadores ven esta unidad móvil.
- **Conexión del detector (`Detector Connection`)**: Seleccione **Mochila RS-350** o **Kromek D5** e indique **Host remoto** y **Puerto remoto**.
