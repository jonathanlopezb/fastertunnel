package handlers

import (
	"encoding/base64"
	"testing"
	"time"
)

func TestValidWireGuardKey(t *testing.T) {
	valid := base64.StdEncoding.EncodeToString(make([]byte, 32))
	if validWireGuardKey(valid) {
		t.Fatal("all-zero keys must be rejected")
	}
	key := make([]byte, 32)
	key[0] = 1
	if !validWireGuardKey(base64.StdEncoding.EncodeToString(key)) {
		t.Fatal("valid 32-byte key was rejected")
	}
	if validWireGuardKey("not-a-key") {
		t.Fatal("malformed key was accepted")
	}
}

func TestValidEndpoint(t *testing.T) {
	for _, endpoint := range []string{"3.142.187.253:51820", "gw.example.test:51820", "[2001:db8::1]:51820"} {
		if !validEndpoint(endpoint) {
			t.Errorf("valid endpoint %q was rejected", endpoint)
		}
	}
	for _, endpoint := range []string{
		"", "gw.example.test", "gw.example.test:0", "gw.example.test:65536",
		"https://gw.example.test:51820", "bad host:51820", "bad..host:51820",
	} {
		if validEndpoint(endpoint) {
			t.Errorf("invalid endpoint %q was accepted", endpoint)
		}
	}
}

func TestProfileRequestLimiter(t *testing.T) {
	limiter := profileRequestLimiter{}
	for i := 0; i < maxProfileRequestsPerHour; i++ {
		if !limiter.allow("192.0.2.10") {
			t.Fatalf("request %d was unexpectedly rate limited", i+1)
		}
	}
	if limiter.allow("192.0.2.10") {
		t.Fatal("request over the hourly limit was not rate limited")
	}
	if !limiter.allow("192.0.2.11") {
		t.Fatal("request from a different address was rate limited")
	}

	limiter.mu.Lock()
	limiter.windows["192.0.2.10"] = ipRequestWindow{
		started: time.Now().Add(-time.Hour),
		count:   maxProfileRequestsPerHour,
	}
	limiter.mu.Unlock()
	if !limiter.allow("192.0.2.10") {
		t.Fatal("expired rate-limit window was not reset")
	}
}
