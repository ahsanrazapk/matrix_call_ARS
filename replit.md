# matrix_call

A Flutter mobile application for VoIP and video calling using the Matrix protocol, with offline voice command support via the Vivoka SDK.

## Project Overview

This is primarily an **Android/iOS mobile application** that has been adapted to also run as a web preview in the Replit environment using Flutter's web compilation target.

## Architecture

- **Framework**: Flutter (Dart)
- **Protocol**: Matrix (decentralized messaging/calling)
- **WebRTC**: `flutter_webrtc` for peer-to-peer audio/video
- **Voice Commands**: Vivoka SDK (Android-native via MethodChannel/EventChannel)
- **Encryption**: `flutter_vodozemac` (WebAssembly-based Matrix E2EE)
- **Database**: SQLite via `sqflite`

## Key Files

- `lib/main.dart` - App entry point, initializes Matrix client, Vivoka SDK, providers
- `lib/voip/voip_service.dart` - Core VoIP service (WebRTC session management)
- `lib/voip/call_screen.dart` - Active call UI
- `lib/voip/vivoka_sdk.dart` - Flutter wrapper for Vivoka native SDK
- `lib/voip/login_screen.dart` - Matrix login page
- `android/` - Android-specific code including Vivoka SDK integration (Kotlin)
- `assets/vodozemac/` - WASM files for Matrix E2EE

## Replit Setup

### Running in Development

The workflow runs a two-step process:
1. Builds Flutter web app: `flutter build web --release`
2. Serves it via Python HTTP server on port 5000

### Flutter Installation

Flutter 3.29.3 is installed at `/home/runner/flutter/`. The workflow sets the PATH accordingly.

### Dependency Notes

The original `pubspec.yaml` used Dart SDK `^3.10.7` and camera `^0.12.0`, which required newer SDK versions not available. These were adjusted:
- SDK: `>=3.7.0 <4.0.0` (compatible with Flutter 3.29.3's Dart 3.7.2)
- camera: `^0.10.5+5` (compatible with Dart 3.7.2)
- flutter_lints: `^5.0.0` (from 6.0.0)
- meta override: `^1.16.0`

### Web Limitations

Some features are **mobile-only** and won't work in the web preview:
- Camera access (uses Android camera APIs)
- Vivoka SDK voice commands (Android native code)
- Torch/flashlight control
- Audio focus management
- Sqflite local database (works in web via IndexedDB adapter)

### Deployment

Configured as a static site deployment:
- Build: `flutter build web --release`
- Public dir: `build/web`

## Development

To rebuild after changes:
```bash
export PATH=/home/runner/flutter/bin:$PATH
flutter pub get
flutter build web --release
```

The workflow (`run.sh`) handles this automatically.
