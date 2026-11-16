# Sentry PTT • Tactical Walkie-Talkie

A lightweight, low-latency Push-to-Talk (PTT) web walkie-talkie designed for instant group voice communication across virtual channels.

No account registration, no app store installations, and zero configuration required — open the link in any mobile or desktop browser and communicate instantly.

---

## ⚡ Key Features

* **Tactical Cockpit Interface:** High-contrast dark cockpit aesthetic (`#0a0c10`) with armored HUD panels, glowing status readouts, and tactical telemetry indicators.
* **1,000-Channel Frequency Synthesizer (CH 000 – 999):**
  * Direct 3-digit numeric dialer pad (tap the channel display to tune).
  * Quick-step adjustment buttons (`-10`, `-1`, `+1`, `+10`) with auto-wraparound.
  * Synthetic UHF frequency mapping readout (e.g., `CH-042` $\rightarrow$ `446.0525 MHz`).
* **Dual Operation Modes:**
  * **SQUAD LOCK:** Tunes exclusively to a selected channel for isolated team communication.
  * **ALL-CALL:** Open broadcast mode that monitors all active channels and broadcasts globally.
* **Private Squad PIN (Encrypted Rooms):**
  * Set an optional 4-digit PIN for private channels. Voice packets are verified so only operators with the matching PIN can decode and listen to transmissions.
* **Tactical Push-and-Hold (PTT) Controls:**
  * Instant response touch/pointer gesture (`pointerdown` / `pointerup`).
  * Desktop keyboard shortcut: Press and hold `Spacebar` to transmit.
  * Visual feedback: High-intensity glowing red shockwave ring when transmitting; green status ring when receiving.
* **Live Oscilloscope & LED VU Meter:**
  * Real-time audio waveform visualizer rendered on an authentic phosphor oscilloscope canvas.
  * 16-segment LED VU meter reflecting microphone decibel levels and incoming audio.
* **Always-On Screen Wake Lock:**
  * Built-in `⚡ AWAKE` toggle using the Screen Wake Lock API to prevent mobile displays from sleeping or locking during operations.
* **Speaker & Volume Boost:**
  * Integrated volume slider offering **up to 150% audio boost**.
  * Instant speaker mute/unmute toggle.
  * Synthesized tactile **Roger Beep & Squelch** audio effects (toggleable via `CHIRP`).
* **Progressive Web App (PWA):**
  * Installable directly to Android or iOS home screens with standalone display mode and offline asset caching.

---

## 📖 How to Use

1. **Open the App:** Navigate to the web app URL on any smartphone, tablet, or computer.
2. **Initialize Audio:** Tap **"INITIALIZE COMMS"** on the startup screen to activate the Web Audio engine and allow microphone access.
3. **Select Your Channel:**
   * Use the `+` / `-` buttons or tap the channel drum to dial any 3-digit frequency (e.g., `042`).
   * For team privacy, tap **SET PIN** to configure a shared 4-digit passcode.
   * To talk to everyone in range regardless of channel, tap **ALL-CALL**.
4. **Push to Talk:**
   * **Hold** the large center button (or hold `Spacebar` on desktop) to speak.
   * **Release** the button to stop transmitting and listen for replies.

---

## 🔒 Privacy & Architecture

* **No Accounts or Logins:** Fully anonymous operator callsigns (customizable in settings).
* **Transport Encryption:** All audio packets are transmitted over encrypted TLS WebSockets (`WSS`).
* **Lightweight Footprint:** Built entirely with pure Vanilla JavaScript, CSS3, and HTML5 Web Audio API — consuming under 40 MB of RAM with zero third-party framework overhead.
* **Wide Device Support:** Compatible with standard mobile browsers (Chrome, Safari, Firefox, Edge, Samsung Internet) and low-memory Android devices.
