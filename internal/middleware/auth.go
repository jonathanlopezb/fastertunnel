package middleware

import (
	"net/http"
	"strings"

	"github.com/gin-gonic/gin"
	"github.com/golang-jwt/jwt/v5"
	"github.com/jonathanlopezb/fastertunnel/internal/database"
	"github.com/jonathanlopezb/fastertunnel/internal/models"
	"github.com/jonathanlopezb/fastertunnel/pkg/response"
)

// JWTClaims define los claims personalizados del token JWT de fasterTunnel.
type JWTClaims struct {
	UserID    uint   `json:"user_id"`
	Username  string `json:"username"`
	SessionID string `json:"session_id"`
	DeviceID  string `json:"device_id"`
	IsAdmin   bool   `json:"is_admin"`
	jwt.RegisteredClaims
}

// AuthRequired es el middleware que valida el JWT y verifica que la sesión esté activa en DB.
// Si la sesión fue reemplazada por otro dispositivo (preemption), retorna 401.
func AuthRequired(jwtSecret string) gin.HandlerFunc {
	return func(c *gin.Context) {
		authHeader := c.GetHeader("Authorization")
		if authHeader == "" {
			response.Unauthorized(c, "authorization header is required")
			c.Abort()
			return
		}

		parts := strings.SplitN(authHeader, " ", 2)
		if len(parts) != 2 || !strings.EqualFold(parts[0], "Bearer") {
			response.Unauthorized(c, "authorization header format must be 'Bearer <token>'")
			c.Abort()
			return
		}

		tokenStr := parts[1]
		claims := &JWTClaims{}

		token, err := jwt.ParseWithClaims(tokenStr, claims, func(token *jwt.Token) (interface{}, error) {
			if _, ok := token.Method.(*jwt.SigningMethodHMAC); !ok {
				return nil, jwt.ErrSignatureInvalid
			}
			return []byte(jwtSecret), nil
		})

		if err != nil || !token.Valid {
			response.Unauthorized(c, "invalid or expired token")
			c.Abort()
			return
		}

		// Verificar en DB que la sesión siga activa (detecta preemption en tiempo real).
		var session models.Session
		result := database.DB.Where(
			"session_id = ? AND status = ?",
			claims.SessionID,
			models.SessionActive,
		).First(&session)

		if result.Error != nil {
			// La sesión fue terminada (preemption o logout) — forzar cierre del túnel.
			c.JSON(http.StatusUnauthorized, response.Response{
				Success: false,
				Message: "session_preempted",
				Error:   "your session was terminated because the account was accessed from another device",
			})
			c.Abort()
			return
		}

		// Inyectar datos en el contexto para los handlers posteriores.
		c.Set("user_id", claims.UserID)
		c.Set("username", claims.Username)
		c.Set("session_id", claims.SessionID)
		c.Set("device_id", claims.DeviceID)
		c.Set("is_admin", claims.IsAdmin)
		c.Set("session", session)

		c.Next()
	}
}

// AdminRequired verifica que el usuario autenticado tenga permisos de administrador.
func AdminRequired() gin.HandlerFunc {
	return func(c *gin.Context) {
		isAdmin, exists := c.Get("is_admin")
		if !exists || !isAdmin.(bool) {
			response.Forbidden(c, "admin privileges required")
			c.Abort()
			return
		}
		c.Next()
	}
}
