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

	// ── Crear usuario admin y semillas por defecto ───────────────────────
	seedDefaultAdmin()
	seedDefaultServersAndTweaks()

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

// seedDefaultServersAndTweaks carga servidores y tweaks optimizados para Movistar y Latam si la DB está vacía.
func seedDefaultServersAndTweaks() {
	gormDB := database.DB

	// Servidores Gateway
	var serverCount int64
	gormDB.Model(&models.ServerNode{}).Count(&serverCount)
	if serverCount == 0 {
		servers := []models.ServerNode{
			{
				NodeID:       "gw-aws-ohio-01",
				Name:         "🔥 AWS Ohio #1 - Gateway Principal (WS+UDP)",
				CountryCode:  "US",
				FlagEmoji:    "🇺🇸",
				Host:         "3.142.187.253", // AWS EC2 us-east-2 Gateway real
				Port:         80,
				Transport:    models.TransportWSS,
				SNIHost:      "web.whatsapp.net",
				IsActive:     true,
				IsPremium:    false,
				PingMs:       38,
				MaxUsers:     500,
				CurrentUsers: 0,
			},
			{
				NodeID:       "gw-movistar-02",
				Name:         "Latam Movistar Bypass #2 (Cloudflare WSS)",
				CountryCode:  "CO",
				FlagEmoji:    "🇨🇴",
				Host:         "104.16.132.229", // IP Anycast CDN
				Port:         443,
				Transport:    models.TransportWSS,
				SNIHost:      "web.whatsapp.net",
				IsActive:     true,
				IsPremium:    false,
				PingMs:       28,
				MaxUsers:     200,
				CurrentUsers: 22,
			},
			{
				NodeID:       "gw-de-03",
				Name:         "Alemania #3 (Stealth SSL)",
				CountryCode:  "DE",
				FlagEmoji:    "🇩🇪",
				Host:         "1.0.0.1",
				Port:         443,
				Transport:    models.TransportSSL,
				SNIHost:      "portal.movistar.com.pe",
				IsActive:     true,
				IsPremium:    false,
				PingMs:       112,
				MaxUsers:     100,
				CurrentUsers: 9,
			},
		}
		for _, s := range servers {
			gormDB.Create(&s)
		}
		log.Println("[SEED] Default gateway servers seeded successfully")
	}

	// Tweaks / Payloads
	var tweakCount int64
	gormDB.Model(&models.Tweak{}).Count(&tweakCount)
	if tweakCount == 0 {
		tweaks := []models.Tweak{
			{
				TweakID:         "movistar_whatsapp_sni",
				Label:           "Movistar WhatsApp Ilimitado (Auto-Inyección)",
				Carrier:         "Movistar",
				Country:         "LATAM",
				SNISpoof:        "web.whatsapp.net",
				PayloadTemplate: "GET / HTTP/1.1\r\nHost: [sni]\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nUser-Agent: [ua]\r\n\r\n",
				Transport:       "WSS",
				RecommendedGW:   "gw-movistar-02",
				IsActive:        true,
			},
			{
				TweakID:         "movistar_portal_ssl",
				Label:           "Movistar Portal & App (SSL Spoof)",
				Carrier:         "Movistar",
				Country:         "LATAM",
				SNISpoof:        "portal.movistar.com.pe",
				PayloadTemplate: "CONNECT [host_port] HTTP/1.1\r\nHost: [sni]\r\nX-Online-Host: [sni]\r\nConnection: Keep-Alive\r\n\r\n",
				Transport:       "SSL",
				RecommendedGW:   "gw-de-03",
				IsActive:        true,
			},
			{
				TweakID:         "movistar_recargas_co",
				Label:           "Movistar Colombia (Recargas / WSS)",
				Carrier:         "Movistar",
				Country:         "CO",
				SNISpoof:        "recargas.movistar.com.co",
				PayloadTemplate: "GET / HTTP/1.1\r\nHost: [sni]\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\n",
				Transport:       "WSS",
				RecommendedGW:   "gw-movistar-02",
				IsActive:        true,
			},
			{
				TweakID:         "movistar_dns_udp",
				Label:           "Movistar DNS UDP 53 (Bypass Filtro)",
				Carrier:         "Movistar",
				Country:         "LATAM",
				SNISpoof:        "",
				PayloadTemplate: "",
				Transport:       "UDP_CUSTOM",
				RecommendedGW:   "gw-us-01",
				IsActive:        true,
			},
		}
		for _, t := range tweaks {
			gormDB.Create(&t)
		}
		log.Println("[SEED] Default Movistar tweaks seeded successfully")
	}
}
