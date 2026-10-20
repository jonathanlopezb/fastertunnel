package services

import (
	"crypto/ed25519"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"time"

	"github.com/jonathanlopezb/fastertunnel/internal/database"
	"github.com/jonathanlopezb/fastertunnel/internal/models"
)

// ConfigService gestiona la distribución firmada de servidores y tweaks.
type ConfigService struct {
	privateKey ed25519.PrivateKey
	publicKey  ed25519.PublicKey
}

// NewConfigService crea el servicio con una clave Ed25519 generada en memoria.
// En producción, la clave privada debe cargarse desde una variable de entorno o un secrets manager.
func NewConfigService() (*ConfigService, error) {
	pub, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		return nil, fmt.Errorf("failed to generate Ed25519 keypair: %w", err)
	}
	return &ConfigService{privateKey: priv, publicKey: pub}, nil
}

// ServersResponse es la respuesta firmada con la lista de servidores.
type ServersResponse struct {
	Servers   []models.ServerNode `json:"servers"`
	Signature string              `json:"signature"`
	Timestamp int64               `json:"timestamp"`
	Version   int                 `json:"version"`
}

// TweaksResponse es la respuesta firmada con la lista de tweaks.
type TweaksResponse struct {
	Tweaks    []models.Tweak `json:"tweaks"`
	Signature string         `json:"signature"`
	Timestamp int64          `json:"timestamp"`
}

// GetActiveServers retorna todos los nodos activos con su lista firmada con Ed25519.
func (cs *ConfigService) GetActiveServers() (*ServersResponse, error) {
	var servers []models.ServerNode
	if err := database.DB.Where("is_active = ?", true).Order("ping_ms ASC").Find(&servers).Error; err != nil {
		return nil, fmt.Errorf("failed to fetch servers: %w", err)
	}

	// Marcar el nodo más rápido como recomendado (no es un campo del modelo,
	// se añade como campo adicional en el JSON usando un wrapper).
	timestamp := time.Now().Unix()
	payload, err := json.Marshal(servers)
	if err != nil {
		return nil, fmt.Errorf("failed to marshal servers: %w", err)
	}

	sig := ed25519.Sign(cs.privateKey, payload)
	sigB64 := base64.StdEncoding.EncodeToString(sig)

	return &ServersResponse{
		Servers:   servers,
		Signature: sigB64,
		Timestamp: timestamp,
		Version:   1,
	}, nil
}

// GetTweaks retorna los tweaks filtrados por país y/u operadora, firmados con Ed25519.
func (cs *ConfigService) GetTweaks(country, carrier string) (*TweaksResponse, error) {
	query := database.DB.Where("is_active = ?", true)
	if country != "" {
		query = query.Where("country = ?", country)
	}
	if carrier != "" {
		query = query.Where("carrier = ?", carrier)
	}

	var tweaks []models.Tweak
	if err := query.Find(&tweaks).Error; err != nil {
		return nil, fmt.Errorf("failed to fetch tweaks: %w", err)
	}

	payload, err := json.Marshal(tweaks)
	if err != nil {
		return nil, fmt.Errorf("failed to marshal tweaks: %w", err)
	}

	sig := ed25519.Sign(cs.privateKey, payload)
	sigB64 := base64.StdEncoding.EncodeToString(sig)

	return &TweaksResponse{
		Tweaks:    tweaks,
		Signature: sigB64,
		Timestamp: time.Now().Unix(),
	}, nil
}

// PublicKeyBase64 retorna la clave pública Ed25519 en Base64 para incrustarla en el cliente Android.
func (cs *ConfigService) PublicKeyBase64() string {
	return base64.StdEncoding.EncodeToString(cs.publicKey)
}
