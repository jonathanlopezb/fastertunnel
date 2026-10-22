# fasterTunnel

<p align="center">
  <img src="logo.png" alt="fasterTunnel Logo" width="180"/>
</p>

<p align="center">
  <strong>Cliente VPN WireGuard para Android y API de perfiles</strong><br/>
  Backend en Go · App Android en Kotlin/Jetpack Compose · PostgreSQL · Docker
</p>

---

## Arquitectura del Proyecto

```
fastertunnel/
├── backend/           # API REST en Go (Gin + GORM + PostgreSQL)
│   ├── Dockerfile
│   ├── docker-compose.yml
│   └── .env.example
├── frontend/          # App Android (Kotlin + Jetpack Compose)
│   └── (próximamente)
├── cmd/               # Entrypoint del servidor Go
│   └── wg-agent/      # Sincronizador de peers para el gateway WireGuard
├── internal/          # Lógica interna (handlers, services, models)
├── pkg/               # Paquetes reutilizables (crypto, response)
├── go.mod
├── go.sum
└── logo.png
```

## Stack Tecnológico

| Capa | Tecnología |
|------|-----------|
| Backend API | Go 1.27 + Gin Framework |
| Base de Datos | PostgreSQL 16 |
| ORM | GORM v2 |
| Autenticación | JWT (golang-jwt/v5) + Argon2id |
| Firma de Configs | Ed25519 |
| Contenedores | Docker + Docker Compose |
| Deploy | Render (backend) |
| App Móvil | Android (Kotlin + Jetpack Compose) |

## Inicio Rápido

### Con Docker Compose

```bash
cd backend
cp .env.example .env
# Edita .env con tus valores reales
docker compose up --build
```

La API estará disponible en `http://localhost:8080`.

### Verificar que funciona

```bash
curl http://localhost:8080/api/v1/health
```

Respuesta esperada:
```json
{
  "success": true,
  "message": "faster bienvenido",
  "data": {
    "status": "online",
    "version": "1.0.0",
    "service": "fasterTunnel API"
  }
}
```

### Sin Docker (local)

```bash
# Copia y configura variables de entorno
cp backend/.env.example .env

# Descarga dependencias
go mod tidy

# Compila y ejecuta
go run cmd/server/main.go
```

## Variables de Entorno

| Variable | Descripción | Default |
|----------|-------------|---------|
| `PORT` | Puerto del servidor HTTP | `8080` |
| `DATABASE_URL` | URL de conexión PostgreSQL | - |
| `JWT_SECRET` | Clave secreta para firmar JWTs | - |
| `JWT_EXPIRY` | Expiración de token en horas | `24` |
| `ENV` | Entorno (`development`/`production`) | `development` |

## Endpoints de la API

### Públicos

| Método | Ruta | Descripción |
|--------|------|-------------|
| `GET` | `/api/v1/health` | Health check - verifica que el servidor está online |
| `POST` | `/api/v1/auth/login` | Inicio de sesión (1 sesión activa por usuario) |
| `GET` | `/api/v1/servers` | Lista legacy de nodos de transporte; no son perfiles WireGuard |
| `GET` | `/api/v1/tweaks` | Configuraciones legacy; no las usa el cliente WireGuard |
| `POST` | `/api/v1/wireguard/profile` | Registra una clave pública y devuelve perfil WireGuard |

### Protegidos (requieren `Authorization: Bearer <token>`)

| Método | Ruta | Descripción |
|--------|------|-------------|
| `POST` | `/api/v1/auth/logout` | Cerrar sesión |
| `POST` | `/api/v1/auth/heartbeat` | Mantener sesión viva (cada 15s) |
| `GET` | `/api/v1/servers/ping` | Latencia estimada por servidor |
| `GET` | `/api/v1/wireguard/peers` | Lista peers activos; requiere el token privado del agente del gateway |

## Despliegue en Render

