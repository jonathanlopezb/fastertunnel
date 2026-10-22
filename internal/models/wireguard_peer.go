package models

import "gorm.io/gorm"

// WireGuardPeer is a device public key authorized on the AWS WireGuard gateway.
type WireGuardPeer struct {
	gorm.Model
	PublicKey string `gorm:"uniqueIndex;size:44;not null" json:"public_key"`
	Address   string `gorm:"uniqueIndex;size:15;not null" json:"address"`
	IsActive  bool   `gorm:"not null;default:true;index" json:"is_active"`
}
