"use strict";
window.RufflePlayer = window.RufflePlayer || {};
window.RufflePlayer.config = {
  publicPath: "/vendor/", autoplay: "on", unmuteOverlay: "hidden",
  splashScreen: false, preloader: false, contextMenu: "off", letterbox: "on",
  allowScriptAccess: false, allowNetworking: "none", openUrlMode: "deny",
  showSwfDownload: false, logLevel: "error", warnOnUnsupportedContent: false
};
const runtimeScript = document.createElement("script");
runtimeScript.src = "/vendor/ruffle.js";
runtimeScript.onerror = () => window.chrome.webview.postMessage({type:"error",message:"Cannot load fixed Ruffle runtime"});
runtimeScript.onload = async () => {
  try {
    const player = window.RufflePlayer.newest().createPlayer();
    document.getElementById("player").appendChild(player);
    await player.ruffle().load({url:"/game.swf",allowScriptAccess:false});
    window.flashBoxPause = () => { player.ruffle().suspend(); return player.ruffle().suspended; };
    window.flashBoxResume = () => { player.ruffle().resume(); return player.ruffle().isPlaying; };
    player.focus();
    window.chrome.webview.postMessage({type:"ready"});
  } catch (error) {
    window.chrome.webview.postMessage({type:"error",message:String(error).slice(0,800)});
  }
};
const tapScript = document.createElement("script");tapScript.src="/audio-tap.js";
tapScript.onload=()=>document.head.appendChild(runtimeScript);
tapScript.onerror=()=>window.chrome.webview.postMessage({type:"error",message:"Missing game audio tap"});
document.head.appendChild(tapScript);
