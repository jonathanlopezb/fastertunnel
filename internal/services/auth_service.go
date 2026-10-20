package services

import (
	"errors"
	"fmt"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"github.com/google/uuid"
	"github.com/jonathanlopezb/fastertunnel/internal/database"
	"github.com/jonathanlopezb/fastertunnel/internal/middleware"
	"github.com/jonathanlopezb/fastertunnel/internal/models"
	argon2crypto "github.com/jonathanlopezb/fastertunnel/pkg/crypto"
)

// AuthService contiene la lógica de negocio de autenticación.
type AuthService struct {
	jwtSecret string
	jwtExpiry int // horas
}

// NewAuthService crea una nueva instancia del servicio de autenticación.
func NewAuthService(jwtSecret string, jwtExpiry int) *AuthService {
	return &AuthService{
		jwtSecret: jwtSecret,
		jwtExpiry: jwtExpiry,
	}
}

// LoginRequest es el DTO de entrada para el login.
type LoginRequest struct {
	Username string `json:"username" binding:"required"`
	Password string `json:"password" binding:"required"`
	DeviceID string `json:"device_id" binding:"required"`
	Platform string `json:"platform"`
	ClientIP string `json:"-"`
}

// LoginResponse es el DTO de respuesta tras un login exitoso.
type LoginResponse struct {
	Token     string    `json:"token"`
	SessionID string    `json:"session_id"`
	ExpiresIn int       `json:"expires_in"`
	ExpiresAt time.Time `json:"expires_at"`
	User      UserDTO   `json:"user"`
}

// UserDTO es la representación pública de un usuario.
type UserDTO struct {
	ID       uint   `json:"id"`
	Username string `json:"username"`
	IsAdmin  bool   `json:"is_admin"`
}

// HeartbeatResponse es la respuesta al ping de sesión del cliente.
type HeartbeatResponse struct {
	Status string `json:"status"`
	Valid  bool   `json:"valid"`
}

// Login autentica al usuario y crea una nueva sesión activa.
// Si ya existe una sesión activa, la termina (preemption).
func (s *AuthService) Login(req *LoginRequest) (*LoginResponse, error) {
	// 1. Buscar usuario en la base de datos.
	var user models.User
	result := database.DB.Where("username = ?", req.Username).First(&user)
	if result.Error != nil {
		return nil, errors.New("invalid credentials")
	}

	// 2. Verificar que la cuenta esté activa.
	if !user.IsActive {
		return nil, errors.New("account is disabled")
	}

	// 3. Verificar contraseña con Argon2id.
	match, err := argon2crypto.VerifyPassword(req.Password, user.PasswordHash)
	if err != nil || !match {
		return nil, errors.New("invalid credentials")
	}

	// 4. PREEMPTION: Terminar cualquier sesión activa existente del usuario.
	//    Esto garantiza que solo UNA sesión esté activa a la vez.
	database.DB.Model(&models.Session{}).
		Where("user_id = ? AND status = ?", user.ID, models.SessionActive).
		Update("status", models.SessionTerminated)

	// 5. Crear nueva sesión.
	sessionID := uuid.New().String()
	platform := req.Platform
	if platform == "" {
		platform = "android"
	}
	expiresAt := time.Now().Add(time.Duration(s.jwtExpiry) * time.Hour)

	session := models.Session{
		SessionID:       sessionID,
		UserID:          user.ID,
		DeviceID:        req.DeviceID,
		Platform:        platform,
		ClientIP:        req.ClientIP,
		Status:          models.SessionActive,
		ExpiresAt:       expiresAt,
		LastHeartbeatAt: time.Now(),
	}
	if err := database.DB.Create(&session).Error; err != nil {
		return nil, fmt.Errorf("failed to create session: %w", err)
	}

	// 6. Generar token JWT.
	now := time.Now()
	claims := middleware.JWTClaims{
		UserID:    user.ID,
		Username:  user.Username,
		SessionID: sessionID,
		DeviceID:  req.DeviceID,
		IsAdmin:   user.IsAdmin,
		RegisteredClaims: jwt.RegisteredClaims{
			IssuedAt:  jwt.NewNumericDate(now),
			ExpiresAt: jwt.NewNumericDate(expiresAt),
			Issuer:    "fastertunnel-api",
			Subject:   fmt.Sprintf("%d", user.ID),
		},
	}

	token := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	tokenStr, err := token.SignedString([]byte(s.jwtSecret))
	if err != nil {
		return nil, fmt.Errorf("failed to sign token: %w", err)
	}

	// 7. Actualizar LastLoginAt y DeviceID del usuario.
	now2 := time.Now()
	database.DB.Model(&user).Updates(map[string]interface{}{
		"last_login_at": &now2,
		"device_id":     req.DeviceID,
	})

	return &LoginResponse{
		Token:     tokenStr,
		SessionID: sessionID,
		ExpiresIn: s.jwtExpiry * 3600,
		ExpiresAt: expiresAt,
		User: UserDTO{
			ID:       user.ID,
			Username: user.Username,
			IsAdmin:  user.IsAdmin,
		},
	}, nil
}

// Logout termina la sesión especificada.
func (s *AuthService) Logout(sessionID string) error {
	result := database.DB.Model(&models.Session{}).
		Where("session_id = ? AND status = ?", sessionID, models.SessionActive).
		Update("status", models.SessionTerminated)

	if result.Error != nil {
		return fmt.Errorf("failed to terminate session: %w", result.Error)
	}
	return nil
}

// Heartbeat actualiza el timestamp de la última actividad de la sesión.
// Si la sesión fue terminada (preemption), retorna error para que el cliente cierre el túnel.
func (s *AuthService) Heartbeat(sessionID, gatewayID string) (*HeartbeatResponse, error) {
	var session models.Session
	result := database.DB.Where("session_id = ? AND status = ?", sessionID, models.SessionActive).First(&session)
	if result.Error != nil {
		// La sesión no existe o fue terminada — preemption ocurrió.
		return &HeartbeatResponse{Status: "session_preempted", Valid: false},
			errors.New("session_preempted")
	}

	// Verificar que el token no haya expirado.
	if time.Now().After(session.ExpiresAt) {
		database.DB.Model(&session).Update("status", models.SessionExpired)
		return &HeartbeatResponse{Status: "session_expired", Valid: false},
			errors.New("session_expired")
	}

	// Actualizar heartbeat y gateway activo.
	updates := map[string]interface{}{
		"last_heartbeat_at": time.Now(),
	}
	if gatewayID != "" {
		updates["gateway_id"] = gatewayID
	}
	database.DB.Model(&session).Updates(updates)

	return &HeartbeatResponse{Status: "ok", Valid: true}, nil
}
