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

/** Temporary signed instrumentation; reads only public remote-page diagnostics. */
public final class Probe extends Instrumentation {
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
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
            runOnMainSync(() -> {
                WebView remote = find(activity.getWindow().getDecorView());
                if (remote != null) {
                    result.putString("beforeParams", remote.getLayoutParams().width+"x"+remote.getLayoutParams().height);
                    remote.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                    remote.requestLayout();
                    remote.reload();
                }
            });
            Thread.sleep(5000);
            final CountDownLatch done = new CountDownLatch(1);
            runOnMainSync(() -> {
                WebView remote = find(activity.getWindow().getDecorView());
                if (remote == null) { result.putString("probe", "No Rasp WebView"); done.countDown(); return; }
                result.putString("remoteUrl", remote.getUrl());
                result.putString("nativeSize", remote.getWidth() + "x" + remote.getHeight());
                result.putBoolean("javascript", remote.getSettings().getJavaScriptEnabled());
                result.putFloat("alpha", remote.getAlpha());
                result.putString("settings", "wide="+remote.getSettings().getUseWideViewPort()+",overview="+remote.getSettings().getLoadWithOverviewMode()+",layout="+remote.getSettings().getLayoutAlgorithm()+",textZoom="+remote.getSettings().getTextZoom());
                remote.evaluateJavascript("JSON.stringify({compat:document.compatMode,root:{client:document.documentElement.clientHeight,rect:document.documentElement.getBoundingClientRect().toJSON(),style:getComputedStyle(document.documentElement).height},body:{client:document.body.clientHeight,rect:document.body.getBoundingClientRect().toJSON(),style:getComputedStyle(document.body).height},elements:Array.from(document.body.children).map(e=>({tag:e.tagName,id:e.id,height:e.getBoundingClientRect().height,style:e.getAttribute('style')})),visual:visualViewport?{height:visualViewport.height,width:visualViewport.width,scale:visualViewport.scale}:null})", value -> {result.putString("layout",value);});
                remote.evaluateJavascript("JSON.stringify({href:location.href,width:innerWidth,height:innerHeight,dpr:devicePixelRatio,title:document.title,visibility:document.documentElement.style.visibility,ready:document.readyState,canvas:!!window.Canvas,nika:!!window.NIKA,fade:window.FadeOutScreen,redraw:window.Redraw,work:window.WORK_PERIOD,bg:document.body.style.background,scripts:Array.from(document.scripts).map(s=>s.src),main:document.getElementById('MainDiv')?document.getElementById('MainDiv').getBoundingClientRect().toJSON():null,canvasSize:window.Canvas?[Canvas.width,Canvas.height]:null})", value -> {result.putString("page",value);done.countDown();});
            });
            if (!done.await(10, TimeUnit.SECONDS)) result.putString("timeout", "JavaScript callback timeout");
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