1. Conecta el repositorio `github.com/jonathanlopezb/fastertunnel` en [render.com](https://render.com)
2. Crea un nuevo **Web Service** con:
   - **Runtime**: Docker
   - **Dockerfile path**: `backend/Dockerfile`
   - **Docker context**: `.` (raíz del repo)
3. Agrega las variables de entorno en el dashboard de Render
4. Crea una base de datos **PostgreSQL** en Render y copia la URL interna a `DATABASE_URL`
5. Configura estas variables privadas del Web Service:
   - `WIREGUARD_ENDPOINT`: endpoint UDP, por ejemplo `3.142.187.253:51820`
   - `WIREGUARD_SERVER_PUBLIC_KEY`: salida de `sudo wg show wg0 public-key`
   - `WIREGUARD_AGENT_TOKEN`: token aleatorio de al menos 32 caracteres; debe coincidir con el configurado en el gateway

El endpoint de creación de perfiles valida que esté configurada la clave pública del gateway y el token del agente. No pongas claves privadas WireGuard en Render, la app Android ni Git.

## Sincronizador del gateway WireGuard

La API persiste solo la clave pública del dispositivo. En el gateway, `fastertunnel-wg-agent` consulta por HTTPS los peers activos con `WIREGUARD_AGENT_TOKEN`, instala y revoca peers en `wg0`. Las claves privadas nunca salen del dispositivo.

Antes de instalar el agente, configura el gateway con `wg0` en `10.66.0.1/24`, forwarding IPv4 y NAT de `10.66.0.0/24` hacia su interfaz de salida. La dirección `10.66.0.2` queda reservada para el perfil de cliente anterior; la asignación nueva usa `10.66.0.10` a `10.66.0.254`.

El workflow de GitHub Actions ejecuta las pruebas Go y publica el binario `fastertunnel-wg-agent-linux-amd64` junto al APK. Descarga ese artifact, además de `fastertunnel-debug-apk`.

Genera un token local aleatorio con `openssl rand -hex 32`. Configura el mismo valor en `WIREGUARD_AGENT_TOKEN` en Render y en el gateway; no lo guardes en Git. Crea `/etc/fastertunnel/wg-agent.env` en AWS con permisos `0600`:

```text
FASTATUNNEL_API_URL=https://fastertunnel-api.onrender.com
WIREGUARD_AGENT_TOKEN=el-mismo-token-aleatorio-configurado-en-Render
```

Copia el binario descargado como `/tmp/fastertunnel-wg-agent`, y `deploy/fastertunnel-wg-agent.service` y `deploy/install_wireguard_agent.sh` como `/tmp/fastertunnel-wg-agent.service` y `/tmp/install_wireguard_agent.sh`, respectivamente. Ejecuta `sudo bash /tmp/install_wireguard_agent.sh`. En AWS, permite **UDP 51820** en el Security Group. Si el dominio se usa como endpoint, el registro de Cloudflare debe estar en DNS-only.

Sin esas variables, el sincronizador activo y la regla de AWS, la app no podrá obtener un túnel funcional aunque la API de salud responda `online`.

## Credenciales por Defecto

Al iniciar el servidor por primera vez se crea automáticamente:

| Campo | Valor |
|-------|-------|
| Usuario | `admin` |
| Contraseña | `fastertunnel2024` |

> ⚠️ **Cambia la contraseña inmediatamente en producción.**

## Seguridad

- **Contraseñas**: Hasheadas con Argon2id (64MB, 3 iteraciones, paralelismo 2)
- **Tokens**: JWT firmados con HS256, expiración configurable
- **Sesiones concurrentes**: Solo 1 sesión activa por usuario (preemption automático)
- **Heartbeat**: Las sesiones expiran en 45s sin heartbeat (anti-zombie)
- **Configuraciones**: Firmadas con Ed25519 (verificables offline en el cliente)

## Arquitectura de Sesión Única

```
Usuario inicia sesión en Dispositivo B
        ↓
Backend invalida sesión del Dispositivo A (TERMINATED)
        ↓
Dispositivo A: próximo heartbeat → HTTP 401 "session_preempted"
        ↓
App Android cierra el túnel y muestra alerta
```

---

© 2026 fasterTunnel · Jonathan López
