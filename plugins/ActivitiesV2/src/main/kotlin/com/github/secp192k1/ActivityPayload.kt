package com.github.secp192k1

// JS injected into the activity WebView on start. Emulates the host transport that the
// embedded-app-sdk expects: a stubbed ServiceWorker, an outbound bridge (`window.postMessage`
// array frames `AliucordRPC.send`), and an inbound hook `window.__hostDeliver`
// Insane??
internal object ActivityPayload {
    val PAYLOAD = """
        (function(){
        if(window.__aliucordPayload)return;window.__aliucordPayload=true;
        var L=[];
        var act={state:'activated',scriptURL:location.origin+'/sw.js',postMessage:function(){},addEventListener:function(){},removeEventListener:function(){}};
        var reg={scope:location.origin+'/',active:act,installing:null,waiting:null,navigationPreload:{getState:function(){return Promise.resolve({enabled:false})}},update:function(){return Promise.resolve()},unregister:function(){return Promise.resolve(true)},addEventListener:function(){},removeEventListener:function(){}};
        var sw={controller:act,ready:Promise.resolve(reg),startMessages:function(){},register:function(){return Promise.resolve(reg);},getRegistration:function(){return Promise.resolve(reg);},getRegistrations:function(){return Promise.resolve([reg]);},addEventListener:function(){},removeEventListener:function(){}};
        window.ServiceWorkerContainer=window.ServiceWorkerContainer||function(){};
        try{if(!('serviceWorker' in navigator))Object.defineProperty(navigator,'serviceWorker',{value:sw,configurable:true});}catch(e){}
        var add=window.addEventListener.bind(window);
        window.addEventListener=function(t,l,o){if(t==='message')L.push(l);return add(t,l,o);};
        var post=window.postMessage.bind(window);
        window.postMessage=function(m,o,t){if(Array.isArray(m)){try{AliucordRPC.send(JSON.stringify(m));}catch(e){}return;}return post(m,o,t);};
        window.__hostDeliver=function(j){var d;try{d=JSON.parse(j);}catch(e){return;}var ev={data:d,origin:'https://discord.com',source:window,ports:[]};for(var i=0;i<L.length;i++){try{L[i](ev);}catch(e){}}if(typeof window.onmessage==='function'){try{window.onmessage(ev);}catch(e){}}};
        })();
    """.replace(Regex("\\s*\\n\\s*"), "")
}
