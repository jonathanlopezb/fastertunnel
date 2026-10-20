package handlers

import (
	"github.com/gin-gonic/gin"
	"github.com/jonathanlopezb/fastertunnel/pkg/response"
)

// HealthData es el cuerpo de respuesta del endpoint de salud.
type HealthData struct {
	Status  string `json:"status"`
	Version string `json:"version"`
	Service string `json:"service"`
}

// HealthCheck responde con el estado del servidor.
// GET /api/v1/health
func HealthCheck(c *gin.Context) {
	response.OK(c, "faster bienvenido", HealthData{
		Status:  "online",
		Version: "1.0.0",
		Service: "fasterTunnel API",
	})
}
