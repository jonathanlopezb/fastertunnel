package models

import "gorm.io/gorm"

// TransportProtocol define los protocolos de transporte soportados por los nodos gateway.
type TransportProtocol string

const (
	TransportSSH       TransportProtocol = "SSH"
	TransportSSL       TransportProtocol = "SSL"
	TransportWSS       TransportProtocol = "WSS"
	TransportUDPCustom TransportProtocol = "UDP_CUSTOM"
)

// ServerNode representa un nodo gateway de fasterTunnel al que los clientes se conectan.
// Son servidores SSH/SSL/WebSocket que actúan como punto de salida del tráfico del usuario.
type ServerNode struct {
	gorm.Model
	NodeID       string            `gorm:"uniqueIndex;not null" json:"node_id"`
	Name         string            `gorm:"not null"             json:"name"`
	CountryCode  string            `gorm:"size:2"               json:"country_code"`
	FlagEmoji    string            `json:"flag_emoji"`
	Host         string            `gorm:"not null"             json:"host"`
	Port         int               `gorm:"not null"             json:"port"`
	Transport    TransportProtocol `gorm:"default:'SSH'"        json:"transport"`
	SNIHost      string            `json:"sni_host,omitempty"`
	IsActive     bool              `gorm:"default:true"         json:"is_active"`
	IsPremium    bool              `gorm:"default:false"        json:"is_premium"`
	PingMs       int               `gorm:"default:0"            json:"ping_ms"`
	MaxUsers     int               `gorm:"default:100"          json:"max_users"`
	CurrentUsers int               `gorm:"default:0"            json:"current_users"`
}
