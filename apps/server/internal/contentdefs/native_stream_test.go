package contentdefs

import (
	"encoding/base64"
	"encoding/json"
	"net/url"
	"strings"
	"testing"
)

func TestNativeStreamPlaybackURL(t *testing.T) {
	config := map[string]any{
		"sourceUrl":            "rtsp://192.168.1.100:554/live",
		"username":             "viewer",
		"password":             "p@ss word",
		"rtspTransport":        "tcp",
		"muted":                false,
		"volume":               55.0,
		"fit":                  "cover",
		"autoReconnect":        true,
		"reconnectSeconds":     3.0,
		"maxReconnectAttempts": 0.0,
	}
	address, err := NativeStreamPlaybackURL(config)
	if err != nil {
		t.Fatal(err)
	}
	parsed, err := url.Parse(address)
	if err != nil {
		t.Fatal(err)
	}
	if parsed.Scheme != "https" || parsed.Host != nativeStreamHost || !strings.HasPrefix(parsed.Path, "/v1/") {
		t.Fatalf("unexpected wrapper URL %s", address)
	}
	encoded := strings.TrimPrefix(parsed.Path, "/v1/")
	data, err := base64.RawURLEncoding.DecodeString(encoded)
	if err != nil {
		t.Fatal(err)
	}
	var payload map[string]any
	if err := json.Unmarshal(data, &payload); err != nil {
		t.Fatal(err)
	}
	if payload["rtspTransport"] != "tcp" || payload["fit"] != "cover" || payload["volume"] != float64(55) {
		t.Fatal(payload)
	}
	source, err := url.Parse(payload["url"].(string))
	if err != nil {
		t.Fatal(err)
	}
	if source.User.Username() != "viewer" {
		t.Fatal("missing source credentials")
	}
	password, _ := source.User.Password()
	if password != "p@ss word" {
		t.Fatal("password was not escaped correctly")
	}
}

func TestNativeStreamPlaybackURLRejectsInvalid(t *testing.T) {
	for _, source := range []string{"http://example.org/live", "file:///tmp/secret", "rtsp://user:pass@example.org/live", "rtsp://host/live#frag", "rtsp://"} {
		if _, err := NativeStreamPlaybackURL(map[string]any{"sourceUrl": source}); err == nil {
			t.Errorf("accepted unsafe stream URL %q", source)
		}
	}
	for _, value := range []any{float64(-1), float64(1001), "10", true} {
		if _, err := NativeStreamPlaybackURL(map[string]any{"sourceUrl": "rtmp://example.org/live", "maxReconnectAttempts": value}); err == nil {
			t.Errorf("accepted invalid retries %v", value)
		}
	}
}

func TestVideoStreamDefinitionPresent(t *testing.T) {
	catalog, err := Load()
	if err != nil {
		t.Fatal(err)
	}
	definition, ok := catalog.Widget("video-stream")
	if !ok {
		t.Fatal("video-stream definition missing")
	}
	if definition.WebIntegration == nil || definition.WebIntegration.Transform != "native_stream" {
		t.Fatal("wrong integration type")
	}
	address, hosts, err := WebPresentationURL(definition, map[string]any{"sourceUrl": "rtsp://192.0.2.10/live"})
	if err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(address, "https://"+nativeStreamHost+"/v1/") || len(hosts) != 1 || hosts[0] != nativeStreamHost {
		t.Fatal(address, hosts)
	}
}
