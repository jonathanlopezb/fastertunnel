# fasterTunnel Android

Cliente Android de VPN con conexión WireGuard, interfaz en Jetpack Compose y sin inicio de sesión.

## Requisitos de conexión

- Android 8.0 (API 26) o superior.
- Un servidor WireGuard activo y accesible por UDP 51820.
- El backend y el sincronizador WireGuard del gateway configurados como se describe en `../README.md`.

Al tocar conectar por primera vez, Android solicita el permiso VPN y la app pide un perfil al backend. La clave privada WireGuard se genera y permanece en el almacenamiento privado de la app; solo se envía la clave pública para registrar el peer. Los perfiles `.conf` también se pueden importar manualmente. El estado conectado solo aparece después de verificar el túnel y una salida HTTPS real.

El endpoint WireGuard debe ser una IP pública o un nombre DNS directo al servidor. El proxy HTTP de Cloudflare no transporta el UDP de WireGuard; para usar un nombre DNS hay que configurarlo como DNS-only.

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
