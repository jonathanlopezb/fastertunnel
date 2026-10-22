package main

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strings"
	"time"
)

const maxResponseBytes = 1 << 20

type apiResponse struct {
	Success bool `json:"success"`
	Data    struct {
		Peers []peer `json:"peers"`
	} `json:"data"`
}

type peer struct {
	PublicKey string `json:"public_key"`
	Address   string `json:"address"`
}

func main() {
	apiURL := strings.TrimRight(os.Getenv("FASTATUNNEL_API_URL"), "/")
	token := os.Getenv("WIREGUARD_AGENT_TOKEN")
	if apiURL == "" || token == "" {
		log.Fatal("FASTATUNNEL_API_URL and WIREGUARD_AGENT_TOKEN are required")
	}
	if len(token) < 32 {
		log.Fatal("WIREGUARD_AGENT_TOKEN must contain at least 32 characters")
	}
	parsedAPIURL, err := url.Parse(apiURL)
	if err != nil || parsedAPIURL.Scheme != "https" || parsedAPIURL.Host == "" || parsedAPIURL.User != nil {
		log.Fatal("FASTATUNNEL_API_URL must be an HTTPS URL without embedded credentials")
	}

	iface := envOrDefault("WIREGUARD_INTERFACE", "wg0")
	statePath := envOrDefault("WIREGUARD_STATE_FILE", "/var/lib/fastertunnel/wg-peers.json")
	interval := 3 * time.Second
	client := &http.Client{Timeout: 10 * time.Second}

	for {
		ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
		if err := syncPeers(ctx, client, apiURL+"/api/v1/wireguard/peers", token, iface, statePath); err != nil {
			log.Printf("peer synchronization failed: %v", err)
		}
		cancel()
		time.Sleep(interval)
	}
}

func syncPeers(ctx context.Context, client *http.Client, endpoint, token, iface, statePath string) error {
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint, nil)
	if err != nil {
		return fmt.Errorf("create API request: %w", err)
	}
	request.Header.Set("Authorization", "Bearer "+token)
	response, err := client.Do(request)
	if err != nil {
		return fmt.Errorf("request peer list: %w", err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(io.LimitReader(response.Body, 2048))
		return fmt.Errorf("API returned HTTP %d: %s", response.StatusCode, strings.TrimSpace(string(body)))
	}
	body, err := io.ReadAll(io.LimitReader(response.Body, maxResponseBytes+1))
	if err != nil {
		return fmt.Errorf("read peer list: %w", err)
	}
	if len(body) > maxResponseBytes {
		return errors.New("peer list exceeds maximum size")
	}
	var payload apiResponse
	if err := json.Unmarshal(body, &payload); err != nil {
		return fmt.Errorf("decode peer list: %w", err)
	}
	if !payload.Success {
		return errors.New("API rejected peer list request")
	}

	desired := make(map[string]string, len(payload.Data.Peers))
	addresses := make(map[string]struct{}, len(payload.Data.Peers))
	for _, item := range payload.Data.Peers {
		if !validPublicKey(item.PublicKey) || !validClientAddress(item.Address) {
			return fmt.Errorf("API returned an invalid peer entry")
		}
		if _, duplicate := addresses[item.Address]; duplicate {
			return errors.New("API returned a duplicate client address")
		}
		addresses[item.Address] = struct{}{}
		if existing, duplicate := desired[item.PublicKey]; duplicate && existing != item.Address {
			return errors.New("API returned conflicting addresses for one peer")
		}
		desired[item.PublicKey] = item.Address
	}
	managed, err := readState(statePath)
	if err != nil {
		return fmt.Errorf("read managed peer state: %w", err)
	}

	keys := make([]string, 0, len(desired))
	for key := range desired {
		keys = append(keys, key)
	}
	sort.Strings(keys)
	for _, key := range keys {
		if err := runWG("set", iface, "peer", key, "allowed-ips", desired[key]+"/32"); err != nil {
			return fmt.Errorf("configure peer %s: %w", key, err)
		}
	}

	for key := range managed {
		if _, active := desired[key]; active {
			continue
		}
		if err := runWG("set", iface, "peer", key, "remove"); err != nil {
			return fmt.Errorf("remove revoked peer %s: %w", key, err)
		}
	}

	if err := writeState(statePath, desired); err != nil {
		return fmt.Errorf("save managed peer state: %w", err)
	}
	return nil
}

func runWG(args ...string) error {
	output, err := exec.Command("wg", args...).CombinedOutput()
	if err != nil {
		return fmt.Errorf("wg %s: %w: %s", strings.Join(args[:min(len(args), 2)], " "), err, strings.TrimSpace(string(output)))
	}
	return nil
}

func readState(path string) (map[string]string, error) {
	state := make(map[string]string)
	data, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return state, nil
	}
	if err != nil {
		return nil, err
	}
	if len(data) == 0 {
		return state, nil
	}
	if err := json.Unmarshal(data, &state); err != nil {
		return nil, err
	}
	for key, address := range state {
		if !validPublicKey(key) || !validClientAddress(address) {
			return nil, errors.New("managed peer state contains invalid data")
		}
	}
	return state, nil
}

func writeState(path string, state map[string]string) error {
	if err := os.MkdirAll(filepath.Dir(path), 0700); err != nil {
		return err
	}
	data, err := json.Marshal(state)
	if err != nil {
		return err
	}
	temporary := path + ".tmp"
	if err := os.WriteFile(temporary, data, 0600); err != nil {
		return err
	}
	return os.Rename(temporary, path)
}

func validPublicKey(value string) bool {
	decoded, err := decodeWireGuardKey(value)
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

func decodeWireGuardKey(value string) ([]byte, error) {
	const encodedLength = 44
	if len(value) != encodedLength || !strings.HasSuffix(value, "=") {
		return nil, errors.New("invalid WireGuard key")
	}
	return base64.StdEncoding.DecodeString(value)
}

func validClientAddress(value string) bool {
	ip := net.ParseIP(value).To4()
	if ip == nil || !ip.Equal(net.IPv4(10, 66, 0, ip[3])) {
		return false
	}
	return ip[3] >= 10 && ip[3] <= 254
}

func envOrDefault(key, fallback string) string {
	if value := strings.TrimSpace(os.Getenv(key)); value != "" {
		return value
	}
	return fallback
}
