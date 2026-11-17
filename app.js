/**
 * SENTRY PTT • Tactical Walkie-Talkie Audio & Network Engine
 * Visual Theme & Architecture: Tactical Cockpit
 */

(() => {
  'use strict';

  // ========================================================
  // STATE MANAGEMENT
  // ========================================================
  const state = {
    channel: parseInt(localStorage.getItem('sentry_channel') || '42', 10),
    mode: localStorage.getItem('sentry_mode') || 'squad', // 'squad' or 'all-call'
    callsign: localStorage.getItem('sentry_callsign') || `SENTRY-${Math.floor(10 + Math.random() * 90)}`,
    squadPin: localStorage.getItem('sentry_squad_pin') || '',
    volume: parseInt(localStorage.getItem('sentry_volume') || '100', 10),
    speakerEnabled: localStorage.getItem('sentry_speaker') !== 'false',
    chirpEnabled: localStorage.getItem('sentry_chirp') !== 'false',
    alwaysOn: localStorage.getItem('sentry_always_on') !== 'false',
    broker: localStorage.getItem('sentry_broker') || 'hivemq',
    
    // Runtime transient states
    clientId: 'sentry_' + Math.random().toString(16).substring(2, 10),
    isAudioInitialized: false,
    isTransmitting: false,
    isReceiving: false,
    activeRemotePeer: null,
    rxTimeoutTimer: null,
    numpadValue: '042',
    sequenceNum: 0
  };

  // ========================================================
  // DOM ELEMENTS
  // ========================================================
  const $ = (id) => document.getElementById(id);
  
  const startOverlay = $('startOverlay');
  const activateAudioBtn = $('activateAudioBtn');
  const netStatusBadge = $('netStatusBadge');
  const netStatusText = $('netStatusText');
  const callsignBtn = $('callsignBtn');
  const callsignDisplay = $('callsignDisplay');
  const wakeLockBtn = $('wakeLockBtn');
  const settingsBtn = $('settingsBtn');
  
  const freqDisplay = $('freqDisplay');
  const dHundreds = $('dHundreds');
  const dTens = $('dTens');
  const dOnes = $('dOnes');
  const channelDrumTrigger = $('channelDrumTrigger');
  
  const stepDown10 = $('stepDown10');
  const stepDown1 = $('stepDown1');
  const stepUp1 = $('stepUp1');
  const stepUp10 = $('stepUp10');
  
  const modeAllCall = $('modeAllCall');
  const modeSquad = $('modeSquad');
  const squadSecStatus = $('squadSecStatus');
  const quickPinBtn = $('quickPinBtn');
  const pinBtnText = $('pinBtnText');
  
  const transceiverState = $('transceiverState');
  const activeSpeakerDisplay = $('activeSpeakerDisplay');
  const scopeCanvas = $('scopeCanvas');
  const scopeCtx = scopeCanvas.getContext('2d');
  const vuBars = document.querySelectorAll('.vu-bar');
  
  const pttButton = $('pttButton');
  const pttOuterRing = document.querySelector('.ptt-outer-ring');
  const pttSubText = $('pttSubText');
  
  const speakerToggleBtn = $('speakerToggleBtn');
  const speakerIconOn = $('speakerIconOn');
  const speakerIconOff = $('speakerIconOff');
  const speakerLabel = $('speakerLabel');
  const volumeSlider = $('volumeSlider');
  const volumePercent = $('volumePercent');
  const chirpToggleBtn = $('chirpToggleBtn');
  
  // Modals
  const channelModal = $('channelModal');
  const closeChannelModal = $('closeChannelModal');
  const numpadBuffer = $('numpadBuffer');
  const confirmChannelBtn = $('confirmChannelBtn');
  const numpadClear = $('numpadClear');
  const numpadBack = $('numpadBack');
  
  const settingsModal = $('settingsModal');
  const closeSettingsModal = $('closeSettingsModal');
  const callsignInput = $('callsignInput');
  const squadPinInput = $('squadPinInput');
  const brokerSelect = $('brokerSelect');
  const localPeerId = $('localPeerId');
  const saveSettingsBtn = $('saveSettingsBtn');

  // ========================================================
  // AUDIO ENGINE (Web Audio API)
  // ========================================================
  let audioCtx = null;
  let masterGain = null;
  let analyser = null;
  let micStream = null;
  let micSource = null;
  let scriptProcessor = null;
  let nextPlayTime = 0;
  const SAMPLE_RATE = 16000; // 16kHz crisp voice band

  async function initAudioEngine() {
    if (state.isAudioInitialized) return;
    
    const AudioContextClass = window.AudioContext || window.webkitAudioContext;
    audioCtx = new AudioContextClass();
    
    // Create master gain
    masterGain = audioCtx.createGain();
    updateVolumeGain();
    
    // Create analyser for oscilloscope & VU meter
    analyser = audioCtx.createAnalyser();
    analyser.fftSize = 256;
    analyser.smoothingTimeConstant = 0.5;
    
    // Route: MasterGain -> Analyser -> Destination
    masterGain.connect(analyser);
    analyser.connect(audioCtx.destination);
    
    // In Native Android App, native Kotlin AudioRecord handles microphone capture directly!
    if (window.AndroidBridge) {
      state.isAudioInitialized = true;
      startOscilloscope();
      playChirp('activate');
      return;
    }

    // Request Microphone access with fallback for strict Android hardware
    try {
      try {
        micStream = await navigator.mediaDevices.getUserMedia({
          audio: {
            echoCancellation: true,
            noiseSuppression: true,
            autoGainControl: true,
            channelCount: 1
          }
        });
      } catch (constraintErr) {
        console.warn('Advanced audio constraints failed, trying basic audio: true', constraintErr);
        micStream = await navigator.mediaDevices.getUserMedia({ audio: true });
      }
      
      micSource = audioCtx.createMediaStreamSource(micStream);
      
      // Setup buffer processor (bufferSize 2048 samples)
      scriptProcessor = audioCtx.createScriptProcessor(2048, 1, 1);
      scriptProcessor.onaudioprocess = handleMicAudioProcess;
      
      // Keep persistent references to avoid V8 garbage collection on mobile
      window._sentryScriptProcessor = scriptProcessor;
      
      micSource.connect(scriptProcessor);
      
      // Connect to a dummy gain of 0 to keep processor running without feedback
      const muteNode = audioCtx.createGain();
      muteNode.gain.value = 0;
      window._sentryMuteNode = muteNode;
      scriptProcessor.connect(muteNode);
      muteNode.connect(audioCtx.destination);
      
      state.isAudioInitialized = true;
      startOscilloscope();
      playChirp('activate');
    } catch (err) {
      console.warn('Microphone permission or audio init error:', err);
      let errMsg = 'Microphone access is required for Push-to-Talk.\n\n';
      if (err.name === 'NotAllowedError' || err.name === 'PermissionDeniedError') {
        errMsg += 'Permission was denied. Tap the tune/lock icon in your browser address bar -> Permissions -> Allow Microphone, then refresh.';
      } else if (err.name === 'NotFoundError' || err.name === 'DevicesNotFoundError') {
        errMsg += 'No microphone found on this device.';
      } else if (err.name === 'NotReadableError' || err.name === 'TrackStartError') {
        errMsg += 'Microphone is currently in use by another app (e.g. phone call or recorder).';
      } else {
        errMsg += err.message || 'Please check browser site permissions and refresh.';
      }
      alert(errMsg);
    }
  }

  function updateVolumeGain() {
    if (!masterGain) return;
    if (!state.speakerEnabled) {
      masterGain.gain.value = 0;
    } else {
      masterGain.gain.value = (state.volume / 100) * 1.2; // Allows 120% boost
    }
  }

  // Tactical Radio Sound Effects Synthesizer
  function playChirp(type) {
    if (!audioCtx || !state.chirpEnabled) return;
    if (audioCtx.state === 'suspended') audioCtx.resume();
    
    const now = audioCtx.currentTime;
    const osc = audioCtx.createOscillator();
    const gain = audioCtx.createGain();
    osc.connect(gain);
    gain.connect(masterGain);
    
    if (type === 'ptt_press') {
      // High-pitched tactical key-up chirp
      osc.type = 'sine';
      osc.frequency.setValueAtTime(1400, now);
      osc.frequency.exponentialRampToValueAtTime(1800, now + 0.04);
      gain.gain.setValueAtTime(0.12, now);
      gain.gain.exponentialRampToValueAtTime(0.001, now + 0.06);
      osc.start(now);
      osc.stop(now + 0.06);
    } else if (type === 'ptt_release') {
      // Classic Motorola style Roger Beep / Squelch Tail
      osc.type = 'triangle';
      osc.frequency.setValueAtTime(1209, now);
      osc.frequency.setValueAtTime(1633, now + 0.04);
      gain.gain.setValueAtTime(0.15, now);
      gain.gain.setValueAtTime(0.15, now + 0.07);
      gain.gain.exponentialRampToValueAtTime(0.001, now + 0.12);
      osc.start(now);
      osc.stop(now + 0.12);
    } else if (type === 'activate' || type === 'channel_click') {
      // Subtle military UI click
      osc.type = 'sine';
      osc.frequency.setValueAtTime(800, now);
      osc.frequency.exponentialRampToValueAtTime(300, now + 0.03);
      gain.gain.setValueAtTime(0.1, now);
      gain.gain.exponentialRampToValueAtTime(0.001, now + 0.03);
      osc.start(now);
      osc.stop(now + 0.03);
    }
  }

  // Microphone audio downsampling & transmission
  function handleMicAudioProcess(e) {
    if (!state.isTransmitting || !mqttClient || !mqttClient.isConnected()) return;
    
    const inputData = e.inputBuffer.getChannelData(0);
    const nativeSampleRate = audioCtx.sampleRate;
    
    // Downsample from native sample rate (e.g. 44.1k/48k) to 16000 Hz
    const downsampled = downsampleBuffer(inputData, nativeSampleRate, SAMPLE_RATE);
    if (!downsampled || downsampled.length === 0) return;
    
    // Convert Float32Array to 16-bit PCM Int16Array
    const pcm16 = new Int16Array(downsampled.length);
    for (let i = 0; i < downsampled.length; i++) {
      let s = Math.max(-1, Math.min(1, downsampled[i]));
      pcm16[i] = s < 0 ? s * 0x8000 : s * 0x7FFF;
    }
    
    // Package into binary packet
    sendAudioPacket(pcm16);
  }

  function downsampleBuffer(buffer, inSampleRate, outSampleRate) {
    if (inSampleRate === outSampleRate) return buffer;
    const sampleRateRatio = inSampleRate / outSampleRate;
    const newLength = Math.round(buffer.length / sampleRateRatio);
    const result = new Float32Array(newLength);
    let offsetResult = 0;
    let offsetBuffer = 0;
    while (offsetResult < result.length) {
      const nextOffsetBuffer = Math.round((offsetResult + 1) * sampleRateRatio);
      let accum = 0, count = 0;
      for (let i = offsetBuffer; i < nextOffsetBuffer && i < buffer.length; i++) {
        accum += buffer[i];
        count++;
      }
      result[offsetResult] = count > 0 ? accum / count : 0;
      offsetResult++;
      offsetBuffer = nextOffsetBuffer;
    }
    return result;
  }

  // Play incoming audio chunks gaplessly
  function playIncomingAudio(float32Data) {
    if (!audioCtx || !state.speakerEnabled) return;
    if (audioCtx.state === 'suspended') {
      audioCtx.resume();
    }
    
    const audioBuffer = audioCtx.createBuffer(1, float32Data.length, SAMPLE_RATE);
    audioBuffer.copyToChannel(float32Data, 0);
    
    const sourceNode = audioCtx.createBufferSource();
    sourceNode.buffer = audioBuffer;
    sourceNode.connect(masterGain);
    
    const currentTime = audioCtx.currentTime;
    if (nextPlayTime < currentTime || nextPlayTime > currentTime + 0.35) {
      nextPlayTime = currentTime + 0.02; // Small 20ms safety lead
    }
    
    sourceNode.start(nextPlayTime);
    nextPlayTime += audioBuffer.duration;
  }

  // ========================================================
  // MQTT PEER-TO-PEER MESH NETWORKING (Paho MQTT)
  // ========================================================
  let mqttClient = null;
  const BROKERS = {
    hivemq: { host: 'broker.hivemq.com', port: 8884, path: '/mqtt', ssl: true },
    emqx: { host: 'broker.emqx.io', port: 8084, path: '/mqtt', ssl: true }
  };

  function connectMqtt() {
    updateNetStatus('connecting', 'TUNING MESH...');
    const config = BROKERS[state.broker] || BROKERS.hivemq;
    
    try {
      mqttClient = new Paho.MQTT.Client(config.host, config.port, config.path, state.clientId);
      mqttClient.onConnectionLost = onMqttConnectionLost;
      mqttClient.onMessageArrived = onMqttMessageArrived;
      
      const connectOptions = {
        useSSL: config.ssl,
        timeout: 6,
        keepAliveInterval: 20,
        cleanSession: true,
        onSuccess: onMqttConnectSuccess,
        onFailure: (err) => {
          console.warn('MQTT Connection failed:', err);
          updateNetStatus('offline', 'RETRYING...');
          setTimeout(connectMqtt, 3000);
        }
      };
      
      mqttClient.connect(connectOptions);
    } catch (e) {
      console.error('MQTT Init error:', e);
      updateNetStatus('offline', 'OFFLINE');
      setTimeout(connectMqtt, 4000);
    }
  }

  function onMqttConnectSuccess() {
    updateNetStatus('online', 'MESH ACTIVE');
    subscribeToChannels();
  }

  function onMqttConnectionLost(responseObject) {
    if (responseObject.errorCode !== 0) {
      console.warn('MQTT connection lost:', responseObject.errorMessage);
      updateNetStatus('offline', 'DISCONNECTED');
      setTimeout(connectMqtt, 2500);
    }
  }

  function getChannelTopic(chNum) {
    const padded = String(chNum).padStart(3, '0');
    return `sentry/ptt/v1/ch/${padded}`;
  }

  function getAllCallTopic() {
    return `sentry/ptt/v1/allcall`;
  }

  function subscribeToChannels() {
    if (!mqttClient || !mqttClient.isConnected()) return;
    
    // Always subscribe to All-Call
    mqttClient.subscribe(getAllCallTopic(), { qos: 0 });
    
    // In Squad mode, subscribe to specific channel
    // In All-Call mode, subscribe to wildcard `sentry/ptt/v1/ch/+`
    if (state.mode === 'all-call') {
      mqttClient.subscribe(`sentry/ptt/v1/ch/+`, { qos: 0 });
    } else {
      mqttClient.subscribe(getChannelTopic(state.channel), { qos: 0 });
    }
  }

  function resubscribeChannels(oldChannel) {
    if (!mqttClient || !mqttClient.isConnected()) return;
    if (oldChannel !== undefined) {
      try {
        mqttClient.unsubscribe(getChannelTopic(oldChannel));
      } catch (e) {}
    }
    subscribeToChannels();
  }

  function updateNetStatus(status, text) {
    netStatusBadge.className = `net-badge ${status}`;
    netStatusText.textContent = text;
  }

  // ========================================================
  // PACKET FORMAT & TRANSMISSION PROTOCOL
  // ========================================================
  /**
   * Binary packet layout (Uint8Array):
   * [0]       = Packet Type (0x01: PTT_START, 0x02: AUDIO_CHUNK, 0x03: PTT_END)
   * [1..2]    = Channel Number (Uint16 Big-Endian)
   * [3..6]    = Squad PIN hash (4 bytes, 0 if public)
   * [7..14]   = Callsign (8 ASCII bytes, padded)
   * [15..16]  = Sequence Number (Uint16)
   * [17..]    = Payload:
   *             - For AUDIO_CHUNK: 16-bit PCM samples
   */
  
  function getPinHash(pin) {
    if (!pin) return 0;
    let hash = 0;
    for (let i = 0; i < pin.length; i++) {
      hash = ((hash << 5) - hash) + pin.charCodeAt(i);
      hash |= 0;
    }
    return Math.abs(hash);
  }

  const HEADER_LEN = 18;

  function buildHeader(type) {
    const header = new Uint8Array(HEADER_LEN);
    header[0] = type;
    
    // Channel (2 bytes)
    header[1] = (state.channel >> 8) & 0xFF;
    header[2] = state.channel & 0xFF;
    
    // Squad PIN Hash (4 bytes)
    const pinHash = getPinHash(state.squadPin);
    header[3] = (pinHash >> 24) & 0xFF;
    header[4] = (pinHash >> 16) & 0xFF;
    header[5] = (pinHash >> 8) & 0xFF;
    header[6] = pinHash & 0xFF;
    
    // Callsign (8 bytes ASCII)
    const cleanCallsign = state.callsign.substring(0, 8);
    for (let i = 0; i < 8; i++) {
      header[7 + i] = i < cleanCallsign.length ? cleanCallsign.charCodeAt(i) : 32;
    }
    
    // Sequence (2 bytes)
    state.sequenceNum = (state.sequenceNum + 1) & 0xFFFF;
    header[15] = (state.sequenceNum >> 8) & 0xFF;
    header[16] = state.sequenceNum & 0xFF;
    
    // Byte 17: Padding to keep audio data on an even 2-byte boundary
    header[17] = 0x00;
    
    return header;
  }

  function sendControlPacket(type) {
    if (!mqttClient || !mqttClient.isConnected()) return;
    const header = buildHeader(type);
    const targetTopic = state.mode === 'all-call' ? getAllCallTopic() : getChannelTopic(state.channel);
    
    const message = new Paho.MQTT.Message(header.buffer);
    message.destinationName = targetTopic;
    message.qos = 0;
    mqttClient.send(message);
  }

  function sendAudioPacket(pcm16) {
    if (!mqttClient || !mqttClient.isConnected()) return;
    const header = buildHeader(0x02);
    const pcmBytes = new Uint8Array(pcm16.buffer, pcm16.byteOffset, pcm16.byteLength);
    
    // Combine header + audio
    const packet = new Uint8Array(header.length + pcmBytes.length);
    packet.set(header, 0);
    packet.set(pcmBytes, header.length);
    
    const targetTopic = state.mode === 'all-call' ? getAllCallTopic() : getChannelTopic(state.channel);
    const message = new Paho.MQTT.Message(packet.buffer);
    message.destinationName = targetTopic;
    message.qos = 0;
    mqttClient.send(message);
  }

  function onMqttMessageArrived(message) {
    try {
      const payload = new Uint8Array(message.payloadBytes);
      if (payload.length < 17) return;
      
      const type = payload[0];
      const incomingChannel = (payload[1] << 8) | payload[2];
      const incomingPinHash = (payload[3] << 24) | (payload[4] << 16) | (payload[5] << 8) | payload[6];
      
      // Extract callsign
      let senderCallsign = '';
      for (let i = 7; i < 15; i++) {
        if (payload[i] > 32) senderCallsign += String.fromCharCode(payload[i]);
      }
      if (!senderCallsign) senderCallsign = 'UNKNOWN';
      
      // Ignore my own broadcast
      if (senderCallsign.trim() === state.callsign.substring(0, 8).trim()) return;
      
      // Squad PIN check: If my channel has a PIN or remote has a PIN, hashes must match!
      const myPinHash = getPinHash(state.squadPin);
      if (myPinHash !== incomingPinHash) {
        // Ignored: Mismatched private squad PIN
        return;
      }
      
      // Channel filter check
      if (state.mode === 'squad' && incomingChannel !== state.channel && !message.destinationName.endsWith('allcall')) {
        return; // Ignore transmissions from other channels when locked to squad
      }
      
      const paddedCh = String(incomingChannel).padStart(3, '0');
      
      // Ensure AudioContext is awake on receiving phone
      if (audioCtx && audioCtx.state === 'suspended') {
        audioCtx.resume();
      }
      
      // Handle packet types
      if (type === 0x01) {
        // PTT_START
        state.isReceiving = true;
        state.activeRemotePeer = senderCallsign;
        updateTransceiverUI('receiving', `RX: ${senderCallsign} (CH-${paddedCh})`);
        pttButton.classList.add('receiving-state');
        if (audioCtx) {
          nextPlayTime = audioCtx.currentTime + 0.02;
        }
      } else if (type === 0x02) {
        // AUDIO_CHUNK
        state.isReceiving = true;
        state.activeRemotePeer = senderCallsign;
        updateTransceiverUI('receiving', `RX: ${senderCallsign} (CH-${paddedCh})`);
        pttButton.classList.add('receiving-state');
        
        // Support both 17-byte (legacy) and 18-byte headers seamlessly
        const offset = (payload.length > 18 && payload[17] === 0x00) ? 18 : 17;
        const audioByteLength = payload.length - offset;
        const sampleCount = Math.floor(audioByteLength / 2);
        
        if (sampleCount > 0) {
          const float32 = new Float32Array(sampleCount);
          const dataView = new DataView(payload.buffer, payload.byteOffset, payload.byteLength);
          
          for (let i = 0; i < sampleCount; i++) {
            const int16Val = dataView.getInt16(offset + (i * 2), true);
            float32[i] = int16Val / 32768.0;
          }
          
          playIncomingAudio(float32);
        }
        
        // Auto-reset RX if no packet for 1.2 seconds
        clearTimeout(state.rxTimeoutTimer);
        state.rxTimeoutTimer = setTimeout(() => {
          handleRxEnd();
        }, 1200);
      } else if (type === 0x03) {
        // PTT_END
        handleRxEnd();
        playChirp('ptt_release');
      }
    } catch (err) {
      console.error('MQTT message processing error:', err);
    }
  }

  function handleRxEnd() {
    clearTimeout(state.rxTimeoutTimer);
    state.isReceiving = false;
    state.activeRemotePeer = null;
    pttButton.classList.remove('receiving-state');
    if (!state.isTransmitting) {
      updateTransceiverUI('standby', 'SQUELCH CLOSED');
    }
  }

  // ========================================================
  // PTT PUSH-AND-HOLD INTERACTION HANDLERS
  // ========================================================
  function startTransmitting(e) {
    if (e) {
      e.preventDefault();
      e.stopPropagation();
    }
    
    if (state.isTransmitting) return;
    if (window.AndroidBridge) {
      state.isAudioInitialized = true;
    } else if (!state.isAudioInitialized) {
      initAudioEngine();
      return;
    }
    
    if (audioCtx && audioCtx.state === 'suspended') {
      audioCtx.resume();
    }
    
    state.isTransmitting = true;
    pttButton.classList.add('active-tx');
    pttOuterRing.classList.add('transmitting');
    pttSubText.textContent = 'TRANSMITTING (LIVE)';
    updateTransceiverUI('transmitting', `TX: CH-${String(state.channel).padStart(3, '0')} (BROADCASTING)`);
    
    playChirp('ptt_press');
    if (window.AndroidBridge) {
      window.AndroidBridge.startTransmitting();
    } else {
      sendControlPacket(0x01); // PTT_START
    }
  }

  function stopTransmitting(e) {
    if (e) {
      e.preventDefault();
      e.stopPropagation();
    }
    
    if (!state.isTransmitting) return;
    
    state.isTransmitting = false;
    pttButton.classList.remove('active-tx');
    pttOuterRing.classList.remove('transmitting');
    pttSubText.textContent = 'TRANSMIT (TX)';
    updateTransceiverUI('standby', 'SQUELCH CLOSED');
    
    if (window.AndroidBridge) {
      window.AndroidBridge.stopTransmitting();
    } else {
      sendControlPacket(0x03); // PTT_END
    }
    playChirp('ptt_release');
  }

  function updateTransceiverUI(mode, detailText) {
    transceiverState.className = `hud-state ${mode}`;
    if (mode === 'transmitting') {
      transceiverState.textContent = '● TX ACTIVE (TRANSMITTING)';
    } else if (mode === 'receiving') {
      transceiverState.textContent = '● RX ACTIVE (RECEIVING)';
    } else {
      transceiverState.textContent = '● STANDBY (RX)';
    }
    activeSpeakerDisplay.textContent = detailText;
  }

  // Bind PTT Touch & Mouse events with cancellation protection
  pttButton.addEventListener('pointerdown', startTransmitting);
  window.addEventListener('pointerup', stopTransmitting);
  window.addEventListener('pointercancel', stopTransmitting);
  pttButton.addEventListener('contextmenu', (e) => e.preventDefault());

  // Desktop keyboard spacebar shortcut for PTT
  let spacePressed = false;
  window.addEventListener('keydown', (e) => {
    if (e.code === 'Space' && !spacePressed && document.activeElement.tagName !== 'INPUT') {
      spacePressed = true;
      startTransmitting(e);
    }
  });
  window.addEventListener('keyup', (e) => {
    if (e.code === 'Space' && spacePressed) {
      spacePressed = false;
      stopTransmitting(e);
    }
  });

  // ========================================================
  // CHANNEL & FREQUENCY LOGIC
  // ========================================================
  function setChannel(newCh, suppressAudio) {
    const oldCh = state.channel;
    state.channel = Math.max(0, Math.min(999, newCh));
    localStorage.setItem('sentry_channel', state.channel);
    
    updateChannelDisplay();
    resubscribeChannels(oldCh);
    if (window.AndroidBridge) {
      window.AndroidBridge.setChannel(state.channel);
    }
    
    if (!suppressAudio) playChirp('channel_click');
  }

  function stepChannel(delta) {
    let next = state.channel + delta;
    if (next > 999) next = 0;
    if (next < 0) next = 999;
    setChannel(next);
  }

  function updateChannelDisplay() {
    const padded = String(state.channel).padStart(3, '0');
    dHundreds.textContent = padded[0];
    dTens.textContent = padded[1];
    dOnes.textContent = padded[2];
    
    // Standard UHF synthetic frequency formula
    const freq = (446.000 + (state.channel * 0.00125)).toFixed(4);
    freqDisplay.textContent = `${freq} MHz`;
    
    // Update squad mode subtext
    modeSquad.querySelector('.mode-sub').textContent = `CH-${padded} ONLY`;
  }

  stepDown10.addEventListener('click', () => stepChannel(-10));
  stepDown1.addEventListener('click', () => stepChannel(-1));
  stepUp1.addEventListener('click', () => stepChannel(1));
  stepUp10.addEventListener('click', () => stepChannel(10));

  // ========================================================
  // MODE SELECTION (ALL-CALL VS SQUAD LOCK)
  // ========================================================
  function setMode(newMode) {
    state.mode = newMode;
    localStorage.setItem('sentry_mode', newMode);
    
    if (newMode === 'all-call') {
      modeAllCall.classList.add('active');
      modeSquad.classList.remove('active');
    } else {
      modeSquad.classList.add('active');
      modeAllCall.classList.remove('active');
    }
    
    resubscribeChannels();
    if (window.AndroidBridge) {
      window.AndroidBridge.setMode(newMode === 'all-call');
    }
    playChirp('channel_click');
  }

  modeAllCall.addEventListener('click', () => setMode('all-call'));
  modeSquad.addEventListener('click', () => setMode('squad'));

  function updateSquadSecurityUI() {
    if (!state.squadPin) {
      squadSecStatus.className = 'sec-val open';
      squadSecStatus.textContent = 'PUBLIC / OPEN';
      pinBtnText.textContent = 'SET PIN';
    } else {
      squadSecStatus.className = 'sec-val secured';
      squadSecStatus.textContent = `SECURED (PIN: ****)`;
      pinBtnText.textContent = 'EDIT PIN';
    }
  }

  // ========================================================
  // SPEAKER & VOLUME CONTROLS
  // ========================================================
  speakerToggleBtn.addEventListener('click', () => {
    state.speakerEnabled = !state.speakerEnabled;
    localStorage.setItem('sentry_speaker', state.speakerEnabled);
    
    if (state.speakerEnabled) {
      speakerToggleBtn.classList.remove('muted');
      speakerIconOn.classList.remove('hidden');
      speakerIconOff.classList.add('hidden');
      speakerLabel.textContent = 'SPKR ON';
    } else {
      speakerToggleBtn.classList.add('muted');
      speakerIconOn.classList.add('hidden');
      speakerIconOff.classList.remove('hidden');
      speakerLabel.textContent = 'MUTED';
    }
    updateVolumeGain();
    playChirp('channel_click');
  });

  volumeSlider.addEventListener('input', (e) => {
    state.volume = parseInt(e.target.value, 10);
    volumePercent.textContent = `${state.volume}%`;
    localStorage.setItem('sentry_volume', state.volume);
    updateVolumeGain();
  });

  chirpToggleBtn.addEventListener('click', () => {
    state.chirpEnabled = !state.chirpEnabled;
    localStorage.setItem('sentry_chirp', state.chirpEnabled);
    chirpToggleBtn.classList.toggle('active', state.chirpEnabled);
    if (state.chirpEnabled) playChirp('ptt_release');
  });

  // ========================================================
  // LIVE OSCILLOSCOPE & LED VU METER ANIMATION LOOP
  // ========================================================
  function startOscilloscope() {
    const dataArray = new Uint8Array(analyser ? analyser.frequencyBinCount : 128);
    
    function draw() {
      requestAnimationFrame(draw);
      
      const width = scopeCanvas.width;
      const height = scopeCanvas.height;
      scopeCtx.clearRect(0, 0, width, height);
      
      let averageLevel = 0;
      
      if (analyser && (state.isTransmitting || state.isReceiving)) {
        analyser.getByteTimeDomainData(dataArray);
        
        let sum = 0;
        for (let i = 0; i < dataArray.length; i++) {
          sum += Math.abs(dataArray[i] - 128);
        }
        averageLevel = sum / dataArray.length;
        
        // Scope trace color
        scopeCtx.lineWidth = 2.2;
        if (state.isTransmitting) {
          scopeCtx.strokeStyle = '#ff5252';
          scopeCtx.shadowColor = '#ff5252';
        } else {
          scopeCtx.strokeStyle = '#00ff88';
          scopeCtx.shadowColor = '#00ff88';
        }
        scopeCtx.shadowBlur = 8;
        
        scopeCtx.beginPath();
        const sliceWidth = width / dataArray.length;
        let x = 0;
        
        for (let i = 0; i < dataArray.length; i++) {
          const v = dataArray[i] / 128.0;
          const y = (v * height) / 2;
          if (i === 0) {
            scopeCtx.moveTo(x, y);
          } else {
            scopeCtx.lineTo(x, y);
          }
          x += sliceWidth;
        }
        scopeCtx.stroke();
      } else {
        // Idle ambient scan line
        scopeCtx.lineWidth = 1.2;
        scopeCtx.strokeStyle = 'rgba(0, 229, 255, 0.4)';
        scopeCtx.shadowColor = '#00e5ff';
        scopeCtx.shadowBlur = 4;
        
        scopeCtx.beginPath();
        scopeCtx.moveTo(0, height / 2);
        
        const time = Date.now() * 0.003;
        for (let x = 0; x < width; x += 4) {
          const y = (height / 2) + Math.sin(x * 0.05 + time) * 2;
          scopeCtx.lineTo(x, y);
        }
        scopeCtx.stroke();
      }
      
      // Update LED VU Meter
      const litBarCount = Math.min(16, Math.floor((averageLevel / 35.0) * 16));
      vuBars.forEach((bar, idx) => {
        if (idx < litBarCount) {
          bar.classList.add('lit');
        } else {
          bar.classList.remove('lit');
        }
      });
    }
    
    draw();
  }

  // ========================================================
  // NUMPAD MODAL (DIRECT 3-DIGIT CHANNEL ENTRY)
  // ========================================================
  channelDrumTrigger.addEventListener('click', () => {
    state.numpadValue = String(state.channel).padStart(3, '0');
    numpadBuffer.textContent = state.numpadValue;
    channelModal.classList.add('active');
    playChirp('channel_click');
  });

  closeChannelModal.addEventListener('click', () => {
    channelModal.classList.remove('active');
  });

  document.querySelectorAll('.num-key[data-digit]').forEach(btn => {
    btn.addEventListener('click', () => {
      const digit = btn.getAttribute('data-digit');
      if (state.numpadValue.length >= 3) {
        state.numpadValue = digit;
      } else {
        state.numpadValue += digit;
      }
      numpadBuffer.textContent = state.numpadValue.padStart(3, '0');
      playChirp('channel_click');
    });
  });

  numpadClear.addEventListener('click', () => {
    state.numpadValue = '0';
    numpadBuffer.textContent = '000';
    playChirp('channel_click');
  });

  numpadBack.addEventListener('click', () => {
    state.numpadValue = state.numpadValue.slice(0, -1) || '0';
    numpadBuffer.textContent = state.numpadValue.padStart(3, '0');
    playChirp('channel_click');
  });

  confirmChannelBtn.addEventListener('click', () => {
    const val = parseInt(state.numpadValue, 10) || 0;
    setChannel(val);
    channelModal.classList.remove('active');
  });

  // ========================================================
  // SETTINGS & SQUAD PIN MODAL
  // ========================================================
  settingsBtn.addEventListener('click', openSettings);
  callsignBtn.addEventListener('click', openSettings);
  quickPinBtn.addEventListener('click', openSettings);

  function openSettings() {
    callsignInput.value = state.callsign;
    squadPinInput.value = state.squadPin;
    brokerSelect.value = state.broker;
    localPeerId.textContent = state.clientId;
    settingsModal.classList.add('active');
    playChirp('channel_click');
  }

  closeSettingsModal.addEventListener('click', () => {
    settingsModal.classList.remove('active');
  });

  saveSettingsBtn.addEventListener('click', () => {
    const oldCallsign = state.callsign;
    const oldPin = state.squadPin;
    const oldBroker = state.broker;
    
    state.callsign = callsignInput.value.trim().toUpperCase() || 'SENTRY-01';
    state.squadPin = squadPinInput.value.trim();
    state.broker = brokerSelect.value;
    
    localStorage.setItem('sentry_callsign', state.callsign);
    localStorage.setItem('sentry_squad_pin', state.squadPin);
    localStorage.setItem('sentry_broker', state.broker);
    
    callsignDisplay.textContent = state.callsign;
    updateSquadSecurityUI();
    settingsModal.classList.remove('active');
    
    if (oldBroker !== state.broker) {
      if (mqttClient) mqttClient.disconnect();
      connectMqtt();
    } else if (oldPin !== state.squadPin) {
      resubscribeChannels();
    }
    
    playChirp('activate');
  });

  // ========================================================
  // SCREEN WAKE LOCK (ALWAYS-ON DISPLAY)
  // ========================================================
  let wakeLock = null;

  async function requestWakeLock() {
    if (!('wakeLock' in navigator) || !state.alwaysOn) {
      updateWakeLockUI(false);
      return;
    }
    try {
      wakeLock = await navigator.wakeLock.request('screen');
      wakeLock.addEventListener('release', () => {
        if (state.alwaysOn && document.visibilityState === 'visible') {
          // Re-acquire if released by system
          setTimeout(requestWakeLock, 1000);
        } else {
          updateWakeLockUI(false);
        }
      });
      updateWakeLockUI(true);
    } catch (err) {
      console.warn('Screen Wake Lock request failed:', err);
      updateWakeLockUI(false);
    }
  }

  function releaseWakeLock() {
    if (wakeLock) {
      wakeLock.release().catch(() => {});
      wakeLock = null;
    }
    updateWakeLockUI(false);
  }

  function updateWakeLockUI(isActive) {
    if (!wakeLockBtn) return;
    wakeLockBtn.classList.toggle('active', isActive);
    const textEl = wakeLockBtn.querySelector('.wake-text');
    if (textEl) {
      textEl.textContent = isActive ? 'AWAKE' : 'SLEEP';
    }
  }

  wakeLockBtn.addEventListener('click', async () => {
    state.alwaysOn = !state.alwaysOn;
    localStorage.setItem('sentry_always_on', state.alwaysOn);
    if (state.alwaysOn) {
      await requestWakeLock();
    } else {
      releaseWakeLock();
    }
    playChirp('channel_click');
  });

  // Re-acquire wake lock when phone display turns on or user switches back to browser tab
  document.addEventListener('visibilitychange', async () => {
    if (document.visibilityState === 'visible' && state.alwaysOn) {
      await requestWakeLock();
    }
  });

  // ========================================================
  // START OVERLAY & INITIALIZATION
  // ========================================================
  activateAudioBtn.addEventListener('click', async () => {
    await initAudioEngine();
    if (state.alwaysOn) {
      await requestWakeLock();
    }
    startOverlay.classList.add('fade-out');
    setTimeout(() => {
      startOverlay.style.display = 'none';
    }, 400);
  });

  // ========================================================
  // TRANSCEIVER ARCHITECTURE (CLOUD VS OFF-GRID)
  // ========================================================
  const transceiverCloudBtn = document.getElementById('transceiverCloudBtn');
  const transceiverOffgridBtn = document.getElementById('transceiverOffgridBtn');
  const transceiverHintText = document.getElementById('transceiverHintText');

  function setTransceiverArchitecture(mode) {
    if (mode === 'offgrid') {
      transceiverOffgridBtn?.classList.add('active');
      transceiverCloudBtn?.classList.remove('active');
      if (transceiverHintText) {
        transceiverHintText.textContent = 'Off-Grid Direct Radio active (Google Nearby BLE + Wi-Fi Direct). No internet or cellular needed.';
      }
      if (window.AndroidBridge) {
        window.AndroidBridge.setTransceiver('offgrid');
      }
    } else {
      transceiverCloudBtn?.classList.add('active');
      transceiverOffgridBtn?.classList.remove('active');
      if (transceiverHintText) {
        transceiverHintText.textContent = 'Cloud Mesh provides worldwide voice comms interoperable with Web browsers and Apple iPhones.';
      }
      if (window.AndroidBridge) {
        window.AndroidBridge.setTransceiver('cloud');
      }
    }
    playChirp('channel_click');
  }

  transceiverCloudBtn?.addEventListener('click', () => setTransceiverArchitecture('cloud'));
  transceiverOffgridBtn?.addEventListener('click', () => setTransceiverArchitecture('offgrid'));

  // ========================================================
  // NATIVE ANDROID BRIDGE CALLBACKS
  // ========================================================
  window.onNativeState = (mode, callsign, ch) => {
    if (mode === 'receiving') {
      updateTransceiverUI('receiving', `RX: ${callsign} on CH-${ch}`);
      state.isReceiving = true;
    } else {
      updateTransceiverUI('standby', 'SQUELCH CLOSED');
      state.isReceiving = false;
    }
  };

  window.onNativeStatus = (statusText) => {
    let modeClass = 'online';
    const upper = (statusText || '').toUpperCase();
    if (upper.includes('CONNECTING') || upper.includes('RETRYING') || upper.includes('TUNING')) {
      modeClass = 'connecting';
    } else if (upper.includes('OFFLINE') || upper.includes('DISCONNECTED')) {
      modeClass = 'offline';
    }
    updateNetStatus(modeClass, statusText);
  };

  window.onNativePtt = (isDown) => {
    if (isDown) {
      startTransmitting();
    } else {
      stopTransmitting();
    }
  };

  // Initialize UI values
  callsignDisplay.textContent = state.callsign;
  volumeSlider.value = state.volume;
  volumePercent.textContent = `${state.volume}%`;
  chirpToggleBtn.classList.toggle('active', state.chirpEnabled);
  setChannel(state.channel, true);
  setMode(state.mode);
  updateSquadSecurityUI();
  updateWakeLockUI(state.alwaysOn);

  // Check if running inside Android Native App WebView
  if (window.AndroidBridge) {
    // Native app auto-dismisses start overlay
    setTimeout(() => {
      startOverlay.classList.add('fade-out');
      setTimeout(() => {
        startOverlay.style.display = 'none';
      }, 400);
      initAudioEngine().catch(() => {});
    }, 300);

    // Sync state with native service
    try {
      if (window.AndroidBridge.getStatus) {
        window.onNativeStatus(window.AndroidBridge.getStatus());
      }
      window.AndroidBridge.setCallsign(state.callsign);
      window.AndroidBridge.setSquadPin(state.squadPin);
      window.AndroidBridge.setChannel(state.channel);
      window.AndroidBridge.setMode(state.mode === 'all-call');
      window.AndroidBridge.setVolume(state.volume);
    } catch (e) {
      console.warn('Native bridge sync error:', e);
    }
  } else {
    // In browser / PWA mode, connect via JS Paho MQTT
    connectMqtt();
  }

  // Test / preview helper
  if (new URLSearchParams(location.search).has('autostart')) {
    startOverlay.style.display = 'none';
    state.isAudioInitialized = true;
    startOscilloscope();
  }

})();
