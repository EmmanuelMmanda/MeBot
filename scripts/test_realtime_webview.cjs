// Host-side fake WebRTC contract test; no browser, Android microphone, or network call.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

const assetDir = path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'realtime');
const html = fs.readFileSync(path.join(assetDir, 'index.html'), 'utf8');
const source = fs.readFileSync(path.join(assetDir, 'rtc.js'), 'utf8');
assert.match(html, /default-src 'none'/);
assert.match(html, /script-src 'self'/);
assert.match(html, /connect-src 'self'/);
assert.match(html, /webrtc 'allow'/);
assert.match(html, /frame-src 'none'/);
assert.match(html, /object-src 'none'/);
assert.match(html, /src="\/assets\/realtime\/rtc\.js"/);
assert.doesNotMatch(html, /<script[^>]+https?:/i);
assert.doesNotMatch(source, /https?:\/\/|wss?:\/\//i);
assert.doesNotMatch(source, /fetch\(|XMLHttpRequest|localStorage|sessionStorage/);

const sent = [];
const native = { onmessage: null, postMessage(message) { sent.push(JSON.parse(message)); } };
const microphoneTrack = { kind: 'audio', enabled: true, stopped: false,
  stop() { this.stopped = true; } };
const remoteTrack = { kind: 'audio' };
const mediaStream = {
  getAudioTracks() { return [microphoneTrack]; },
  getTracks() { return [microphoneTrack]; },
};
const sentChannelMessages = [];
let dataChannel;
let peer;
const fakeAudio = {
  srcObject: null,
  paused: false,
  async play() { this.paused = false; },
  pause() { this.paused = true; },
};
class FakeDataChannel {
  constructor(label) { this.label = label; this.readyState = 'connecting'; }
  send(message) { sentChannelMessages.push(message); }
  close() { this.readyState = 'closed'; this.onclose?.(); }
}
class FakePeerConnection {
  constructor(configuration) {
    this.configuration = configuration;
    this.connectionState = 'new';
    this.iceGatheringState = 'complete';
    this.localDescription = null;
    this.remoteDescription = null;
    this.tracks = [];
    this.listeners = new Map();
    peer = this;
  }
  addTrack(track, stream) { this.tracks.push({ track, stream }); }
  createDataChannel(label) {
    dataChannel = new FakeDataChannel(label);
    queueMicrotask(() => { dataChannel.readyState = 'open'; dataChannel.onopen?.(); });
    return dataChannel;
  }
  async createOffer() { return { type: 'offer', sdp: 'v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n' }; }
  async setLocalDescription(description) { this.localDescription = description; }
  async setRemoteDescription(description) {
    this.remoteDescription = description;
    this.ontrack?.({ streams: [mediaStream], track: remoteTrack });
  }
  addEventListener(name, listener) { this.listeners.set(name, listener); }
  removeEventListener(name) { this.listeners.delete(name); }
  close() { this.connectionState = 'closed'; this.closed = true; }
}

const context = vm.createContext({
  window: { MebotNative: native },
  navigator: { mediaDevices: { async getUserMedia(constraints) {
    assert.equal(constraints.audio, true);
    assert.equal(constraints.video, false);
    return mediaStream;
  } } },
  document: { getElementById(id) { assert.equal(id, 'remote-audio'); return fakeAudio; } },
  RTCPeerConnection: FakePeerConnection,
  MediaStream: class { constructor(tracks) { this.tracks = tracks; } },
  setTimeout, clearTimeout, Promise, JSON, Object, Error, Math, Number,
});
vm.runInContext(source, context, { filename: 'rtc.js' });
assert.equal(sent[0].type, 'ready');
assert.equal(typeof native.onmessage, 'function');

let nextId = 0;
async function command(op, params = {}) {
  const id = `test-${++nextId}`;
  native.onmessage({ data: JSON.stringify({ id, op, ...params }) });
  for (let attempt = 0; attempt < 100; attempt++) {
    const result = sent.find((message) => message.type === 'result' && message.id === id);
    if (result) return result;
    await new Promise((resolve) => setImmediate(resolve));
  }
  throw new Error(`No bounded result for ${op}`);
}

(async () => {
  const malformedCount = sent.length;
  native.onmessage({ data: 'not-json' });
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(sent.length, malformedCount, 'malformed bridge input is ignored');

  assert.equal((await command('addMicrophoneTrack')).ok, true);
  assert.equal(peer.configuration, undefined, 'uses the official WebRTC default ICE configuration');
  assert.equal(peer.tracks.length, 1);
  assert.equal((await command('createDataChannel', { label: 'oai-events' })).ok, true);
  assert.equal(dataChannel.label, 'oai-events');
  const offer = await command('createOffer');
  assert.equal(offer.ok, true);
  assert.match(offer.value.sdp, /m=audio/);
  assert.equal((await command('setLocalDescription', { sdp: offer.value.sdp })).ok, true);
  const gathered = await command('awaitIceGatheringComplete', { timeoutMillis: 1000 });
  assert.equal(gathered.ok, true);
  assert.match(gathered.value, /v=0/);
  assert.equal((await command('setRemoteDescription', { sdp: 'v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n' })).ok, true);
  await new Promise((resolve) => setImmediate(resolve));
  assert(sent.some((message) => message.type === 'remoteAudio' && message.ready === true));
  assert.equal(fakeAudio.paused, false);

  assert.equal((await command('setMicrophoneMuted', { muted: true })).ok, true);
  assert.equal(microphoneTrack.enabled, false);
  assert.equal((await command('setMicrophoneMuted', { muted: false })).ok, true);
  assert.equal(microphoneTrack.enabled, true);
  assert.equal((await command('sendDataChannelMessage', { message: '{"type":"response.cancel"}' })).value, true);
  assert.deepEqual(sentChannelMessages, ['{"type":"response.cancel"}']);

  assert.equal((await command('close')).ok, true);
  assert.equal(microphoneTrack.enabled, false);
  assert.equal(microphoneTrack.stopped, true, 'teardown stops every local capture track');
  assert.equal(dataChannel.readyState, 'closed', 'teardown closes data channel');
  assert.equal(peer.closed, true, 'teardown closes peer connection');
  assert.equal(fakeAudio.paused, true);
  assert.equal(fakeAudio.srcObject, null);
  console.log('WebView fake WebRTC asset contract: PASS');
})().catch((error) => { console.error(error); process.exitCode = 1; });
