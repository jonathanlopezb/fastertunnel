package main

import (
	"fmt"
	"log"

	"github.com/gin-gonic/gin"
	"github.com/jonathanlopezb/fastertunnel/internal/config"
	"github.com/jonathanlopezb/fastertunnel/internal/database"
	"github.com/jonathanlopezb/fastertunnel/internal/models"
	"github.com/jonathanlopezb/fastertunnel/internal/router"
	"github.com/jonathanlopezb/fastertunnel/internal/services"
	argon2crypto "github.com/jonathanlopezb/fastertunnel/pkg/crypto"
)

func main() {
	// ── Cargar configuración desde variables de entorno ──────────────────
	cfg := config.Load()

	if cfg.JWTSecret == "" {
		log.Fatal("[CONFIG] JWT_SECRET env var is required")
	}

	// ── Conectar a PostgreSQL ─────────────────────────────────────────────
	db, err := database.Connect(cfg.DatabaseURL)
	if err != nil {
		log.Fatalf("[DB] Connection failed: %v", err)
	}
	_ = db

	// ── Auto-migrar modelos ───────────────────────────────────────────────
	if err := database.Migrate(db); err != nil {
		log.Fatalf("[DB] Migration failed: %v", err)
	}

	// ── Crear usuario admin por defecto si no existe ──────────────────────
	seedDefaultAdmin()

	// ── Inicializar servicios ─────────────────────────────────────────────
	authService := services.NewAuthService(cfg.JWTSecret, cfg.JWTExpiry)

	configService, err := services.NewConfigService()
	if err != nil {
		log.Fatalf("[CONFIG_SERVICE] Failed to initialize: %v", err)
	}

	log.Printf("[CONFIG_SERVICE] Ed25519 public key: %s", configService.PublicKeyBase64())

	// ── Configurar Gin ────────────────────────────────────────────────────
	if cfg.Env == "production" {
		gin.SetMode(gin.ReleaseMode)
	}

	r := gin.New()
	r.Use(gin.Recovery())

	// ── Registrar rutas ───────────────────────────────────────────────────
	router.Setup(r, authService, configService, cfg.JWTSecret)

	// ── Iniciar servidor ──────────────────────────────────────────────────
	addr := fmt.Sprintf(":%s", cfg.Port)
	log.Printf("[SERVER] fasterTunnel API listening on %s (env=%s)", addr, cfg.Env)
	if err := r.Run(addr); err != nil {
		log.Fatalf("[SERVER] Failed to start: %v", err)
	}
}

// seedDefaultAdmin crea el usuario administrador si no existe en la base de datos.
func seedDefaultAdmin() {
	gormDB := database.DB

	var count int64
	gormDB.Model(&models.User{}).Where("username = ?", "admin").Count(&count)
	if count > 0 {
		log.Println("[SEED] Admin user already exists, skipping")
		return
	}

	hash, err := argon2crypto.HashPassword("fastertunnel2024")
	if err != nil {
		log.Printf("[SEED] Failed to hash admin password: %v", err)
		return
	}

	admin := models.User{
		Username:     "admin",
		PasswordHash: hash,
		IsActive:     true,
		IsAdmin:      true,
	}

	if err := gormDB.Create(&admin).Error; err != nil {
		log.Printf("[SEED] Failed to create admin user: %v", err)
		return
	}

	log.Println("[SEED] Default admin user created: admin / fastertunnel2024")
	log.Println("[SEED] ⚠️  Change the admin password immediately in production!")
}
