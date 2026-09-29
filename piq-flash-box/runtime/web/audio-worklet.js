"use strict";
class PiqAudio extends AudioWorkletProcessor {
  constructor() { super(); this.phase=0; this.samples=new Int16Array(1200); this.at=0; this.pending=0; this.port.onmessage=()=>{this.pending=Math.max(0,this.pending-1);}; }
  process(inputs) {
    const channels=inputs[0]; if (!channels.length) return true;
    for(let i=0;i<channels[0].length;i++) {
      let value=0; for(const channel of channels) value += channel[i] || 0;
      value=Math.max(-1,Math.min(1,value/channels.length)); this.phase+=24000;
      while(this.phase>=sampleRate) {
        this.phase-=sampleRate; this.samples[this.at++]=Math.round(value*32767);
        if(this.at===1200) {
          if(this.pending<2){this.port.postMessage(this.samples.buffer,[this.samples.buffer]);this.pending++;this.samples=new Int16Array(1200);}
          this.at=0;
        }
      }
    }
    return true; // output remains silence: no duplicate local playback
  }
}
registerProcessor("piq-audio",PiqAudio);
