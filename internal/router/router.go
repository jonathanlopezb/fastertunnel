package router

import (
	"github.com/gin-gonic/gin"
	"github.com/jonathanlopezb/fastertunnel/internal/handlers"
	"github.com/jonathanlopezb/fastertunnel/internal/middleware"
	"github.com/jonathanlopezb/fastertunnel/internal/services"
)

// Setup configura todas las rutas del servidor fasterTunnel.
func Setup(
	r *gin.Engine,
	authService *services.AuthService,
	configService *services.ConfigService,
	jwtSecret string,
	wireGuardAgentToken string,
) {
	// Inicializar handlers.
	authHandler := handlers.NewAuthHandler(authService)
	serverHandler := handlers.NewServerHandler(configService)
	tweakHandler := handlers.NewTweakHandler(configService)
	wireGuardHandler := handlers.NewWireGuardHandler(wireGuardAgentToken)

	// Middleware global.
	r.Use(middleware.CORS())

	// Grupo base de la API v1.
	v1 := r.Group("/api/v1")
	{
		// ── Endpoints públicos ──────────────────────────────────────────────
		v1.GET("/health", handlers.HealthCheck)
		v1.POST("/auth/login", authHandler.Login)
		v1.GET("/servers", serverHandler.GetServers)
		v1.GET("/tweaks", tweakHandler.GetTweaks)
		v1.POST("/wireguard/profile", wireGuardHandler.CreateProfile)

		// ── Endpoints protegidos (requieren JWT válido y sesión activa) ─────
		auth := v1.Group("/")
		auth.Use(middleware.AuthRequired(jwtSecret))
		{
			auth.POST("/auth/logout", authHandler.Logout)
			auth.POST("/auth/heartbeat", authHandler.Heartbeat)

			auth.GET("/servers/ping", serverHandler.GetServersPing)
		}

		agent := v1.Group("/wireguard")
		agent.Use(wireGuardHandler.AgentAuth())
		agent.GET("/peers", wireGuardHandler.GetPeers)
	}
}
