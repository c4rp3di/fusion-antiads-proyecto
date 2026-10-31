package com.vigia.adblock

/** Scripts del laboratorio. Sin el carácter dólar para que Kotlin no los tome por plantillas. */
object LaboratorioJs {

    /** Respuesta «no hay anuncio» a una petición VAST. */
    const val VAST_VACIO = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><VAST version=\"3.0\"></VAST>"
    const val VMAP_VACIO = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><vmap:VMAP xmlns:vmap=\"http://www.iab.net/videosuite/vmap\" version=\"1.0\"></vmap:VMAP>"

    /** Google Publisher Tag: acepta todas las llamadas, no hace nada y ejecuta la cola googletag.cmd. */
    val STUB_GPT = """
(function(){
  var w=window, q=(w.googletag&&w.googletag.cmd)||[], chain;
  chain=new Proxy(function(){},{
    get:function(t,p){ if(p==='then'||p===Symbol.toPrimitive) return undefined; if(p==='toString') return function(){return ''}; return chain; },
    apply:function(){ return chain; },
    construct:function(){ return chain; }
  });
  var cmd={push:function(){ for(var i=0;i<arguments.length;i++){ try{ if(typeof arguments[i]==='function') arguments[i].call(w); }catch(e){} } return 0; }};
  var g={cmd:cmd,apiReady:true,pubadsReady:true,_loaded_:true,
    pubads:function(){return chain}, companionAds:function(){return chain}, content:function(){return chain},
    defineSlot:function(){return chain}, defineOutOfPageSlot:function(){return chain}, sizeMapping:function(){return chain},
    enableServices:function(){}, display:function(){}, destroySlots:function(){return true}, openConsole:function(){},
    getVersion:function(){return 'antiads-stub'}, setAdIframeTitle:function(){}};
  w.googletag=new Proxy(g,{get:function(t,p){ if(p in t) return t[p]; if(p==='then') return undefined; return chain; }});
  for(var i=0;i<q.length;i++){ cmd.push(q[i]); }
})();
"""

    /** Google IMA SDK: cada petición de anuncios termina en «adError», que los reproductores tratan como «sigue con el vídeo». */
    val STUB_IMA = """
(function(){
  var w=window; w.google=w.google||{};
  function lenient(o){ return new Proxy(o,{get:function(t,p){ if(p in t) return t[p]; if(p==='then') return undefined; return function(){ return undefined; }; }}); }
  function Err(){ this.getErrorCode=function(){return 1009}; this.getVastErrorCode=function(){return 1009}; this.getMessage=function(){return 'AntiAds: sin anuncios'}; this.getType=function(){return 'adError'}; this.getInnerError=function(){return null}; this.toString=function(){return 'AdError 1009'}; }
  function Ev(t){ this.type=t; this.getError=function(){return new Err()}; this.getAd=function(){return null}; this.getAdData=function(){return {}}; this.getUserRequestContext=function(){return null}; }
  function Em(){ this._l={}; }
  Em.prototype.addEventListener=function(t,f){ (this._l[t]=this._l[t]||[]).push(f); };
  Em.prototype.removeEventListener=function(t,f){ var a=this._l[t]||[], i=a.indexOf(f); if(i>=0) a.splice(i,1); };
  Em.prototype._fire=function(t){ var a=(this._l[t]||[]).slice(), e=new Ev(t); for(var i=0;i<a.length;i++){ try{ a[i](e); }catch(x){} } };
  function Settings(){}
  Settings.prototype=lenient({});
  function Loader(){ Em.call(this); }
  Loader.prototype=Object.create(Em.prototype);
  Loader.prototype.requestAds=function(){ var s=this; setTimeout(function(){ s._fire('adError'); },0); };
  Loader.prototype.contentComplete=function(){};
  Loader.prototype.destroy=function(){};
  Loader.prototype.getSettings=function(){ return new Settings(); };
  function Container(){}
  Container.prototype.initialize=function(){}; Container.prototype.destroy=function(){};
  function Plain(){}
  w.google.ima=lenient({
    AdDisplayContainer:Container, AdsLoader:Loader, AdsRequest:Plain, AdsRenderingSettings:Plain, ImaSdkSettings:Settings,
    settings:new Settings(), VERSION:'3.0.0',
    AdsManagerLoadedEvent:{Type:{ADS_MANAGER_LOADED:'adsManagerLoaded'}},
    AdErrorEvent:{Type:{AD_ERROR:'adError'}},
    AdEvent:{Type:{ALL_ADS_COMPLETED:'allAdsCompleted',CONTENT_PAUSE_REQUESTED:'contentPauseRequested',CONTENT_RESUME_REQUESTED:'contentResumeRequested',LOADED:'loaded',STARTED:'started',COMPLETE:'complete',SKIPPED:'skip',AD_ERROR:'adError'}},
    ViewMode:{NORMAL:'normal',FULLSCREEN:'fullscreen'},
    AdError:{ErrorCode:{VAST_EMPTY_RESPONSE:1009,UNKNOWN_AD_RESPONSE:1010}}
  });
})();
"""

