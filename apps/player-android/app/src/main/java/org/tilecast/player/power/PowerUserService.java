package org.tilecast.player.power;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import java.io.*;
import java.util.concurrent.TimeUnit;

/** Fixed-function Shizuku UserService; never runs with root or accepts arbitrary commands. */
public final class PowerUserService extends IPowerControl.Stub {
  private static final String TAG="TilecastShizukuPower";
  public PowerUserService(){}
  @Override public int uid(){return Process.myUid();}
  @Override public void destroy(){System.exit(0);}
  private boolean active(long deadline){return PowerDeadline.active(deadline,SystemClock.elapsedRealtime());}
  private String run(long deadline,String... args) throws Exception {
    if(!active(deadline))throw new IOException("Expired power request");
    java.lang.Process p=new ProcessBuilder(args).redirectErrorStream(true).start();
    if(!p.waitFor(3,TimeUnit.SECONDS)){p.destroyForcibly();throw new IOException("Power command timeout");}
    String line;
    try(BufferedReader r=new BufferedReader(new InputStreamReader(p.getInputStream()))){line=r.readLine();}
    if(p.exitValue()!=0)throw new IOException("Power command failed");
    return line==null?"":line.trim();
  }
  @Override public synchronized boolean setAwake(boolean awake,long deadline) {
    if(uid()!=2000||!active(deadline))return false;
    try {
      String cvte=run(deadline,"/system/bin/settings","get","global","fake_standby_switch");
      if("true".equalsIgnoreCase(cvte)||"1".equals(cvte)){
        String before=run(deadline,"/system/bin/settings","get","global","IS_FAKE_STANDBY_STATUS");
        if(!"0".equals(before)&&!"1".equals(before))return false; // Never blindly toggle.
        if(("0".equals(before))==awake)return true;
        run(deadline,"/system/bin/input","keyevent","26");
        Thread.sleep(600);
        String after=run(deadline,"/system/bin/settings","get","global","IS_FAKE_STANDBY_STATUS");
        return (awake?"0":"1").equals(after);
      }
      run(deadline,"/system/bin/input","keyevent",awake?"224":"223");
      return true; // Sent, not physical-panel confirmation.
    }catch(Exception e){Log.e(TAG,"Power command failed",e);return false;}
  }
}
