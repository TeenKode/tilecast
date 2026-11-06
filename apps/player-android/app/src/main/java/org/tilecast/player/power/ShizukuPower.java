package org.tilecast.player.power;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.util.Log;
import java.util.concurrent.atomic.AtomicBoolean;
import rikka.shizuku.Shizuku;

/** Asynchronous, opt-in binding to an ADB-shell-only fixed power service. */
public final class ShizukuPower {
  private static final String TAG="TilecastShizukuPower";
  public interface Result { void completed(boolean success); }
  private ShizukuPower(){}
  public static boolean ready(){
    try{return Shizuku.pingBinder()&&Shizuku.getUid()==2000&&
      Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED;}
    catch(RuntimeException e){return false;}
  }
  public static String status(){
    try{
      if(!Shizuku.pingBinder())return "Shizuku is not running";
      if(Shizuku.getUid()!=2000)return "Shizuku must run as ADB shell (UID 2000)";
      return ready()?"Ready: ADB shell (UID 2000)":"Authorize Tilecast in Shizuku";
    }catch(RuntimeException e){return "Shizuku unavailable";}
  }
  public static void connect(Activity activity){
    try{
      if(Shizuku.pingBinder()&&Shizuku.getUid()==2000){
        if(!ready())Shizuku.requestPermission(7119);
        return;
      }
    }catch(RuntimeException e){Log.w(TAG,"Shizuku connection",e);}
    Intent manager=activity.getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
    if(manager!=null)activity.startActivity(manager);
  }
  public static boolean setAwake(Context context,boolean awake,Result callback){
    if(!ready())return false;
    Handler handler=new Handler(Looper.getMainLooper());
    long deadline=SystemClock.elapsedRealtime()+PowerDeadline.TIMEOUT_MS;
    Shizuku.UserServiceArgs args=new Shizuku.UserServiceArgs(new ComponentName(context,PowerUserService.class))
      .tag("tilecast-power").processNameSuffix("power").daemon(true).version(1);
    AtomicBoolean done=new AtomicBoolean(false);
    final ServiceConnection[] conn=new ServiceConnection[1];
    final Runnable[] timeout=new Runnable[1];
    conn[0]=new ServiceConnection(){
      @Override public void onServiceConnected(ComponentName name,IBinder binder){
        new Thread(()->{
          boolean ok=false;
          try{
            if(!done.get()&&PowerDeadline.active(deadline,SystemClock.elapsedRealtime())){
              IPowerControl service=IPowerControl.Stub.asInterface(binder);
              ok=service.uid()==2000&&service.setAwake(awake,deadline);
            }
          }catch(RemoteException|RuntimeException e){Log.e(TAG,"Power call failed",e);}
          final boolean success=ok;
          handler.post(()->complete(success));
        },"tilecast-shizuku-power").start();
      }
      private void complete(boolean success){
        if(!done.compareAndSet(false,true))return;
        handler.removeCallbacks(timeout[0]);
        try{Shizuku.unbindUserService(args,conn[0],false);}catch(RuntimeException ignored){}
        if(callback!=null)callback.completed(success);
      }
      @Override public void onServiceDisconnected(ComponentName name){complete(false);}
    };
    timeout[0]=()->{
      if(!done.compareAndSet(false,true))return;
      Log.w(TAG,"Power request timed out");
      try{Shizuku.unbindUserService(args,conn[0],false);}catch(RuntimeException ignored){}
      if(callback!=null)callback.completed(false);
    };
    handler.postDelayed(timeout[0],PowerDeadline.TIMEOUT_MS);
    try{Shizuku.bindUserService(args,conn[0]);return true;}
    catch(RuntimeException e){
      handler.removeCallbacks(timeout[0]);
      Log.e(TAG,"Unable to bind Shizuku UserService",e);
      return false;
    }
  }
}
