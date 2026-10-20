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
) {
	// Inicializar handlers.
	authHandler := handlers.NewAuthHandler(authService)
	serverHandler := handlers.NewServerHandler(configService)
	tweakHandler := handlers.NewTweakHandler(configService)

	// Middleware global.
	r.Use(middleware.CORS())

	// Grupo base de la API v1.
	v1 := r.Group("/api/v1")
	{
		// ── Endpoints públicos ──────────────────────────────────────────────
		v1.GET("/health", handlers.HealthCheck)
		v1.POST("/auth/login", authHandler.Login)

		// ── Endpoints protegidos (requieren JWT válido y sesión activa) ─────
		auth := v1.Group("/")
		auth.Use(middleware.AuthRequired(jwtSecret))
		{
			auth.POST("/auth/logout", authHandler.Logout)
			auth.POST("/auth/heartbeat", authHandler.Heartbeat)

			auth.GET("/servers", serverHandler.GetServers)
			auth.GET("/servers/ping", serverHandler.GetServersPing)

			auth.GET("/tweaks", tweakHandler.GetTweaks)
		}
	}
}
