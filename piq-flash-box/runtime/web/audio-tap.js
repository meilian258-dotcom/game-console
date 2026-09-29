"use strict";
// Captures only this player's WebAudio graph. No microphone/desktop/loopback capture.
(() => {
  const connect = AudioNode.prototype.connect, disconnect = AudioNode.prototype.disconnect;
  const taps = new WeakMap(), linked = new WeakMap();
  AudioNode.prototype.connect = function(destination, ...args) {
    const result = connect.call(this, destination, ...args);
    const tap = taps.get(this.context);
    if (tap && destination === this.context.destination && !linked.has(this)) {
      connect.call(this, tap); linked.set(this, tap);
    }
    return result;
  };
  AudioNode.prototype.disconnect = function(...args) {
    const result = disconnect.apply(this, args), tap = linked.get(this);
    if (tap && (args.length === 0 || args[0] === this.context.destination)) {
      if (args.length) try { disconnect.call(this, tap); } catch (_) {}
      linked.delete(this);
    }
    return result;
  };
  const Original = window.AudioContext;
  window.AudioContext = new Proxy(Original, {construct(Target,args) {
    const context = new Target(...args), tap = context.createGain(); taps.set(context,tap);
    context.audioWorklet.addModule("/audio-worklet.js").then(() => {
      const worklet = new AudioWorkletNode(context,"piq-audio");
      connect.call(tap,worklet); connect.call(worklet,context.destination); // silent output, original route unchanged
      worklet.port.onmessage = event => {
        const bytes = new Uint8Array(event.data);
        if (bytes.length <= 2400) window.chrome.webview.postMessage({type:"audio",pcm:btoa(String.fromCharCode(...bytes))});
        worklet.port.postMessage("ack");
      };
    }).catch(error => window.chrome.webview.postMessage({type:"audio-error",message:String(error).slice(0,200)}));
    return context;
  }});
  if (window.webkitAudioContext) window.webkitAudioContext = window.AudioContext;
})();
