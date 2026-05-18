# CuSO4 Open

A lightweight, open-source Android ROOT manager built with native Android + WebView.
**BUT IT CAN'T RUN WITH OUT OFFICAL MAGISK/KERNELSU ROOT MANAGER**
## Features

- **Universal ROOT Manager Support** — Works with Magisk, KernelSU, and other major ROOT solutions
- **Module Management** — Install, enable/disable, and remove ROOT modules directly from your device
- **Magisk Detection** — Displays Zygisk status and Ramdisk information for Magisk installations
- **One-Click Uninstall** — Remove ROOT or Magisk completely without rebooting multiple times

## Supported ROOT Managers

| Manager | Support |
|---------|---------|
| Magisk | Full |
| KernelSU | Full |
| APatch | Full |
| Other | Partial |

### Magisk-specific Info

When running Magisk, CuSO4 Open displays:
- Current kernel version with Magisk version
- Zygisk enabled status (Yes/No)
- Ramdisk status (Yes/No)

## Installation

1. Download the latest APK from the [Releases](https://github.com/CuSO4-X/CuSO4-RootManager/releases) page
2. Install the APK on your Android device (enable "Install from unknown sources" if needed)
3. Launch **CuSO4 Open**

## Permissions

- **INTERNET** — For module download features
- **FOREGROUND_SERVICE** — Required for background operations
- **POST_NOTIFICATIONS** — For module installation progress notifications

## Project Structure

```
CuSO4-opensource/
├── android/
│   ├── app/src/main/
│   │   ├── assets/home/      # Web UI (HTML/CSS/JS)
│   │   ├── java/             # Kotlin backend
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
├── gradle/                    # Gradle wrapper
├── gradlew                    # Build script (Unix)
├── gradlew.bat                # Build script (Windows)
└── settings.gradle.kts
```

## Building from Source

### Prerequisites

- Android Studio Hedgehog (2023.1.1) or later
- Android SDK API 34
- Gradle 8.9
- JDK 17

### Build Steps

```bash
# Clone the repository
git clone https://github.com/CuSO4-X/CuSO4-RootManager.git
cd CuSO4-RootManager

# Build debug APK
./gradlew assembleDebug

# The APK will be at:
# android/app/build/outputs/apk/debug/app-debug.apk
```

## Package Info

- **Package Name**: `com.cuso4.open`
- **App Name**: CuSO4 Open
- **Min SDK**: 21 (Android 5.0)
- **Target SDK**: 34 (Android 14)

## License

This project is open source. See [LICENSE](LICENSE) for details.

## Credits

- [Magisk](https://github.com/topjohnwu/Magisk) — The ultimate Android ROOT solution
- [KernelSU](https://github.com/KernelSU/KernelSU) — Kernel-based ROOT for GKI devices
