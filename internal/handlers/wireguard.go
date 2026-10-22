package handlers

import (
	"crypto/subtle"
	"encoding/base64"
	"errors"
	"fmt"
	"net"
	"net/http"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/jonathanlopezb/fastertunnel/internal/database"
	"github.com/jonathanlopezb/fastertunnel/internal/models"
	"github.com/jonathanlopezb/fastertunnel/pkg/response"
	"gorm.io/gorm"
)

type WireGuardHandler struct {
	agentToken string
}

type createWireGuardProfileRequest struct {
	PublicKey string `json:"public_key" binding:"required"`
}

type wireGuardProfile struct {
	NodeID              string   `json:"node_id"`
	Name                string   `json:"name"`
	Address             string   `json:"address"`
	DNS                 string   `json:"dns"`
	Endpoint            string   `json:"endpoint"`
	ServerPublicKey     string   `json:"server_public_key"`
	AllowedIPs          []string `json:"allowed_ips"`
	PersistentKeepalive int      `json:"persistent_keepalive"`
}

type wireGuardPeer struct {
	PublicKey string `json:"public_key"`
	Address   string `json:"address"`
}

func NewWireGuardHandler(agentToken string) *WireGuardHandler {
	return &WireGuardHandler{agentToken: agentToken}
}

func (h *WireGuardHandler) CreateProfile(c *gin.Context) {
	var request createWireGuardProfileRequest
	c.Request.Body = http.MaxBytesReader(c.Writer, c.Request.Body, 1024)
	if err := c.ShouldBindJSON(&request); err != nil || !validWireGuardKey(request.PublicKey) {
		response.BadRequest(c, "invalid WireGuard public key", "public_key must be a valid base64-encoded 32-byte key")
		return
	}

	endpoint := strings.TrimSpace(os.Getenv("WIREGUARD_ENDPOINT"))
	serverKey := strings.TrimSpace(os.Getenv("WIREGUARD_SERVER_PUBLIC_KEY"))
	if len(strings.TrimSpace(h.agentToken)) < 32 || !validEndpoint(endpoint) || !validWireGuardKey(serverKey) {
		c.JSON(http.StatusServiceUnavailable, gin.H{
			"success": false,
			"message": "WireGuard gateway is not configured",
		})
		return
	}
	if !provisionLimiter.allow(c.ClientIP()) {
		c.Header("Retry-After", "60")
		c.JSON(429, gin.H{"success": false, "message": "too many profile requests"})
		return
	}

	var peer models.WireGuardPeer
	err := database.DB.Transaction(func(tx *gorm.DB) error {
		if err := tx.Exec("SELECT pg_advisory_xact_lock(?)", int64(0x46545650)).Error; err != nil {
			return fmt.Errorf("lock peer allocation: %w", err)
		}
		err := tx.Where("public_key = ?", request.PublicKey).First(&peer).Error
		if err == nil {
			if !peer.IsActive {
				return errors.New("peer has been revoked")
			}
			return nil
		}
		if !errors.Is(err, gorm.ErrRecordNotFound) {
			return fmt.Errorf("lookup peer: %w", err)
		}

		var existing []models.WireGuardPeer
		if err := tx.Select("address").Find(&existing).Error; err != nil {
			return fmt.Errorf("list allocated addresses: %w", err)
		}
		used := make(map[string]struct{}, len(existing))
		for _, item := range existing {
			used[item.Address] = struct{}{}
		}
		for lastOctet := 10; lastOctet <= 254; lastOctet++ {
			address := fmt.Sprintf("10.66.0.%d", lastOctet)
			if _, exists := used[address]; exists {
				continue
			}
			peer = models.WireGuardPeer{
				PublicKey: request.PublicKey,
				Address:   address,
				IsActive:  true,
			}
			return tx.Create(&peer).Error
		}
		return errors.New("WireGuard address pool is full")
	})
	if err != nil {
		if strings.Contains(err.Error(), "revoked") {
			response.Forbidden(c, "this WireGuard profile has been revoked")
			return
		}
		if strings.Contains(err.Error(), "pool is full") {
			response.InternalError(c, "WireGuard gateway has no available client addresses")
			return
		}
		response.InternalError(c, "failed to allocate WireGuard peer")
		return
	}

	response.OK(c, "WireGuard profile ready", wireGuardProfile{
		NodeID:              "gw-aws-ohio-01",
		Name:                "AWS Ohio",
		Address:             peer.Address + "/32",
		DNS:                 "1.1.1.1",
		Endpoint:            endpoint,
		ServerPublicKey:     serverKey,
		AllowedIPs:          []string{"0.0.0.0/0"},
		PersistentKeepalive: 25,
	})
}

