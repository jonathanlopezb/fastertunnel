# fasterTunnel Android

Cliente Android de VPN con conexión WireGuard, interfaz en Jetpack Compose y sin inicio de sesión.

## Requisitos de conexión

- Android 8.0 (API 26) o superior.
- Un servidor WireGuard activo y accesible por UDP.
- Un perfil de cliente `.conf` válido, con `AllowedIPs = 0.0.0.0/0`.
- El endpoint del perfil debe ser una IP pública o un nombre DNS que resuelva directamente al servidor. Los registros de WireGuard no pueden pasar por el proxy HTTP de Cloudflare.

La app importa el archivo `.conf` desde el almacenamiento del dispositivo y conserva el permiso de lectura de ese archivo. No copia su clave privada a las preferencias ni la imprime en los registros. El botón de conexión solicita el permiso VPN de Android, activa el túnel y comprueba acceso HTTPS real antes de mostrar el estado conectado.

## Compilar localmente

Se requiere JDK 17, Android SDK Platform 35 y Gradle 8.10.2:

```powershell
cd frontend\android
gradle --no-daemon assembleDebug
```

El APK de depuración queda en `app\build\outputs\apk\debug\app-debug.apk`.

## Descargar desde GitHub Actions

Cada `push` a `main`, pull request hacia `main` o ejecución manual del workflow **Build fasterTunnel Android APK** compila el APK y publica el artifact `fastertunnel-debug-apk` por 14 días. En GitHub, abre **Actions**, selecciona la ejecución exitosa y descarga el artifact.

Los perfiles WireGuard y las claves privadas no deben añadirse al repositorio ni al artifact de compilación.
