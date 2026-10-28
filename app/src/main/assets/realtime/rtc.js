/* Local-only WebRTC transport. No credentials, provider URLs or profile data enter this page. */
(() => {
  'use strict';
  const bridge = () => window.MebotNative;
  const post = (message) => {
    try { bridge()?.postMessage(JSON.stringify(message)); } catch (_) { /* surface is closing */ }
  };
  let pc = null;
  let channel = null;
  let stream = null;
  let microphoneMuted = false;
  let remoteAudio = null;

  const stateName = (value) => ({
    new: 'new', connecting: 'connecting', connected: 'connected',
    disconnected: 'disconnected', failed: 'failed', closed: 'closed',
  }[value] || 'failed');
  const channelState = (value) => ({
    connecting: 'connecting', open: 'open', closing: 'closing', closed: 'closed',
  }[value] || 'closed');
  const failureCode = (error) => {
    if (error && error.name === 'NotAllowedError') return 'microphone_permission_required';
    if (error && error.name === 'NotFoundError') return 'microphone_unavailable';
    if (error && error.name === 'NotReadableError') return 'microphone_unavailable';
    if (error && error.name === 'NotSupportedError') return 'transport_not_available';
    if (error && error.name === 'SecurityError') return 'transport_not_available';
    return 'temporary_error';
  };
  const result = (id, ok, value, code) => post({ type: 'result', id, ok, value: value === undefined ? null : value, code: code || '' });
  const requirePeer = () => { if (!pc) throw Object.assign(new Error('Peer missing'), { name: 'NotSupportedError' }); };

  function closeLocal() {
    microphoneMuted = true;
    if (channel) {
      try { channel.onopen = channel.onclose = channel.onclosing = channel.onmessage = null; channel.close(); } catch (_) {}
    }
    channel = null;
    if (stream) {
      for (const track of stream.getTracks()) { try { track.enabled = false; track.stop(); } catch (_) {} }
    }
    stream = null;
    if (pc) {
      try { pc.onconnectionstatechange = pc.onicegatheringstatechange = pc.ontrack = null; pc.close(); } catch (_) {}
    }
    pc = null;
    if (remoteAudio) { try { remoteAudio.pause(); remoteAudio.srcObject = null; } catch (_) {} }
    remoteAudio = null;
  }

  async function dispatch(message) {
    if (!message || typeof message.id !== 'string' || message.id.length > 64 || typeof message.op !== 'string') return;
    const id = message.id;
    try {
      let value = null;
      switch (message.op) {
        case 'addMicrophoneTrack': {
          if (pc) throw Object.assign(new Error('Already started'), { name: 'NotSupportedError' });
          // Use the official WebRTC sample's default ICE configuration; no third-party
          // STUN/TURN hostname or custom remote signaling endpoint is supplied by the app.
          pc = new RTCPeerConnection();
          remoteAudio = document.getElementById('remote-audio');
          pc.onconnectionstatechange = () => post({ type: 'peer', state: stateName(pc ? pc.connectionState : 'closed') });
          pc.ontrack = async (event) => {
            if (!pc || !remoteAudio || !event.track || event.track.kind !== 'audio') return;
            const tracks = (event.streams && event.streams[0]) ? event.streams[0].getAudioTracks() : [event.track];
            if (!tracks.length) return;
            remoteAudio.srcObject = (event.streams && event.streams[0]) ? event.streams[0] : new MediaStream(tracks);
            try {
              await remoteAudio.play();
              post({ type: 'remoteAudio', ready: true });
            } catch (_) {
              post({ type: 'error', code: 'audio_playback_unavailable' });
            }
          };
          stream = await navigator.mediaDevices.getUserMedia({ audio: true, video: false });
          for (const track of stream.getAudioTracks()) {
            track.enabled = !microphoneMuted;
            pc.addTrack(track, stream);
          }
          value = true;
          break;
        }
        case 'createDataChannel': {
          requirePeer();
          if (message.label !== 'oai-events') throw Object.assign(new Error('Unsupported channel'), { name: 'NotSupportedError' });
          channel = pc.createDataChannel('oai-events');
          channel.onopen = () => post({ type: 'dataChannel', state: channelState('open') });
          channel.onclose = () => post({ type: 'dataChannel', state: channelState('closed') });
          channel.onclosing = () => post({ type: 'dataChannel', state: channelState('closing') });
          channel.onmessage = (event) => {
            if (typeof event.data === 'string' && event.data.length <= 128000) post({ type: 'data', message: event.data });
          };
          post({ type: 'dataChannel', state: channelState(channel.readyState) });
          value = true;
          break;
        }
        case 'createOffer': {
          requirePeer();
          const offer = await pc.createOffer();
          value = { sdp: offer.sdp || '' };
          break;
        }
        case 'setLocalDescription': {
          requirePeer();
          if (typeof message.sdp !== 'string' || message.sdp.length > 512000) throw Object.assign(new Error('Invalid SDP'), { name: 'NotSupportedError' });
          await pc.setLocalDescription({ type: 'offer', sdp: message.sdp });
          value = true;
          break;
        }
        case 'awaitIceGatheringComplete': {
          requirePeer();
          if (pc.iceGatheringState !== 'complete') {
            const activePeer = pc;
            await new Promise((resolve, reject) => {
              const timeout = Math.max(1000, Math.min(30000, Number(message.timeoutMillis) || 20000));
              const timer = setTimeout(() => { activePeer.removeEventListener('icegatheringstatechange', check); reject(Object.assign(new Error('ICE timeout'), { name: 'TimeoutError' })); }, timeout);
              const check = () => {
                if (activePeer.iceGatheringState === 'complete') {
                  clearTimeout(timer); activePeer.removeEventListener('icegatheringstatechange', check); resolve();
                }
              };
              activePeer.addEventListener('icegatheringstatechange', check);
              check();
            });
          }
          value = pc.localDescription && pc.localDescription.sdp ? pc.localDescription.sdp : '';
          break;
        }
        case 'setRemoteDescription': {
          requirePeer();
          if (typeof message.sdp !== 'string' || message.sdp.length > 512000) throw Object.assign(new Error('Invalid SDP'), { name: 'NotSupportedError' });
          await pc.setRemoteDescription({ type: 'answer', sdp: message.sdp });
          value = true;
          break;
        }
        case 'setMicrophoneMuted': {
          microphoneMuted = message.muted === true;
          if (stream) for (const track of stream.getAudioTracks()) track.enabled = !microphoneMuted;
          value = true;
          break;
        }
        case 'sendDataChannelMessage': {
          if (!channel || channel.readyState !== 'open' || typeof message.message !== 'string' || message.message.length > 4000) {
            value = false;
          } else {
            channel.send(message.message);
            value = true;
          }
          break;
        }
        case 'close': {
          closeLocal();
          value = true;
          break;
        }
        default: throw Object.assign(new Error('Unsupported operation'), { name: 'NotSupportedError' });
      }
      result(id, true, value, '');
    } catch (error) {
      result(id, false, null, failureCode(error));
      if (message.op === 'addMicrophoneTrack' || message.op === 'createDataChannel' || message.op === 'setRemoteDescription') {
        post({ type: 'error', code: failureCode(error) });
      }
    }
  }

  // WebMessageListener supplies this origin-bound object before page scripts run.
  // Native commands arrive as messages; this page never exposes a Java bridge.
  const native = bridge();
  if (native) native.onmessage = (event) => {
    try { dispatch(JSON.parse(event.data)); } catch (_) { /* malformed native command */ }
  };
  post({ type: 'ready' });
})();
