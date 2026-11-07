package updates

import "strings"

// Fork test APKs retain their installed package and certificate identities.
// The Player still verifies both against its own installed application.
func validAndroidApplication(manifest Manifest) bool {
	return manifest.ApplicationID == ApplicationID ||
		(manifest.ApplicationID == ApplicationID+".test" &&
			strings.Contains(manifest.VersionName, "-dev.") &&
			manifest.Channel == "beta")
}
