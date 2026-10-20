package handlers

import (
	"net/http"

	"github.com/gin-gonic/gin"
	"github.com/jonathanlopezb/fastertunnel/internal/services"
	"github.com/jonathanlopezb/fastertunnel/pkg/response"
)

// AuthHandler agrupa los endpoints de autenticación.
type AuthHandler struct {
	authService *services.AuthService
}

// NewAuthHandler crea un nuevo AuthHandler.
func NewAuthHandler(authService *services.AuthService) *AuthHandler {
	return &AuthHandler{authService: authService}
}

// loginInput es el body de la petición POST /auth/login.
type loginInput struct {
	Username string `json:"username" binding:"required"`
	Password string `json:"password" binding:"required"`
	DeviceID string `json:"device_id" binding:"required"`
	Platform string `json:"platform"`
}

// Login maneja el inicio de sesión.
// POST /api/v1/auth/login
func (h *AuthHandler) Login(c *gin.Context) {
	var input loginInput
	if err := c.ShouldBindJSON(&input); err != nil {
		response.BadRequest(c, "invalid request body", err.Error())
		return
	}

	req := &services.LoginRequest{
		Username: input.Username,
		Password: input.Password,
		DeviceID: input.DeviceID,
		Platform: input.Platform,
		ClientIP: c.ClientIP(),
	}

	result, err := h.authService.Login(req)
	if err != nil {
		response.Unauthorized(c, err.Error())
		return
	}

	response.OK(c, "login successful", result)
}

// Logout cierra la sesión actual.
// POST /api/v1/auth/logout
func (h *AuthHandler) Logout(c *gin.Context) {
	sessionID, _ := c.Get("session_id")
	if err := h.authService.Logout(sessionID.(string)); err != nil {
		response.InternalError(c, "failed to logout")
		return
	}
	response.OK(c, "logout successful", nil)
}

// heartbeatInput es el body del heartbeat.
type heartbeatInput struct {
	SessionID string `json:"session_id" binding:"required"`
	GatewayID string `json:"gateway_id"`
}

// Heartbeat actualiza la actividad de la sesión y detecta preemption.
// POST /api/v1/auth/heartbeat
func (h *AuthHandler) Heartbeat(c *gin.Context) {
	var input heartbeatInput
	if err := c.ShouldBindJSON(&input); err != nil {
		response.BadRequest(c, "invalid request body", err.Error())
		return
	}

	result, err := h.authService.Heartbeat(input.SessionID, input.GatewayID)
	if err != nil {
		// Sesión inválida o terminada — el cliente debe cerrar el túnel.
		c.JSON(http.StatusUnauthorized, response.Response{
			Success: false,
			Message: result.Status,
			Error:   err.Error(),
		})
		return
	}

	response.OK(c, "heartbeat ok", result)
}
