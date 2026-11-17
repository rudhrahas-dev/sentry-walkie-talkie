# Sentry PTT • Tactical Walkie-Talkie

A lightweight, low-latency Push-to-Talk (PTT) tactical walkie-talkie designed for instant voice communication across virtual UHF channels, available both as a zero-install Web PWA and as a standalone Native Android application.

Available live at: **[https://rudhrahas-dev.github.io/sentry-walkie-talkie/](https://rudhrahas-dev.github.io/sentry-walkie-talkie/)**  
Direct Android APK Download: **[`sentry-ptt.apk`](sentry-ptt.apk)**

---

## ⚡ Key Features

* **Dual Platform Architecture (Web + Native Android):**
  * **Web PWA:** Instant browser comms across Apple iPhones (Safari), Android (Chrome), and Desktop. No app store installation required.
  * **Native Android App (`.apk`):** Standalone native Android client with hardware key PTT, background screen-off operation, and direct device-to-device radio mesh.
* **Seamless Cross-Platform Interoperability:**
  * Android native app users and Apple iPhone/Web users transmit and receive voice packets on identical channels via the unified 18-byte binary radio protocol.
* **Dual-Transceiver Modes (Android App):**
  * **🌐 Cloud Mesh:** Connects across the internet via global MQTT brokers, enabling full interoperability with Web browsers and Apple iPhones.
  * **📡 Off-Grid Direct Radio:** 100% off-grid peer-to-peer mesh using Google Nearby Connections (Bluetooth LE discovery + high-bandwidth Wi-Fi Direct socket upgrades). Operates anywhere with no internet, Wi-Fi router, or cellular coverage.
* **Background Pocket Mode (Android App):**
  * Runs as a persistent Android Foreground Service with partial wake locks, enabling seamless continuous reception and transmission even when the screen is locked or the app is minimized in a pocket.
* **Hardware Key PTT (Android App):**
  * Physical **Volume Down** key acts as the tactical Push-To-Talk trigger. Press and hold to transmit, release to end broadcast.
* **1,000-Channel Frequency Synthesizer (CH 000 – 999):**
  * Direct 3-digit numeric dialer pad (tap the channel display to tune).
  * Quick-step adjustment buttons (`-10`, `-1`, `+1`, `+10`) with automatic wraparound.
  * Synthetic UHF frequency mapping readout (e.g., `CH-042` $\rightarrow$ `446.0525 MHz`).
* **Tactical Cockpit Interface:**
  * High-contrast dark cockpit aesthetic (`#0a0c10`) with armored HUD panels, glowing status readouts, and tactical telemetry indicators.
* **Dual Operation Modes:**
  * **SQUAD LOCK:** Tunes exclusively to a selected channel for isolated team communication.
  * **ALL-CALL:** Open broadcast mode that monitors all active channels and broadcasts globally.
* **Private Squad PIN (Encrypted Rooms):**
  * Optional 4-digit PIN for private channels. Voice packets are verified so only operators with the matching PIN can decode and listen to transmissions.
* **Live Phosphor Oscilloscope & LED VU Meter:**
  * Real-time audio waveform visualizer rendered on an authentic phosphor oscilloscope canvas.
  * 16-segment LED VU meter reflecting microphone decibel levels and incoming audio.
* **Always-On Screen Wake Lock:**
  * Built-in `⚡ AWAKE` toggle using the Screen Wake Lock API to prevent mobile displays from sleeping during operations.
* **Speaker & Volume Boost:**
  * Integrated volume slider offering **up to 150% audio boost**.
  * Instant speaker mute/unmute toggle.
  * Synthesized tactile **Roger Beep & Squelch** audio effects (toggleable via `CHIRP`).

---

## 📖 How to Use

### 🌐 Web & Apple iPhone Users
1. **Open the Web App:** Open `https://rudhrahas-dev.github.io/sentry-walkie-talkie/` on Safari (iOS), Chrome, or any modern browser.
2. **Initialize Audio:** Tap **"INITIALIZE COMMS"** to enable the Web Audio engine and allow microphone access.
3. **Select Your Channel:** Use `-` / `+` or tap the drum digits to dial any 3-digit frequency (e.g., `042`).
4. **Push to Talk:** Hold the center PTT button (or hold `Spacebar` on desktop) to broadcast; release to listen.

### 🤖 Android Native Users
1. **Install APK:** Download **[`sentry-ptt.apk`](sentry-ptt.apk)** from the web app or repository and install it on your device.
2. **Grant Permissions:** Grant Microphone, Location, and Nearby Device permissions when prompted.
3. **Pocket Mode Comms:** Lock the phone or put it in your pocket. Incoming transmissions will play through the speaker.
4. **Hardware PTT:** Press and hold the physical **Volume Down** button to transmit anytime.

---

## 🔒 Radio Protocol & Binary Specification

Packets are structured with a fixed 18-byte big-endian header followed by raw 16 kHz 16-bit PCM mono audio:

| Offset | Size | Field | Description |
|---|---|---|---|
| 0 | 1 byte | Packet Type | `0x01` (PTT Start), `0x02` (Audio Chunk), `0x03` (PTT End) |
| 1 - 2 | 2 bytes | Channel | Channel number (`0` to `999`) |
| 3 - 6 | 4 bytes | PIN Hash | Computed hash of private squad PIN (`0` if open) |
| 7 - 14 | 8 bytes | Callsign | Fixed-length 8-byte ASCII callsign (e.g. `SENTRY01`) |
| 15 - 16 | 2 bytes | Sequence | Monotonically incrementing packet counter |
| 17 | 1 byte | Padding | Alignment byte to guarantee 2-byte boundary for audio |
| 18+ | N bytes | Audio Payload | 16 kHz 16-bit mono PCM chunks |

---

## 🛠️ Building the Native Android App from Source

To compile the standalone APK from source:

```bash
cd android
./gradlew assembleDebug
```

The compiled APK will be output to `android/app/build/outputs/apk/debug/app-debug.apk`.
