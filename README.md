# fasterTunnel

<p align="center">
  <img src="logo.png" alt="fasterTunnel Logo" width="180"/>
</p>

<p align="center">
  <strong>Cliente de túnel tipo HTTP Injector de alto rendimiento</strong><br/>
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
| `GET` | `/api/v1/servers` | Lista de nodos gateway disponibles |
| `GET` | `/api/v1/tweaks` | Configuraciones de payload por operadora |

### Protegidos (requieren `Authorization: Bearer <token>`)

| Método | Ruta | Descripción |
|--------|------|-------------|
| `POST` | `/api/v1/auth/logout` | Cerrar sesión |
| `POST` | `/api/v1/auth/heartbeat` | Mantener sesión viva (cada 15s) |
| `GET` | `/api/v1/servers/ping` | Latencia estimada por servidor |

## Despliegue en Render

1. Conecta el repositorio `github.com/jonathanlopezb/fastertunnel` en [render.com](https://render.com)
2. Crea un nuevo **Web Service** con:
   - **Runtime**: Docker
   - **Dockerfile path**: `backend/Dockerfile`
   - **Docker context**: `.` (raíz del repo)
3. Agrega las variables de entorno en el dashboard de Render
4. Crea una base de datos **PostgreSQL** en Render y copia la URL interna a `DATABASE_URL`

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
