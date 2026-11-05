# PantallaTV

Muestra la pantalla de un móvil Android en el televisor a través del navegador del Fire TV Stick, por la red del propio móvil.

- **Móvil:** app Android (captura y codifica en H.264).
- **Televisor:** nada que instalar. La app sirve la página y el Stick la abre en Silk.

## Descargar la app

Cada cambio en `main` compila el APK. Una vez compilado, abre esto en el navegador del móvil, cambiando `USUARIO` y `REPOSITORIO`:

`https://github.com/USUARIO/REPOSITORIO/releases/latest/download/PantallaTV.apk`

## Límites de esta versión

- Un solo móvil a la vez.
- Sin sonido en el televisor: se oye en el móvil.
- El retardo depende de Silk y hay que medirlo.
- Cada compilación se firma con una clave temporal: para actualizar hay que desinstalar la versión anterior.
