package models

import "gorm.io/gorm"

// Tweak representa una configuración de payload HTTP/SNI para evadir restricciones
// de una operadora de telefonía específica (como HTTP Injector o NapsternetV).
type Tweak struct {
	gorm.Model
	TweakID         string `gorm:"uniqueIndex;not null" json:"tweak_id"`
	Label           string `gorm:"not null"             json:"label"`
	Carrier         string `json:"carrier"`
	Country         string `gorm:"size:2"               json:"country"`
	SNISpoof        string `json:"sni_spoof,omitempty"`
	PayloadTemplate string `gorm:"type:text"            json:"payload_template,omitempty"`
	Transport       string `gorm:"default:'SSH'"        json:"transport"`
	RecommendedGW   string `json:"recommended_gateway"`
	IsActive        bool   `gorm:"default:true"         json:"is_active"`
}
