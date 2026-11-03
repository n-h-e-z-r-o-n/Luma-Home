package com.lumahome.iconqa;
import android.app.*;import android.content.*;import android.os.*;import android.view.*;import android.widget.*;import java.lang.reflect.*;
public class ScreenMirroringInstrumentation extends Instrumentation {
 private int checks;private void check(boolean ok,String why){if(!ok)throw new AssertionError(why);checks++;}
 private boolean contains(View v,String t){if(v instanceof TextView && ((TextView)v).getText().toString().contains(t))return true;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)if(contains(g.getChildAt(i),t))return true;}return false;}
 @Override public void onCreate(Bundle b){super.onCreate(b);start();}
 @Override public void onStart(){Bundle result=new Bundle();try{
 Intent i=new Intent().setClassName("com.lumahome.launcher","com.lumahome.launcher.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);Activity a=startActivitySync(i);waitForIdleSync();
 Method resolver=a.getClass().getDeclaredMethod("mirroringConnectionSettings");resolver.setAccessible(true);Intent receiver=(Intent)resolver.invoke(a);check(receiver!=null,"Native receiver not visible to Luma");check("com.tcl.miracast.app.DeviceList".equals(receiver.getAction()),"Wrong receiver entry");check(!receiver.getAction().contains("test"),"Test menu exposed");
 runOnMainSync(()->{try{Method m=a.getClass().getDeclaredMethod("screenMirroringMenu");m.setAccessible(true);m.invoke(a);}catch(Exception e){throw new RuntimeException(e);}});waitForIdleSync();
 Field f=a.getClass().getDeclaredField("activeBlade");f.setAccessible(true);Dialog dialog=(Dialog)f.get(a);check(dialog!=null&&dialog.isShowing(),"Mirroring panel missing");View root=dialog.getWindow().getDecorView();
 check(contains(root,"Win + K"),"Windows instructions missing");check(contains(root,"Connection settings"),"Native receiver settings missing");check(!contains(root,"QR"),"Unexpected QR flow");
 result.putString("result","PASS: "+checks+" mirroring integration checks");finish(-1,result);
 }catch(Throwable e){result.putString("result","FAIL: "+e);finish(1,result);}}
}