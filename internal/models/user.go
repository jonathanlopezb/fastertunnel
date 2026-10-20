package models

import (
	"time"

	"gorm.io/gorm"
)

// User representa un usuario registrado en el sistema fasterTunnel.
type User struct {
	gorm.Model
	Username     string     `gorm:"uniqueIndex;not null" json:"username"`
	PasswordHash string     `gorm:"not null"             json:"-"`
	IsActive     bool       `gorm:"default:true"         json:"is_active"`
	IsAdmin      bool       `gorm:"default:false"        json:"is_admin"`
	DeviceID     string     `gorm:"default:''"           json:"device_id"`
	LastLoginAt  *time.Time `json:"last_login_at"`
	Sessions     []Session  `gorm:"foreignKey:UserID"    json:"-"`
}