    val STUB_ADSBYGOOGLE = """
(function(){ var a=window.adsbygoogle=window.adsbygoogle||[]; a.loaded=true; a.push=function(){ return 0; }; })();
"""

    /** YouTube: quita los datos de anuncios de las respuestas del reproductor (la técnica de los bloqueadores de navegador). */
    val YT_PRUNE = """
(function(){
  var K=['adPlacements','playerAds','adSlots','adBreakHeartbeatParams'];
  function prune(o){
    if(o&&typeof o==='object'){
      for(var i=0;i<K.length;i++){ if(K[i] in o){ try{ delete o[K[i]]; }catch(e){} } }
      if(o.playerResponse) prune(o.playerResponse);
      if(o.response) prune(o.response);
    }
    return o;
  }
  var pj=JSON.parse;
  JSON.parse=function(){ return prune(pj.apply(this,arguments)); };
  if(window.Response&&Response.prototype){
    var rj=Response.prototype.json;
    Response.prototype.json=function(){ return rj.apply(this,arguments).then(prune); };
    var rt=Response.prototype.text;
    Response.prototype.text=function(){
      return rt.apply(this,arguments).then(function(t){
        try{ if(t&&t.charAt(0)==='{'&&t.indexOf('adPlacements')>=0) return JSON.stringify(prune(pj(t))); }catch(e){}
        return t;
      });
    };
  }
  var v;
  try{ Object.defineProperty(window,'ytInitialPlayerResponse',{get:function(){return v;},set:function(x){ v=prune(x); },configurable:true}); }catch(e){}
})();
"""

    /** YouTube: si aun así sale un anuncio, silenciarlo, adelantarlo al final y pulsar «Saltar». */
    val SKIP_YT = """
(function(){
  var BT=['.ytp-skip-ad-button','.ytp-ad-skip-button','.ytp-ad-skip-button-modern','.ytp-ad-skip-button-container button'];
  var tocado=false, mudoAntes=false;
  setInterval(function(){
    if(document.hidden) return;
    try{
      var p=document.querySelector('.html5-video-player');
      var anuncio=!!p&&(p.classList.contains('ad-showing')||p.classList.contains('ad-interrupting'));
      var v=document.querySelector('video.html5-main-video')||document.querySelector('video');
      if(anuncio&&v){
        if(!tocado){ tocado=true; mudoAntes=v.muted; }
        v.muted=true;
        try{ v.playbackRate=16; }catch(e){}
        if(isFinite(v.duration)&&v.duration>0&&v.currentTime<v.duration-0.2){ v.currentTime=v.duration; }
        for(var i=0;i<BT.length;i++){ var b=document.querySelector(BT[i]); if(b) b.click(); }
        var c=document.querySelector('.ytp-ad-overlay-close-button'); if(c) c.click();
      } else if(tocado&&v){
        tocado=false; v.muted=mudoAntes;
        try{ v.playbackRate=1; }catch(e){}
      }
    }catch(e){}
  },500);
})();
"""

