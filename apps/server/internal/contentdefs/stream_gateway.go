package contentdefs

import (
 "crypto/aes"
 "crypto/cipher"
 "crypto/hmac"
 "crypto/sha256"
 "encoding/base64"
 "encoding/json"
 "errors"
 "fmt"
 "net/url"
 "os"
 "strings"
)

// streamGatewayPlaybackURL creates an opaque, authenticated player URL. The
// RTSP/RTMP source and credentials are never placed in plaintext in a browser
// address; only the server and the separately deployed gateway know the key.
// Tokens are deterministic for an unchanged configuration, so manifest sync
// does not churn on every read.
func streamGatewayPlaybackURL(gateway *url.URL, configuration map[string]any) (string, error) {
 source := strings.TrimSpace(stringConfig(configuration["sourceUrl"]))
 if len(source) == 0 || len(source) > 2048 {
  return "", errors.New("stream source URL must be 1 to 2048 characters")
 }
 parsed, err := url.Parse(source)
 if err != nil || parsed == nil || parsed.Hostname() == "" || parsed.Opaque != "" || parsed.User != nil || parsed.Fragment != "" {
  return "", errors.New("stream source must be an RTSP/RTMP URL without inline credentials or fragment")
 }
 switch strings.ToLower(parsed.Scheme) {
 case "rtsp", "rtmp", "rtmps":
  parsed.Scheme = strings.ToLower(parsed.Scheme)
 default:
  return "", errors.New("video stream supports only rtsp://, rtmp:// and rtmps:// sources")
 }
 username := stringConfig(configuration["username"])
 password := stringConfig(configuration["password"])
 if len(username) > 255 || len(password) > 255 || (password != "" && username == "") {
  return "", errors.New("stream credentials are invalid")
 }
 if username != "" {
  parsed.User = url.UserPassword(username, password)
 }
 transport := stringConfig(configuration["rtspTransport"])
 if transport == "" { transport = "tcp" }
 if transport != "tcp" && transport != "udp" {
  return "", errors.New("RTSP transport must be TCP or UDP")
 }
 codec := stringConfig(configuration["videoCodec"])
 if codec == "" { codec = "copy" }
 if codec != "copy" && codec != "transcode" {
  return "", errors.New("video codec setting is invalid")
 }
 fit := stringConfig(configuration["fit"])
 if fit == "" { fit = "contain" }
 if fit != "contain" && fit != "cover" && fit != "fill" {
  return "", errors.New("video fit setting is invalid")
 }
 bitrate, err := streamInteger(configuration, "videoBitrateKbps", 2500, 300, 12000)
 if err != nil { return "", err }
 volume, err := streamInteger(configuration, "volume", 100, 0, 100)
 if err != nil { return "", err }
 reconnect, err := streamInteger(configuration, "reconnectSeconds", 5, 2, 60)
 if err != nil { return "", err }
 segments, err := streamInteger(configuration, "segmentSeconds", 2, 1, 6)
 if err != nil { return "", err }
 count, err := streamInteger(configuration, "playlistSegments", 5, 3, 12)
 if err != nil { return "", err }
 color := stringConfig(configuration["backgroundColor"])
 if color == "" { color = "#000000" }
 if len(color) != 7 || color[0] != '#' {
  return "", errors.New("background color must be a hex RGB color")
 }
 for _, ch := range color[1:] {
  if !strings.ContainsRune("0123456789abcdefABCDEF", ch) {
   return "", errors.New("background color must be a hex RGB color")
  }
 }
 payload := map[string]any{
  "sourceUrl":parsed.String(),
  "rtspTransport":transport,
  "videoCodec":codec,
  "videoBitrateKbps":bitrate,
  "audioEnabled":streamBoolean(configuration, "audioEnabled", true),
  "muted":streamBoolean(configuration, "muted", true),
  "volume":volume,
  "fit":fit,
  "backgroundColor":color,
  "reconnectSeconds":reconnect,
  "segmentSeconds":segments,
  "playlistSegments":count,
  "showErrors":streamBoolean(configuration, "showErrors", true),
 }
 encoded, err := json.Marshal(payload)
 if err != nil { return "", err }
 rawKey := strings.TrimSpace(os.Getenv("STREAM_GATEWAY_KEY"))
 key, err := base64.StdEncoding.DecodeString(rawKey)
 if err != nil || len(key) != 32 {
  return "", errors.New("STREAM_GATEWAY_KEY must be a base64-encoded 32-byte key shared with the stream gateway")
 }
 cipherBlock, err := aes.NewCipher(key)
 if err != nil { return "", err }
 gcm, err := cipher.NewGCM(cipherBlock)
 if err != nil { return "", err }
 mac := hmac.New(sha256.New, key)
 mac.Write([]byte("tilecast-stream-nonce-v1:"))
 mac.Write(encoded)
 nonce := mac.Sum(nil)[:gcm.NonceSize()]
 sealed := gcm.Seal(append([]byte(nil), nonce...), nonce, encoded, []byte("tilecast-stream-v1"))
 token := base64.RawURLEncoding.EncodeToString(sealed)
 gateway.Path = "/player/" + token
 gateway.RawPath = ""
 gateway.RawQuery = ""
 gateway.Fragment = ""
 return gateway.String(), nil
}

func streamInteger(configuration map[string]any, name string, fallback, min, max int) (int,error) {
 value, present := configuration[name]
 if !present || value == nil { return fallback,nil }
 number, ok := integerConfig(value)
 if !ok || number < min || number > max {
  return 0, fmt.Errorf("invalid %s: value must be between %d and %d", name, min, max)
 }
 return number,nil
}

func streamBoolean(configuration map[string]any, name string, fallback bool) bool {
 value, ok := configuration[name].(bool)
 if !ok { return fallback }
 return value
}
