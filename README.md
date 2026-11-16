# Sentry PTT • Tactical Walkie-Talkie

Tactical, off-grid aesthetic Push-to-Talk (PTT) web walkie-talkie.

Designed for instant mobile deployment with zero app-store friction — anyone can open the link in their mobile browser, tap to initialize audio, and immediately communicate across tactical channels.

---

## ⚡ Features

* **Tactical Cockpit Theme:** Deep space cockpit palette (`#0a0c10`), armored cards (`#11151f`), tactical borders, and neon indicators (Cyan, Green, Amber, Red).
* **3-Digit Frequency Synthesizer (CH 000 – 999):**
  * Steppers: `[-10]`, `[-1]`, `[+1]`, `[+10]` with automatic wraparound.
  * Direct 3-digit digital dialer keypad (tap the channel box to open).
  * Synthetic UHF frequency readout (e.g. `CH-042 -> 446.0525 MHz`).
* **Dual Operation Modes:**
  * **SQUAD LOCK:** Tunes exclusively to the selected 3-digit channel.
  * **ALL-CALL (Open Broadcast):** Monitors all channels within range and broadcasts globally.
* **Squad Privacy / 4-Digit PIN:**
  * Set an optional PIN for private squad communications. Only operators with the matching PIN can decode and hear voice on that channel.
* **Tactical Push-and-Hold Button:**
  * Instant response with `pointerdown` / `pointerup` (touch and mouse).
  * Desktop keyboard shortcut: Press and hold `Spacebar`.
  * Visual feedback: Glowing high-intensity red ring with pulse shockwaves during transmission.
* **Live Oscilloscope & LED VU Meter:**
  * Real-time audio waveform rendered on an authentic phosphor oscilloscope canvas.
  * Segmented LED VU meter reflecting mic input and incoming voice levels.
* **Always-On Speaker & Volume Knob:**
  * Tactical slider with 0% to 150% volume boost.
  * Instant speaker mute/unmute button.
  * Tactical roger beeps and squelch tail sound effects (can be toggled on/off).
* **Installable PWA:**
  * Works offline and can be added directly to the Android or iOS home screen.

---

## 🚀 How to Host on GitHub Pages

1. **Create a GitHub Repository:**
   * Create a new repository on GitHub named `sentry-walkie-talkie` (or any name you prefer) under your account.
2. **Push the Code:**
   ```bash
   cd /home/pradeep/Projects/sentry-walkie-talkie
   git remote add origin https://github.com/<your-username>/<repo-name>.git
   git branch -M main
   git push -u origin main
   ```
3. **Enable GitHub Pages:**
   * Go to repository **Settings** -> **Pages**.
   * Under **Branch**, select `main` and `/ (root)`, then click **Save**.
   * Your site will be live at:
     ```
     https://<your-username>.github.io/<repo-name>/
     ```

---

## 📱 How to Use at the Event

1. Share your GitHub Pages URL or a QR code linking to it.
2. Attendees open the link on their mobile phones (Chrome, Safari, Firefox).
3. Tap **"INITIALIZE COMMS"** to enable the Web Audio transceiver and grant microphone access.
4. Agree on a channel (e.g., `042`) or use `ALL-CALL`.
5. Hold the large center button to speak — release to listen!