    /** Otras webs: pulsa los botones «Saltar anuncio» que aparezcan dentro de contenedores de publicidad. */
    val SKIP_GENERICO = """
(function(){
  var RE=/^\s*(skip( ad| ads)?|saltar( anuncio| publicidad)?|omitir( anuncio)?|close ad|cerrar anuncio)\s*[>»]*\s*${'$'}/i;
  var AD=/(^|[\s_-])(ad|ads|advert|preroll|pre-roll|vast|ima|sponsor)([\s_-]|${'$'})/i;
  setInterval(function(){
    if(document.hidden) return;
    try{
      var bs=document.querySelectorAll('button,a,div[role=button],span[role=button]');
      for(var i=0;i<bs.length;i++){
        var b=bs[i], t=(b.textContent||b.getAttribute('aria-label')||'').trim();
        if(!t||t.length>24||!RE.test(t)) continue;
        var n=b, enAnuncio=false;
        for(var d=0;n&&d<6;d++,n=n.parentElement){ if(AD.test((n.className&&n.className.toString())||'')||AD.test(n.id||'')){ enAnuncio=true; break; } }
        if(enAnuncio) b.click();
      }
    }catch(e){}
  },1000);
})();
"""

    /**
     * Telemetría desde la página (solo con «Telemetría de página» activada). Sin temporizadores en marcha: se apunta lo que
     * ocurre y se envía en un solo aviso cada 3 s como máximo, a una ruta del propio sitio que el proxy intercepta.
     */
    val TELEMETRIA = """
(function(){
  var Q=[], T=null, H=location.host;
  function cut(u){ try{ var a=new URL(u,location.href); return a.host+a.pathname.slice(0,70); }catch(e){ return String(u).slice(0,70); } }
  function fl(){ T=null; if(!Q.length) return; var b=JSON.stringify({h:H,p:location.pathname.slice(0,50),e:Q}); Q=[]; try{ navigator.sendBeacon('/__antiads/t',b); }catch(e){} }
  function add(o){ if(Q.length<40){ Q.push(o); if(!T) T=setTimeout(fl,3000); } }
  addEventListener('error',function(e){
    var t=e.target;
    if(t&&t!==window&&(t.src||t.href)) add({k:'recurso',t:t.tagName,u:cut(t.src||t.href)});
    else add({k:'js',m:String(e.message).slice(0,140),u:cut(e.filename||'')});
  },true);
  addEventListener('unhandledrejection',function(e){ add({k:'promesa',m:String((e.reason&&e.reason.message)||e.reason).slice(0,140)}); });
  document.addEventListener('securitypolicyviolation',function(e){ add({k:'csp',d:e.violatedDirective,u:cut(e.blockedURI)}); });
  ['loadedmetadata','play','playing','pause','ended','error','waiting','stalled'].forEach(function(n){
    document.addEventListener(n,function(e){
      var v=e.target;
      if(!v||(v.tagName!=='VIDEO'&&v.tagName!=='AUDIO')) return;
      add({k:'media',ev:n,d:isFinite(v.duration)?Math.round(v.duration*10)/10:-1,t:Math.round(v.currentTime*10)/10,c:v.error?v.error.code:0,u:cut(v.currentSrc||v.src||'')});
    },true);
  });
  try{
    new PerformanceObserver(function(l){
      l.getEntries().forEach(function(r){
        if(r.transferSize===0&&r.decodedBodySize>0&&(r.initiatorType==='video'||r.initiatorType==='script'||r.decodedBodySize>200000)) add({k:'cache',u:cut(r.name),b:r.decodedBodySize});
      });
    }).observe({type:'resource',buffered:true});
  }catch(e){}
  addEventListener('pagehide',fl);
  document.addEventListener('visibilitychange',function(){ if(document.hidden) fl(); });
  add({k:'pagina',ua:navigator.userAgent.slice(0,140),red:(navigator.connection&&navigator.connection.effectiveType)||''});
})();
"""

    /** Ocultar restos de anuncios propios de YouTube (además de los genéricos). */
    const val CSS_YOUTUBE =
        "#masthead-ad,#player-ads,ytd-ad-slot-renderer,ytd-in-feed-ad-layout-renderer,ytd-display-ad-renderer," +
            "ytd-promoted-sparkles-web-renderer,ytd-banner-promo-renderer,.ytp-ad-overlay-container,.ytp-ad-text-overlay," +
            "ytm-promoted-sparkles-web-renderer,ad-slot-renderer{display:none!important}"
}
