package handlers

import (
	"github.com/gin-gonic/gin"
	"github.com/jonathanlopezb/fastertunnel/internal/services"
	"github.com/jonathanlopezb/fastertunnel/pkg/response"
)

// ServerHandler agrupa los endpoints de nodos gateway.
type ServerHandler struct {
	configService *services.ConfigService
}

// NewServerHandler crea un nuevo ServerHandler.
func NewServerHandler(configService *services.ConfigService) *ServerHandler {
	return &ServerHandler{configService: configService}
}

// GetServers retorna la lista de servidores activos firmada con Ed25519.
// GET /api/v1/servers
func (h *ServerHandler) GetServers(c *gin.Context) {
	result, err := h.configService.GetActiveServers()
	if err != nil {
		response.InternalError(c, "failed to fetch servers")
		return
	}
	response.OK(c, "servers retrieved", result)
}

// GetServersPing retorna los servidores con información de latencia para auto-selección.
// GET /api/v1/servers/ping
func (h *ServerHandler) GetServersPing(c *gin.Context) {
	result, err := h.configService.GetActiveServers()
	if err != nil {
		response.InternalError(c, "failed to fetch servers")
		return
	}

	// Construir respuesta simplificada solo con info de latencia.
	type PingInfo struct {
		NodeID      string `json:"node_id"`
		Name        string `json:"name"`
		Host        string `json:"host"`
		Port        int    `json:"port"`
		Transport   string `json:"transport"`
		CountryCode string `json:"country_code"`
		FlagEmoji   string `json:"flag_emoji"`
		PingMs      int    `json:"ping_ms"`
		IsActive    bool   `json:"is_active"`
	}

	pings := make([]PingInfo, 0, len(result.Servers))
	for _, s := range result.Servers {
		pings = append(pings, PingInfo{
			NodeID:      s.NodeID,
			Name:        s.Name,
			Host:        s.Host,
			Port:        s.Port,
			Transport:   string(s.Transport),
			CountryCode: s.CountryCode,
			FlagEmoji:   s.FlagEmoji,
			PingMs:      s.PingMs,
			IsActive:    s.IsActive,
		})
	}

	response.OK(c, "server ping data", gin.H{
		"servers":   pings,
		"timestamp": result.Timestamp,
	})
}
