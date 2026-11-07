# Android TLS verifier compatibility

The Kotlin verifier is copied from rustls-platform-verifier tag `v/0.7.1`,
commit `252e25161a91af476cbea620e29d277592d27ec7`, Android component 0.2.0.
The original MIT and Apache license notices are included in the APK assets.

Local changes add `PrivateCaPolicy` and one classification check in
`isKnownRoot`. The optional `TILECAST_PRIVATE_CA_SHA256` build setting names
an operator-managed private CA already trusted by Android. It does not install
or trust a new CA. Normal Android chain validation must succeed first. The
existing upstream private-root rule then applies when no OCSP response is
stapled. Hostname, validity and handshake-signature validation stay unchanged;
public roots retain revocation checking.

The JNI method names and result codes stay unchanged. The build refuses a
different Rust Android component version until compatibility is reviewed.

The app supplies its own network security configuration. The original AAR's
public-CA CRL allowlist is not included, so it cannot override local HTTP
enrollment and the legacy update transport.
