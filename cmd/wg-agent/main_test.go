package main

import (
	"encoding/base64"
	"testing"
)

func TestValidPublicKey(t *testing.T) {
	key := make([]byte, 32)
	key[0] = 1
	if !validPublicKey(base64.StdEncoding.EncodeToString(key)) {
		t.Fatal("valid WireGuard public key was rejected")
	}
	if validPublicKey("invalid") {
		t.Fatal("invalid WireGuard public key was accepted")
	}
}

func TestValidClientAddress(t *testing.T) {
	for _, address := range []string{"10.66.0.10", "10.66.0.254"} {
		if !validClientAddress(address) {
			t.Errorf("valid client address %q was rejected", address)
		}
	}
	for _, address := range []string{"10.66.0.1", "10.66.0.2", "10.66.1.10", "10.66.0.255", "192.168.1.2"} {
		if validClientAddress(address) {
			t.Errorf("invalid client address %q was accepted", address)
		}
	}
}
