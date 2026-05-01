# neoforge（mcmod）

NeoForge mod プロジェクト単体のビルド/実行ガイドです。

全体手順は以下を参照してください。

- 英語: `../README.md`
- 日本語: `../README.ja.md`

## 前提

- Java 21
- Gradle Wrapper（`gradlew.bat`）

## ビルド

```powershell
Set-Location "<repo-root>"
Set-Location ".\neoforge"
.\gradlew.bat --no-daemon compileJava
```

