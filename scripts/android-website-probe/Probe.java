package org.tilecast.probe;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.File;
import org.json.JSONArray;
import org.json.JSONObject;

/** Temporary signed instrumentation; reads only public remote-page diagnostics. */
public final class Probe extends Instrumentation {
    private boolean preview;
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); preview=arguments!=null && "true".equals(arguments.getString("preview")); start(); }
    @Override public void onStart() {
        final Bundle result = new Bundle();
        try {
            ActivityMonitor monitor = addMonitor("org.tilecast.player.MainActivity", null, false);
            Intent intent = new Intent().setClassName("org.tilecast.player.test", "org.tilecast.player.MainActivity");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getTargetContext().startActivity(intent);
            final Activity activity = waitForMonitorWithTimeout(monitor, 15000);
            if (activity == null) throw new IllegalStateException("No player activity");
            Thread.sleep(15000);
            try {
                Class<?> api=Class.forName("org.tilecast.player.core.PlayerCoreNative");
                Object instance=api.getField("INSTANCE").get(null);
                JSONObject status=new JSONObject((String)api.getMethod("nativeStatus",long.class).invoke(instance,1L));
                result.putString("renderer",String.valueOf(status.optJSONObject("renderer")));
                File file=new File(getTargetContext().getFilesDir(),"player-core/state.db");
                try(SQLiteDatabase db=SQLiteDatabase.openDatabase(file.toString(),null,SQLiteDatabase.OPEN_READONLY); Cursor c=db.rawQuery("SELECT version,document FROM manifest_target",null)) {
                    JSONArray rows=new JSONArray();
                    while(c.moveToNext()) {
                        JSONObject document=new JSONObject(c.getString(1));
                        JSONArray widgets=document.optJSONArray("widgets"), safe=new JSONArray();
                        if(widgets!=null)for(int i=0;i<widgets.length();i++) {
                            JSONObject widget=widgets.getJSONObject(i), p=widget.optJSONObject("presentation");
                            safe.put(new JSONObject().put("id",widget.optString("id")).put("provider",widget.optString("provider")).put("kind",p==null?null:p.optString("kind")).put("required",p==null?null:p.optJSONObject("requiredCapabilities")).put("webMode",p==null||p.optJSONObject("web")==null?null:p.optJSONObject("web").optString("mode")));
                        }
                        rows.put(new JSONObject().put("version",c.getLong(0)).put("widgets",safe));
                    }
                    result.putString("targetRequirements",rows.toString());
                }
            } catch(Throwable error) {result.putString("manifestProbeError",error.toString());}
            if (preview) {
                runOnMainSync(() -> {
                    WebView remote=find(activity.getWindow().getDecorView());
                    if(remote!=null) remote.evaluateJavascript("window.FadeOutScreen=false;window.FadeOutScreenCnt=0;window.Redraw=true",null);
                });
                Thread.sleep(1500);
                result.putString("preview", "Local working-hours preview only; restart restores site policy");
            }
            final CountDownLatch done = new CountDownLatch(1);
            runOnMainSync(() -> {
                WebView remote = find(activity.getWindow().getDecorView());
                if (remote == null) { result.putString("probe", "No Rasp WebView"); done.countDown(); return; }
                result.putString("remoteUrl", remote.getUrl());
                result.putString("nativeSize", remote.getWidth() + "x" + remote.getHeight());
                result.putBoolean("javascript", remote.getSettings().getJavaScriptEnabled());
                result.putFloat("alpha", remote.getAlpha());
                result.putString("nativeParams", remote.getLayoutParams().width+"x"+remote.getLayoutParams().height);
                result.putString("settings", "wide="+remote.getSettings().getUseWideViewPort()+",overview="+remote.getSettings().getLoadWithOverviewMode()+",layout="+remote.getSettings().getLayoutAlgorithm()+",textZoom="+remote.getSettings().getTextZoom());
                remote.evaluateJavascript("JSON.stringify({compat:document.compatMode,root:{client:document.documentElement.clientHeight,rect:document.documentElement.getBoundingClientRect().toJSON(),style:getComputedStyle(document.documentElement).height},body:{client:document.body.clientHeight,rect:document.body.getBoundingClientRect().toJSON(),style:getComputedStyle(document.body).height},elements:Array.from(document.body.children).map(e=>({tag:e.tagName,id:e.id,height:e.getBoundingClientRect().height,style:e.getAttribute('style')})),visual:visualViewport?{height:visualViewport.height,width:visualViewport.width,scale:visualViewport.scale}:null})", value -> {result.putString("layout",value);});
                remote.evaluateJavascript("JSON.stringify({href:location.href,width:innerWidth,height:innerHeight,dpr:devicePixelRatio,title:document.title,visibility:document.documentElement.style.visibility,ready:document.readyState,canvas:!!window.Canvas,nika:!!window.NIKA,fade:window.FadeOutScreen,redraw:window.Redraw,work:window.WORK_PERIOD,bg:document.body.style.background,scripts:Array.from(document.scripts).map(s=>s.src),main:document.getElementById('MainDiv')?document.getElementById('MainDiv').getBoundingClientRect().toJSON():null,canvasSize:window.Canvas?[Canvas.width,Canvas.height]:null})", value -> {result.putString("page",value);done.countDown();});
            });
            if (!done.await(10, TimeUnit.SECONDS)) result.putString("timeout", "JavaScript callback timeout");
            if (preview) { sendStatus(0,result); Thread.sleep(20000); }
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) { result.putString("probeError", error.toString()); finish(Activity.RESULT_CANCELED,result); }
    }
    private static WebView find(View view) {
        if (view instanceof WebView) {
            String url = ((WebView)view).getUrl();
            if (url != null && url.startsWith("http://rasp.server.local/")) return (WebView)view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup)view;
            for (int i=0;i<group.getChildCount();i++) {WebView found=find(group.getChildAt(i));if(found!=null)return found;}
        }
        return null;
    }
}
