package handlers

import (
	"github.com/gin-gonic/gin"
	"github.com/jonathanlopezb/fastertunnel/internal/services"
	"github.com/jonathanlopezb/fastertunnel/pkg/response"
)

// TweakHandler agrupa los endpoints de configuraciones de payload.
type TweakHandler struct {
	configService *services.ConfigService
}

// NewTweakHandler crea un nuevo TweakHandler.
func NewTweakHandler(configService *services.ConfigService) *TweakHandler {
	return &TweakHandler{configService: configService}
}

// GetTweaks retorna los tweaks de payload filtrados por país y operadora.
// GET /api/v1/tweaks?country=PE&carrier=Claro
func (h *TweakHandler) GetTweaks(c *gin.Context) {
	country := c.Query("country")
	carrier := c.Query("carrier")

	result, err := h.configService.GetTweaks(country, carrier)
	if err != nil {
		response.InternalError(c, "failed to fetch tweaks")
		return
	}

	response.OK(c, "tweaks retrieved", result)
}
