package config

import (
	"os"
	"strconv"
)

// Config contiene toda la configuración del servidor cargada desde variables de entorno.
type Config struct {
	Port        string
	DatabaseURL string
	JWTSecret   string
	JWTExpiry   int    // Duración del token en horas
	Env         string // "development" o "production"
}

// Load carga la configuración desde variables de entorno con valores por defecto seguros.
func Load() *Config {
	expiry := 24
	if v := os.Getenv("JWT_EXPIRY"); v != "" {
		if parsed, err := strconv.Atoi(v); err == nil && parsed > 0 {
			expiry = parsed
		}
	}

	env := os.Getenv("ENV")
	if env == "" {
		env = "development"
	}

	port := os.Getenv("PORT")
	if port == "" {
		port = "8080"
	}

	return &Config{
		Port:        port,
		DatabaseURL: os.Getenv("DATABASE_URL"),
		JWTSecret:   os.Getenv("JWT_SECRET"),
		JWTExpiry:   expiry,
		Env:         env,
	}
}
