package com.github.secp192k1

import org.json.JSONObject

// Activity runs inside this html as in iframe to simulate how its done on desktop
// some activities like chess fail checks like `window.top !== window.self`
// Bridges the activity sdk parent postMessage transport like this:
//  TX: `AliucordRPC.send`
//  RX: `window.__hostDeliver`
// Even more insane???
internal object ActivityPayload {
    private const val URL_PLACEHOLDER = "__ACTIVITY_URL__"

    private val PAYLOAD = """
        <!DOCTYPE html>
        <html>
        <head>
        <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
        <style>html,body{margin:0;padding:0;width:100%;height:100%;overflow:hidden;background:#000}iframe{border:0;width:100%;height:100%;display:block}</style>
        </head>
        <body>
        <iframe id="activity" allow="autoplay; encrypted-media; fullscreen; picture-in-picture; camera; microphone; clipboard-write; clipboard-read; gamepad; accelerometer; gyroscope"></iframe>
        <script>
        (function(){
        var frame=document.getElementById('activity');
        window.addEventListener('message',function(ev){
        if(ev.source===frame.contentWindow&&Array.isArray(ev.data)){try{AliucordRPC.send(JSON.stringify(ev.data));}catch(e){}}
        });
        window.__hostDeliver=function(j){var d;try{d=JSON.parse(j);}catch(e){return;}try{frame.contentWindow.postMessage(d,'*');}catch(e){}};
        frame.src=$URL_PLACEHOLDER;
        })();
        </script>
        </body>
        </html>
    """ // no trimIndent(): Discord's bundled kotlin-stdlib crashes inside it (obfuscated code)

    internal fun hostPage(activityUrl: String) = PAYLOAD.replace(URL_PLACEHOLDER, JSONObject.quote(activityUrl))
}
