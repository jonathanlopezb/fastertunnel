# fasterTunnel Android

> **Módulo de aplicación móvil** — Cliente tipo HTTP Injector para Android.
> Esta carpeta contendrá el proyecto Android (Kotlin + Jetpack Compose + JNI C++/Rust).

## Estado: En Desarrollo

El módulo de frontend se construirá en la siguiente fase del proyecto, una vez que el backend esté estable y desplegado.

## Stack Planificado

- **Lenguaje**: Kotlin + Coroutines + Flow
- **UI**: Jetpack Compose + Material 3 (tema oscuro / cyberpunk)
- **Arquitectura**: Clean Architecture + MVI + Hilt DI
- **VPN**: Android `VpnService` API (interfaz TUN virtual)
- **Motor Nativo**: C++ o Rust compilado con Android NDK (arm64-v8a, armeabi-v7a)
- **JNI Bridge**: `FasterTunnelJni.kt` ↔ `libfastertunnel.so`
- **Red**: Retrofit + OkHttp para API REST
- **Storage**: Room DB + EncryptedSharedPreferences (Jetpack Security)

## Módulos Planificados

```
frontend/
└── android/
    ├── app/
    │   ├── src/main/
    │   │   ├── java/com/fastertunnel/
    │   │   │   ├── core/vpn/          # VpnService, TunnelManager
    │   │   │   ├── core/security/     # Keystore, Ed25519 Verifier
    │   │   │   ├── core/jni/          # JNI bridge a motor nativo
    │   │   │   ├── data/api/          # Retrofit clients
    │   │   │   ├── data/local/        # Room DB
    │   │   │   ├── domain/usecase/    # Configuración pública, StartTunnel
    │   │   │   └── presentation/      # Compose Screens + ViewModels
    │   │   └── cpp/                   # Motor C++/Rust nativo
    │   └── build.gradle.kts
    └── build.gradle.kts
```

## Pantallas Principales

1. **MainTunnelScreen** — Inicio directo, botón de conexión ON/OFF, estado del túnel y latencia RTT
2. **ServerSelectSheet** — Lista de nodos por país y selector de protocolo
3. **TweakSelectSheet** — Configuraciones de payload por operadora/carrier
4. **SettingsScreen** — Kill Switch, Split Tunneling, DNS anti-leak y tema de la app
