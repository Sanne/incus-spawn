The real certificates of the case behind #1552: a "Never Trust" on Sectigo Public Server Authentication Root E46, while `api.github.com` sends E46 cross-signed by USERTrust ECC (#1631).
They were downloaded from crt.sh, not captured from a Mac or from `api.github.com`'s handshake.

| File | crt.sh | Subject, issuer | SHA-256 |
|---|---|---|---|
| `root-e46.pem` | 4256644603 | Root E46, self-signed | `C90F26F0FB1B4018B22227519B5CA2B53E2CA5B3BE5CF18EFE1BEF47380C5383` |
| `root-e46-by-usertrust-ecc.pem` | 11405664274 | Root E46, by USERTrust ECC Certification Authority | `EA6B89ED6907A209FF9188676FB164E7ACED894B8996DFBE5CE5BBCC22DE4DDD` |
| `ca-dv-e36.pem` | 4267304693 | Public Server Authentication CA DV E36, by Root E46 | `873F0BA80E3AC222656DFD04158CC15C2927D42D5D05F01DEE4A47EB43A916DF` |

The self-signed E46 has the same SHA-256 as the one in Fedora's `tls-ca-bundle.pem` and in the JDK's `cacerts`.
`openssl verify -partial_chain` accepts the cross-signed copy under the USERTrust ECC root of that bundle, and DV E36 under the self-signed E46.

That `api.github.com` sends DV E36 and this cross-signed copy, and that the Apple roots keychain holds this self-signed E46, was not checked here: an inspecting proxy stood between this download and GitHub.
The Mac runner confirmed both on 2026-11-18, on macOS 27.2.1 with a direct connection: `SystemRootCertificates.keychain` holds E46 with this SHA-1, and `api.github.com` sent DV E36 and this cross-signed E46, byte for byte these files.
The leaf is left out: it expires within the year, and a test cannot serve it without its key.

There is no recorded user-domain plist.
A runner cannot write the user domain (#1515), so the tests build one in `trust-settings-export`'s shape around E46's issuer and serial (`MacKeychainTrustTest`).
