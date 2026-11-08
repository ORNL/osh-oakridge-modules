# Autenticación básica por sesión

El módulo **Autenticación básica por sesión** autentica las solicitudes del navegador y de la API mediante una cookie de sesión opaca en el servidor tras verificar las credenciales del usuario.

## Términos en lenguaje claro

| Término | Significado |
| --- | --- |
| **Cookie de sesión opaca** | Un identificador aleatorio almacenado en el navegador después de iniciar sesión. En lugar de reenviar la contraseña en cada solicitud, el navegador envía este identificador y OSCAR valida la sesión en el servidor. |
| **Tiempo de inactividad vs. Tiempo absoluto** | El **Tiempo de inactividad** cierra la sesión tras un periodo sin actividad; el **Tiempo absoluto** exige volver a iniciar sesión tras una duración máxima total aunque haya actividad. |
| **Política SameSite** | Regla del navegador que controla si la cookie se envía en solicitudes entre sitios. `Strict` la limita al nodo local; `None` permite conexiones federadas entre múltiples nodos sobre HTTPS. |

## Referencia de configuración

- **Nombre de la cookie de sesión (`Session cookie name`)**: Nombre de la cookie que guarda el identificador de sesión.
- **Tiempo de espera por inactividad (segundos) (`Idle timeout`)**: Segundos sin actividad antes de invalidar la sesión.
- **Tiempo de espera absoluto (segundos) (`Absolute timeout`)**: Duración máxima total de una sesión antes de requerir un nuevo inicio de sesión.
- **Cookie segura (`Secure cookie`)**: Garantiza que la cookie solo se envíe por conexiones HTTPS cifradas. Manténgalo activado en producción.
- **Política SameSite (`SameSite policy`)**: Política de envío de cookie (`Strict`, `Lax` o `None`).
- **Ruta de redirección predeterminada (`Default redirect path`)**: Ruta local a la que se redirige tras iniciar sesión cuando no había una página de destino guardada.
