package com.growthos.jarvis;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.webkit.*;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;

/** Thin, secure WebView shell with explicit, preview-only Android share handoff. */
public final class MainActivity extends Activity {
    private static final int AUDIO_REQUEST=41, WAKE_REQUEST=42, FILE_REQUEST=43, SPEECH_REQUEST=44;
    private WebView webView; private AdvancedIntelligenceBridge advancedBridge; private PermissionRequest pendingPermissionRequest; private ValueCallback<Uri[]> fileCallback;
    private String pendingShareJson; private SpeechBridge speech; private String pendingSpeechLang;
    private final BroadcastReceiver wakeReceiver=new BroadcastReceiver(){ public void onReceive(Context c,Intent i){
        if(!WakeWordService.ACTION_COMMAND.equals(i.getAction())) return; String command=i.getStringExtra(WakeWordService.EXTRA_COMMAND);
        if(command==null||command.trim().isEmpty()) return; String n=command.trim().toLowerCase(Locale.ROOT);
        if(n.matches("whats?app\\s+(open|launch|start)(?:\\s+chey)?\\s*")){try{startActivity(getPackageManager().getLaunchIntentForPackage("com.whatsapp"));}catch(Exception e){Toast.makeText(MainActivity.this,"WhatsApp is not installed",Toast.LENGTH_SHORT).show();}return;}
        dispatch("jarvis-native-command", command.trim());
    }};
    @Override protected void onCreate(Bundle state){ super.onCreate(state); getWindow().setStatusBarColor(0xff02070d); getWindow().setNavigationBarColor(0xff02070d);
        webView=new WebView(this); setContentView(webView); WebSettings s=webView.getSettings(); s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true); s.setMediaPlaybackRequiresUserGesture(true); s.setAllowFileAccess(false); s.setAllowContentAccess(true); s.setAllowFileAccessFromFileURLs(false); s.setAllowUniversalAccessFromFileURLs(false); s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW); s.setSafeBrowsingEnabled(true);
        advancedBridge=new AdvancedIntelligenceBridge(this,(event,payload)->dispatch(event,payload),()->onAppPage);
        speech=new SpeechBridge(this,json->dispatch("jarvis-native-speech",json));
        webView.addJavascriptInterface(new NativeBridge(),"JarvisNative");
        webView.addJavascriptInterface(advancedBridge,"JarvisAdvanced"); webView.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){return r.isForMainFrame()&&openExternalIfNeeded(r.getUrl());}
            @Override public boolean shouldOverrideUrlLoading(WebView v,String u){return openExternalIfNeeded(Uri.parse(u));}
            @Override public void onPageStarted(WebView v,String u,android.graphics.Bitmap f){onAppPage=u!=null&&isAppHost(Uri.parse(u));super.onPageStarted(v,u,f);}
            @Override public void onPageFinished(WebView v,String u){onAppPage=u!=null&&isAppHost(Uri.parse(u));super.onPageFinished(v,u);dispatchPendingShare();copyAiKey();}
        });
        webView.setWebChromeClient(new WebChromeClient(){
            @Override public void onPermissionRequest(final PermissionRequest r){runOnUiThread(()->{if(hasRecordAudioPermission()&&isTrustedOrigin(r.getOrigin()))r.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});else if(!hasRecordAudioPermission()){pendingPermissionRequest=r;requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},AUDIO_REQUEST);}else r.deny();});}
            @Override public boolean onShowFileChooser(WebView v,ValueCallback<Uri[]> cb,FileChooserParams p){if(fileCallback!=null)fileCallback.onReceiveValue(null);fileCallback=cb;try{startActivityForResult(p.createIntent(),FILE_REQUEST);}catch(Exception e){fileCallback=null;return false;}return true;}
        });
        String url=BuildConfig.JARVIS_START_URL; if(!url.startsWith("https://")){Toast.makeText(this,"KALKI URL must use HTTPS",Toast.LENGTH_LONG).show();return;} webView.loadUrl(url); handleIntent(getIntent()); }
    @Override protected void onNewIntent(Intent i){super.onNewIntent(i);setIntent(i);handleIntent(i);}
    private void handleIntent(Intent i){
        if(i==null)return; String a=i.getAction(); Uri u=i.getData();
        if(Intent.ACTION_VIEW.equals(a)&&u!=null){dispatch("jarvis-native-deeplink",u.toString());return;}
        if(!Intent.ACTION_SEND.equals(a)&&!Intent.ACTION_SEND_MULTIPLE.equals(a))return;
        try { JSONObject p=new JSONObject(); p.put("mimeType",i.getType()==null?"application/octet-stream":i.getType());
            CharSequence title=i.getCharSequenceExtra(Intent.EXTRA_TITLE), text=i.getCharSequenceExtra(Intent.EXTRA_TEXT);
            if(title!=null)p.put("title",title.toString()); if(text!=null)p.put("text",text.toString()); JSONArray fs=new JSONArray(); ClipData clip=i.getClipData();
            if(clip!=null)for(int n=0;n<clip.getItemCount();n++)addShareUri(fs,clip.getItemAt(n).getUri(),i.getType()); else {Uri stream=i.getParcelableExtra(Intent.EXTRA_STREAM);if(stream!=null)addShareUri(fs,stream,i.getType());}
            p.put("files",fs);p.put("previewOnly",true);pendingShareJson=p.toString();dispatchPendingShare();
        } catch(Exception ignored){Toast.makeText(this,"Unable to preview shared content",Toast.LENGTH_SHORT).show();}
    }
    private void addShareUri(JSONArray fs,Uri u,String type)throws Exception{if(u==null)return;JSONObject f=new JSONObject();f.put("uri",u.toString());f.put("name",u.getLastPathSegment()==null?"Shared item":u.getLastPathSegment());f.put("mimeType",type==null?"application/octet-stream":type);fs.put(f);}
    /** Lets voice commands answer questions with the Gemini key the user saved in KALKI settings. Only from KALKI's own page; stays on this phone. */
    private void copyAiKey(){if(webView==null)return;String u=webView.getUrl();if(u==null||!"rakesh-1143.github.io".equalsIgnoreCase(Uri.parse(u).getHost()))return;
        webView.evaluateJavascript("(function(){try{return localStorage.getItem('jarvis_api_key')||''}catch(e){return ''}})()",v->{try{if(v!=null&&v.length()>2)GeminiClient.saveKey(this,new org.json.JSONArray("["+v+"]").getString(0));}catch(Exception ignored){}});}
    @Override protected void onPause(){copyAiKey();super.onPause();}
    private void dispatchPendingShare(){if(webView==null||pendingShareJson==null)return;String q=JSONObject.quote(pendingShareJson);webView.post(()->webView.evaluateJavascript("window.dispatchEvent(new CustomEvent('kalki-share',{detail:JSON.parse("+q+")}));",null));}
    private void dispatch(String event,String value){if(webView==null)return;webView.post(()->webView.evaluateJavascript("window.dispatchEvent(new CustomEvent("+JSONObject.quote(event)+",{detail:"+JSONObject.quote(value)+"}));",null));}
    private String JSONObjectQuote(String s){return org.json.JSONObject.quote(s);}
    @Override protected void onStart(){super.onStart();IntentFilter f=new IntentFilter(WakeWordService.ACTION_COMMAND);// Android 12 has no "not exported" flag, so require our own signature-only permission: other apps cannot send fake commands.
        if(Build.VERSION.SDK_INT>=33)registerReceiver(wakeReceiver,f,RECEIVER_NOT_EXPORTED);else registerReceiver(wakeReceiver,f,getPackageName()+".permission.INTERNAL",null);
        // Back on screen: let the page's timers and animations run again (paused in onStop).
        if(webView!=null){webView.onResume();webView.resumeTimers();}
        // If "Hey Jarvis" was switched on but the listener died (app update, app disabled/re-enabled,
        // KALKI no longer the default assistant), opening the app brings it back. The app is in the
        // foreground here, so Android allows starting the microphone service.
        if(WakeWordService.isEnabled(this)&&!WakeWordService.running)WakeWordService.start(this,null);}
    @Override protected void onStop(){try{unregisterReceiver(wakeReceiver);}catch(Exception ignored){}
        // Off screen: stop the page's JavaScript timers and animations so they use no CPU or battery in the background.
        // "Hey Jarvis" keeps working: it runs in WakeWordService, not in the page.
        if(webView!=null){webView.onPause();webView.pauseTimers();}super.onStop();}
    private boolean openExternalIfNeeded(Uri u){String scheme=u.getScheme();if(("http".equalsIgnoreCase(scheme)||"https".equalsIgnoreCase(scheme))&&isAppHost(u))return false;try{startActivity(new Intent(Intent.ACTION_VIEW,u));}catch(Exception e){Toast.makeText(this,"No app can open this link",Toast.LENGTH_SHORT).show();}return true;}
    /** KALKI's own pages stay in the app; other sites (YouTube, Google sign-in, ...) open in their app or the browser. */
    private boolean isAppHost(Uri u){String host=u.getHost(),appHost=Uri.parse(BuildConfig.JARVIS_START_URL).getHost();return host!=null&&host.equalsIgnoreCase(appHost);}
    /** Only KALKI's own site may use the microphone; other sites (for example inside a frame) are refused. */
    private boolean isTrustedOrigin(Uri o){return o!=null&&"https".equalsIgnoreCase(o.getScheme())&&isAppHost(o);}
    /** True while the page shown is KALKI's own site. The native bridges ignore calls from any other page. */
    private volatile boolean onAppPage;
    private boolean hasRecordAudioPermission(){return checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED;}
    private boolean hasCameraPermission(){return checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED;}
    private boolean hasNotificationPermission(){return Build.VERSION.SDK_INT<33||checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED;}
    private void requestWakePermissions(){ArrayList<String> p=new ArrayList<>();if(!hasRecordAudioPermission())p.add(Manifest.permission.RECORD_AUDIO);if(Build.VERSION.SDK_INT>=33&&!hasNotificationPermission())p.add(Manifest.permission.POST_NOTIFICATIONS);if(p.isEmpty())startWakeService();else requestPermissions(p.toArray(new String[0]),WAKE_REQUEST);}
    private void startNativeSpeech(String lang){if(speech==null)return;if(hasRecordAudioPermission()){speech.start(lang);return;}pendingSpeechLang=lang==null?"":lang;requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},SPEECH_REQUEST);}
    private void startWakeService(){if(!hasRecordAudioPermission())return;WakeWordService.setEnabled(this,true);}
    private void stopWakeService(){WakeWordService.setEnabled(this,false);}
    private final class NativeBridge{
        @JavascriptInterface public void enableWakeWord(){if(!onAppPage)return;runOnUiThread(MainActivity.this::requestWakePermissions);}
        @JavascriptInterface public void requestScreenCapture(){if(!onAppPage)return;advancedBridge.requestScreenCapture();}
        @JavascriptInterface public void requestCameraPermission(){if(!onAppPage)return;advancedBridge.requestCameraPermission();}
        @JavascriptInterface public void chooseSourceFile(){if(!onAppPage)return;advancedBridge.chooseSourceFile();}
        @JavascriptInterface public void launchSafeIntent(String action){if(!onAppPage)return;advancedBridge.launchSafeIntent(action);}
        @JavascriptInterface public boolean speechAvailable(){if(!onAppPage)return false;return speech!=null&&speech.isAvailable();}
        @JavascriptInterface public void startListening(String lang){if(!onAppPage)return;runOnUiThread(()->startNativeSpeech(lang));}
        @JavascriptInterface public void stopListening(){if(!onAppPage)return;runOnUiThread(()->{if(speech!=null)speech.stop();});}
        @JavascriptInterface public void speak(String text,String lang){if(!onAppPage)return;runOnUiThread(()->{if(speech!=null)speech.speak(text,lang);});}
        @JavascriptInterface public void stopSpeaking(){if(!onAppPage)return;runOnUiThread(()->{if(speech!=null)speech.stopSpeaking();});}
        @JavascriptInterface public void disableWakeWord(){if(!onAppPage)return;runOnUiThread(MainActivity.this::stopWakeService);}
        @JavascriptInterface public String permissionStatus(){if(!onAppPage)return "{}";return "{\"microphone\":"+hasRecordAudioPermission()+",\"camera\":"+hasCameraPermission()+",\"notifications\":"+hasNotificationPermission()+"}";}
        @JavascriptInterface public void openWhatsApp(){if(!onAppPage)return;runOnUiThread(()->{try{Intent i=getPackageManager().getLaunchIntentForPackage("com.whatsapp");if(i==null)i=getPackageManager().getLaunchIntentForPackage("com.whatsapp.w4b");if(i==null)throw new ActivityNotFoundException();startActivity(i);}catch(Exception e){if(!onAppPage)return;Toast.makeText(MainActivity.this,"WhatsApp is not installed",Toast.LENGTH_SHORT).show();}});}
        @JavascriptInterface public void openCamera(){if(!onAppPage)return;runOnUiThread(()->{try{startActivity(new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE));}catch(Exception e){if(!onAppPage)return;Toast.makeText(MainActivity.this,"No camera app is available",Toast.LENGTH_SHORT).show();}});}
        @JavascriptInterface public void openNotificationSettings(){if(!onAppPage)return;runOnUiThread(()->startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,getPackageName())));}
        @JavascriptInterface public void openBatterySettings(){if(!onAppPage)return;runOnUiThread(()->{try{startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));}catch(Exception e){if(!onAppPage)return;startActivity(new Intent(Settings.ACTION_SETTINGS));}});}
        @JavascriptInterface public void openAssistantSetup(){if(!onAppPage)return;runOnUiThread(()->startActivity(new Intent(MainActivity.this,AssistantSetupActivity.class)));}
        @JavascriptInterface public void openAppSettings(){if(!onAppPage)return;runOnUiThread(()->startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName()))));}
        @JavascriptInterface public String nativeVersion(){return "kalki-android-0.8.0-voice";}
    }
    @Override protected void onActivityResult(int req,int result,Intent data){super.onActivityResult(req,result,data);if(req==AdvancedIntelligenceBridge.SCREEN_CAPTURE_REQUEST&&advancedBridge!=null){advancedBridge.screenResult(result==RESULT_OK);return;}if(req==1203&&advancedBridge!=null){advancedBridge.fileResult(result==RESULT_OK);return;}if(req==FILE_REQUEST&&fileCallback!=null){Uri[] r=WebChromeClient.FileChooserParams.parseResult(result,data);fileCallback.onReceiveValue(r);fileCallback=null;}}
    @Override public void onRequestPermissionsResult(int c,String[] p,int[] r){super.onRequestPermissionsResult(c,p,r);if(c==SPEECH_REQUEST){String l=pendingSpeechLang;pendingSpeechLang=null;if(hasRecordAudioPermission())speech.start(l);else dispatch("jarvis-native-speech","{\"type\":\"error\",\"error\":\"not-allowed\"}");return;}if(c==AUDIO_REQUEST&&pendingPermissionRequest!=null){if(hasRecordAudioPermission()&&isTrustedOrigin(pendingPermissionRequest.getOrigin()))pendingPermissionRequest.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});else pendingPermissionRequest.deny();pendingPermissionRequest=null;}else if(c==WAKE_REQUEST&&hasRecordAudioPermission()&&hasNotificationPermission())startWakeService();else if(c==WAKE_REQUEST&&webView!=null)webView.evaluateJavascript("document.getElementById('wake-word-toggle')?.click();document.getElementById('wake-word-toggle')&&(document.getElementById('wake-word-toggle').checked=false);",null);}
    @Override public void onBackPressed(){if(webView!=null&&webView.canGoBack())webView.goBack();else super.onBackPressed();}
    @Override protected void onDestroy(){/* Jarvis keeps listening after the app closes; turn it off in Jarvis setup or the notification. */if(speech!=null)speech.destroy();if(fileCallback!=null)fileCallback.onReceiveValue(null);if(webView!=null){webView.removeJavascriptInterface("JarvisNative");webView.removeJavascriptInterface("JarvisAdvanced");webView.destroy();}super.onDestroy();}
}

