# neoforge (mcmod)

Component guide for building and running the NeoForge mod project.

For full stack setup, see:

- English: `../README.md`
- Japanese: `../README.ja.md`

## Requirements

- Java 21
- Gradle wrapper (`gradlew.bat`)

## Build

```powershell
Set-Location "<repo-root>"
Set-Location ".\neoforge"
.\gradlew.bat --no-daemon compileJava
```

## Run Server

```powershell
Set-Location "<repo-root>"
Set-Location ".\neoforge"
.\gradlew.bat runServer
```

## Run Client

```powershell
Set-Location "<repo-root>"
Set-Location ".\neoforge"
.\gradlew.bat runClient
```

## Config Files

- `run/config/nat_traversal_mod-common.toml`
- `run/config/nat_traversal_mod-server.toml`
- `run/config/nat_traversal_mod-client.toml`

## Notes

- Relay port values are configurable.
- `relay_priority_mode` also supports `quic_first`.
- `quic_tls_mode=ca_or_pinned` uses CA validation and accepts self-signed only when `quic_cert_fingerprint_sha256` matches.
- `quic_tls_mode=insecure_trust_all` is for development only.

