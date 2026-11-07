package contentdefs

import (
 "encoding/base64"
 "encoding/json"
 "errors"
 "fmt"
 "net/url"
 "strings"
)

const nativeStreamHost = "tilecast-native-stream.invalid"

// NativeStreamPlaybackURL serializes an editor-authored RTSP/RTMP feed as an
// internal HTTPS presentation URL. The URL is never fetched: Android's
// RemoteWebLayer recognizes this reserved host and creates a Media3 surface.
// Keeping the envelope in the existing remote-web descriptor lets older
// manifests and the shared Layout projection carry it without a new service.
//
// IMPORTANT: base64 is NOT encryption. The authenticated player manifest can
// contain camera credentials; never print the resulting URL in logs or expose
// presentation manifests publicly.
func NativeStreamPlaybackURL(configuration map[string]any) (string, error) {
 raw := strings.TrimSpace(stringConfig(configuration["sourceUrl"]))
 if len(raw) < 1 || len(raw) > 1024 {
  return "", errors.New("stream URL must be between 1 and 1024 characters")
 }
 source, err := url.Parse(raw)
 if err != nil || source.Opaque != "" || source.Hostname() == "" || source.User != nil ||
    source.Fragment != "" || source.RawQuery == "" && strings.Contains(raw,"#") {
  return "", errors.New("stream URL must have a host and no embedded credentials or fragment")
 }
 switch strings.ToLower(source.Scheme) {
 case "rtsp", "rtmp":
  source.Scheme = strings.ToLower(source.Scheme)
 default:
  return "", errors.New("stream URL must use rtsp:// or rtmp://")
 }
 username := stringConfig(configuration["username"])
 password := stringConfig(configuration["password"])
 if len(username) > 128 || len(password) > 256 || (password != "" && username == "") {
  return "", errors.New("invalid stream login or password")
 }
 if username != "" { source.User = url.UserPassword(username,password) }
 transport := stringConfig(configuration["rtspTransport"])
 if transport == "" { transport = "tcp" }
 if transport != "tcp" && transport != "udp" {
  return "", errors.New("RTSP transport must be tcp or udp")
 }
 fit := stringConfig(configuration["fit"])
 if fit == "" { fit = "contain" }
 if fit != "contain" && fit != "cover" && fit != "fill" {
  return "", errors.New("video fit mode is invalid")
 }
 volume, err := nativeStreamInt(configuration,"volume",100,0,100)
 if err != nil { return "",err }
 retry, err := nativeStreamInt(configuration,"reconnectSeconds",5,1,300)
 if err != nil { return "",err }
 attempts, err := nativeStreamInt(configuration,"maxReconnectAttempts",0,0,1000)
 if err != nil { return "",err }
 payload := map[string]any{
  "url":source.String(),
  "rtspTransport":transport,
  "fit":fit,
  "muted":nativeStreamBool(configuration,"muted",true),
  "volume":volume,
  "autoReconnect":nativeStreamBool(configuration,"autoReconnect",true),
  "reconnectSeconds":retry,
  "maxReconnectAttempts":attempts,
 }
 encoded, err := json.Marshal(payload)
 if err != nil { return "",err }
 token := base64.RawURLEncoding.EncodeToString(encoded)
 result := "https://"+nativeStreamHost+"/v1/"+token
 if len(result)>2048 {
  return "", errors.New("stream configuration is too large for a presentation URL")
 }
 return result,nil
}

func nativeStreamInt(configuration map[string]any, name string, fallback,min,max int)(int,error) {
 raw,ok := configuration[name]
 if !ok || raw==nil { return fallback,nil }
 n,valid := integerConfig(raw)
 if !valid || n<min || n>max {
  return 0,fmt.Errorf("%s must be an integer between %d and %d",name,min,max)
 }
 return n,nil
}
func nativeStreamBool(configuration map[string]any,name string, fallback bool) bool {
 b,ok:=configuration[name].(bool)
 if !ok{return fallback}
 return b
}
