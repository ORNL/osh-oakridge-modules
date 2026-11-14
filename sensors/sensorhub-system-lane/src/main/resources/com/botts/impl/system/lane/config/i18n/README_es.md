# Sistema de sensores de carril

El **Sistema de sensores de carril** crea un contenedor principal para un monitor de pórtico de radiación (RPM), su proceso de ocupación y hasta cuatro cámaras de carril.

## Términos en lenguaje claro

| Término | Significado |
| --- | --- |
| **Clave de vista operativa (`Operational View Key`)** | Etiqueta opcional (por ejemplo, `north-gate` o `cargo-inbound`) que agrupa este carril en una vista de trabajo para operadores en el Visor OSCAR. |
| **Aprendizaje automático mejorado (`EML`, Enhanced Machine Learning)** | Algoritmo opcional para Rapiscan que compara conteos gamma de baja, media y alta energía para distinguir radiación natural de carga (**NORM**, auto-adjudicada como `Code 9999: EML Non-Threat`) frente a fuentes de amenaza. |
| **OCR de contenedores y matrículas (`ocrConfig`)** | Reconocimiento óptico opcional por carril (`enabled = false` de forma predeterminada) que lee de forma asíncrona códigos de contenedor ISO 6346 (con dígito de control) y matrículas según lo permitan los recursos de CPU y memoria, publicando sugerencias `vehicleOcr` y autocompletando **ID del vehículo** al adjudicar. |
| **Rango de direcciones Modbus** | Rango de registros (`Desde` y `Hasta`) utilizado por los controladores Aspect sobre TCP/IP. |

## Configuración general

- **Nombre del módulo** e **ID único de carril**: Asigne al carril un nombre visible único y un identificador único.
- **Clave de vista operativa**: Clave opcional para asignar el carril a una vista operativa definida en el Servicio OSCAR.
- **OCR de contenedores y matrículas**: Ajuste opcional por carril (`Habilitar OCR de contenedores / matrículas`, `Fotogramas por vídeo`, `Confianza mínima`, `Exigir dígito de control ISO 6346`, `Reconocer matrículas`, `Guardar recorte de evidencia`, `Directorio de modelos`). Desactivado de forma predeterminada sin sobrecarga cuando está apagado.
- **Ubicación**: Introduzca la latitud y longitud fijas del carril (o coloque el marcador en el plano del sitio).
- **Eliminar datos al quitar el carril**: Actívelo **solo** cuando eliminar el carril también deba borrar sus registros históricos almacenados.

## Opciones del carril

- **Configuración inicial de RPM**:
  - Seleccione **Rapiscan**, **Aspect** o **RS-350** e introduzca el host/IP y el puerto TCP.
  - Para **Aspect**, indique también el rango de direcciones Modbus.
  - Para **Rapiscan**, configure **EML** si el nodo tiene cargado un complemento `.jar` de EML.
- **Configuración inicial de cámaras**:
  - Añada hasta **4** cámaras **Sony**, **Axis** o **Custom** por carril.
  - Para cámaras **Axis**, seleccione el códec de transmisión (`H.264`). Para cámaras **Custom**, introduzca la ruta RTSP/HTTP posterior al host y puerto.
- Haga clic en **Aplicar** y **Guardar** en la barra superior para crear e iniciar los módulos hijos.