func (h *WireGuardHandler) AgentAuth() gin.HandlerFunc {
	return func(c *gin.Context) {
		parts := strings.Fields(c.GetHeader("Authorization"))
		if len(parts) != 2 || !strings.EqualFold(parts[0], "Bearer") ||
			len(parts[1]) != len(h.agentToken) ||
			subtle.ConstantTimeCompare([]byte(parts[1]), []byte(h.agentToken)) != 1 {
			response.Unauthorized(c, "invalid gateway credentials")
			c.Abort()
			return
		}
		c.Next()
	}
}

func (h *WireGuardHandler) GetPeers(c *gin.Context) {
	var peers []models.WireGuardPeer
	if err := database.DB.Where("is_active = ?", true).Order("address ASC").Find(&peers).Error; err != nil {
		response.InternalError(c, "failed to fetch WireGuard peers")
		return
	}
	result := make([]wireGuardPeer, 0, len(peers))
	for _, peer := range peers {
		result = append(result, wireGuardPeer{
			PublicKey: peer.PublicKey,
			Address:   peer.Address,
		})
	}
	response.OK(c, "WireGuard peers retrieved", gin.H{"peers": result})
}

func validWireGuardKey(value string) bool {
	decoded, err := base64.StdEncoding.DecodeString(value)
	if err != nil || len(decoded) != 32 {
		return false
	}
	for _, b := range decoded {
		if b != 0 {
			return true
		}
	}
	return false
}

func validEndpoint(value string) bool {
	host, portText, err := net.SplitHostPort(value)
	if err != nil || !validEndpointHost(host) {
		return false
	}
	port, err := strconv.Atoi(portText)
	return err == nil && port > 0 && port <= 65535
}

func validEndpointHost(host string) bool {
	if host == "" || len(host) > 253 || strings.ContainsAny(host, " \t\r\n") {
		return false
	}
	if net.ParseIP(host) != nil {
		return true
	}
	for _, label := range strings.Split(host, ".") {
		if len(label) == 0 || len(label) > 63 ||
			!isASCIIAlphaNumeric(label[0]) || !isASCIIAlphaNumeric(label[len(label)-1]) {
			return false
		}
		for i := 0; i < len(label); i++ {
			character := label[i]
			if !isASCIIAlphaNumeric(character) && character != '-' {
				return false
			}
		}
	}
	return true
}

func isASCIIAlphaNumeric(character byte) bool {
	return character >= 'a' && character <= 'z' ||
		character >= 'A' && character <= 'Z' ||
		character >= '0' && character <= '9'
}

type ipRequestWindow struct {
	started time.Time
	count   int
}

type profileRequestLimiter struct {
	mu      sync.Mutex
	windows map[string]ipRequestWindow
}

const maxProfileRequestsPerHour = 10

func (l *profileRequestLimiter) allow(ip string) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.windows == nil {
		l.windows = make(map[string]ipRequestWindow)
	}
	now := time.Now()
	window := l.windows[ip]
	if now.Sub(window.started) >= time.Hour {
		window = ipRequestWindow{started: now}
	}
	if window.count >= maxProfileRequestsPerHour {
		l.windows[ip] = window
		return false
	}
	window.count++
	l.windows[ip] = window
	if len(l.windows) > 4096 {
		for key, value := range l.windows {
			if now.Sub(value.started) >= time.Hour {
				delete(l.windows, key)
			}
		}
	}
	return true
}

var provisionLimiter profileRequestLimiter
