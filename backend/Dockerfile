# ─────────────────────────────────────────────────────────────────────────────
#  fasterTunnel Backend — Dockerfile multi-etapa
#  Build context: raíz del repositorio (no desde /backend)
#  Comando: docker build -f backend/Dockerfile -t fastertunnel-api .
# ─────────────────────────────────────────────────────────────────────────────

# ── Etapa 1: Builder ──────────────────────────────────────────────────────────
FROM golang:1.27-alpine AS builder

# Herramientas necesarias para compilar con CGO desactivado.
RUN apk add --no-cache git ca-certificates tzdata

WORKDIR /app

# Copiar archivos de módulos primero para aprovechar el caché de capas de Docker.
COPY go.mod go.sum ./
RUN go mod download

# Copiar el código fuente completo.
COPY cmd/    ./cmd/
COPY internal/ ./internal/
COPY pkg/    ./pkg/

# Compilar el binario estático.
RUN CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build \
    -a -installsuffix cgo \
    -ldflags="-w -s" \
    -o fastertunnel \
    ./cmd/server

# ── Etapa 2: Imagen final mínima ──────────────────────────────────────────────
FROM alpine:3.20

RUN apk --no-cache add ca-certificates tzdata

# Usuario no privilegiado para seguridad en contenedor.
RUN addgroup -g 1001 -S appgroup && \
    adduser  -u 1001 -S appuser -G appgroup

WORKDIR /app

COPY --from=builder /app/fastertunnel .

# El binario pertenece al usuario no privilegiado.
RUN chown appuser:appgroup /app/fastertunnel
USER appuser

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=10s --start-period=5s --retries=3 \
    CMD wget -qO- http://localhost:8080/api/v1/health || exit 1

ENTRYPOINT ["./fastertunnel"]
