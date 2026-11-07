package updates

import "testing"

func TestForkAndroidApplication(t *testing.T) {
	for _, test := range []struct {
		application, version, channel string
		want                          bool
	}{
		{ApplicationID, "1.0.1", "stable", true},
		{ApplicationID + ".test", "1.0.1-dev.1", "beta", true},
		{ApplicationID + ".test", "1.0.1-dev.1", "stable", false},
		{ApplicationID + ".test", "1.0.1", "stable", false},
		{"other.app", "1.0.1-dev.1", "beta", false},
	} {
		if got := validAndroidApplication(Manifest{ApplicationID: test.application, VersionName: test.version, Channel: test.channel}); got != test.want {
			t.Errorf("%s %s %s: got %t, want %t", test.application, test.version, test.channel, got, test.want)
		}
	}
}
