package httpapi

import (
	"net/http"
	"strings"

	"github.com/tilecast/tilecast/apps/server/internal/devices"
)

func (s *server) playerManifest(w http.ResponseWriter, r *http.Request) {
	principal := r.Context().Value(deviceContextKey).(devices.DevicePrincipal)
	const schedulePrecisionHeader = "X-Tilecast-Schedule-Precision"
	const campaignBoundsHeader = "X-Tilecast-Campaign-Bounds"
	weeklySeconds := r.Header.Get(schedulePrecisionHeader) == "seconds"
	campaignBounds := weeklySeconds && r.Header.Get(campaignBoundsHeader) == "1"
	manifest, etag, err := s.playlists.BuildPlayerManifest(r.Context(), principal.ScreenID, weeklySeconds, campaignBounds)
	if err != nil {
		s.writePlaylistError(w, r, err)
		return
	}
	w.Header().Set("ETag", etag)
	w.Header().Set("Cache-Control", "private, no-cache")
	w.Header().Add("Vary", schedulePrecisionHeader)
	w.Header().Add("Vary", campaignBoundsHeader)
	if strings.TrimSpace(r.Header.Get("If-None-Match")) == etag {
		w.WriteHeader(http.StatusNotModified)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"data": manifest})
}
