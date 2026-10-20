package models

import (
	"time"

	"gorm.io/gorm"
)

// SessionStatus define los posibles estados de una sesión.
type SessionStatus string

const (
	SessionActive     SessionStatus = "ACTIVE"
	SessionExpired    SessionStatus = "EXPIRED"
	SessionTerminated SessionStatus = "TERMINATED"
)

// Session representa una sesión de túnel activa de un usuario en un dispositivo.
// Solo puede existir UNA sesión con status ACTIVE por usuario a la vez.
type Session struct {
	gorm.Model
	SessionID       string        `gorm:"uniqueIndex;not null" json:"session_id"`
	UserID          uint          `gorm:"not null;index"       json:"user_id"`
	User            User          `gorm:"foreignKey:UserID"    json:"-"`
	DeviceID        string        `gorm:"not null"             json:"device_id"`
	Platform        string        `gorm:"default:'android'"    json:"platform"`
	ClientIP        string        `json:"client_ip"`
	GatewayID       string        `json:"gateway_id"`
	Status          SessionStatus `gorm:"default:'ACTIVE';index" json:"status"`
	ExpiresAt       time.Time     `json:"expires_at"`
	LastHeartbeatAt time.Time     `json:"last_heartbeat_at"`
}
