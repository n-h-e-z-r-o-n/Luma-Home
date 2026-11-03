package com.lumahome.iconqa;
import android.app.*;import android.content.*;import android.os.*;import android.view.*;import android.widget.*;import java.lang.reflect.*;import java.util.*;
public class WallpaperTransitionInstrumentation extends Instrumentation {
 private int checks;private void check(boolean ok,String why){if(!ok)throw new AssertionError(why);checks++;}
 private Object field(Activity a,String name)throws Exception{Field f=a.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(a);}
 private void invoke(Activity a,String name)throws Exception{Method m=a.getClass().getDeclaredMethod(name);m.setAccessible(true);m.invoke(a);}
 private void main(Activity a,String method){runOnMainSync(()->{try{invoke(a,method);}catch(Exception e){throw new RuntimeException(e);}});}
 @Override public void onCreate(Bundle b){super.onCreate(b);start();}
 @Override public void onStart(){Bundle result=new Bundle();SharedPreferences prefs=null;String original=null;Activity activity=null;try{
  Intent i=new Intent().setClassName("com.lumahome.launcher","com.lumahome.launcher.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);final Activity a=startActivitySync(i);activity=a;waitForIdleSync();Thread.sleep(1200);
  prefs=getTargetContext().getSharedPreferences("luma_preferences",0);original=prefs.getString("background_image","");check("photo".equals(prefs.getString("background","")),"Test needs existing photo wallpaper");check(prefs.getString("wallpaper_gallery","").split(",").length>=2,"Test needs two saved wallpapers");
  final View root=(View)field(a,"root");final FrameLayout stage=(FrameLayout)field(a,"backgroundStage");final ImageView old=(ImageView)field(a,"wallpaperImageView");final View icon=(View)((List<?>)field(a,"appViews")).get(0);runOnMainSync(()->icon.requestFocus());final View focus=a.getCurrentFocus();final int children=stage.getChildCount();final float previousAlpha=old.getAlpha();
  main(a,"advanceWallpaperSlideshow");
  check(root==field(a,"root"),"Slideshow rebuilt root");check(stage==field(a,"backgroundStage"),"Slideshow replaced backdrop stage");check(icon==((List<?>)field(a,"appViews")).get(0),"Slideshow recreated icons");check(old.getParent()==stage,"Previous image removed before replacement loaded");check(Math.abs(old.getAlpha()-previousAlpha)<.01f,"Previous image faded before replacement ready");
  Thread.sleep(220);ImageView pending=(ImageView)field(a,"pendingWallpaperImage");check(pending!=null,"Missing crossfade layer");check(pending.getAlpha()<1f,"Transition skipped animation");
  Thread.sleep(1800);waitForIdleSync();check(root==field(a,"root"),"Palette extraction rebuilt page");check(icon==((List<?>)field(a,"appViews")).get(0),"Palette extraction replaced icons");check(a.getCurrentFocus()==focus,"Wallpaper transition lost focus");check(stage.getChildCount()==children,"Completed transition leaked layers");check(field(a,"pendingWallpaperImage")==null,"Completed transition retained pending layer");
  for(int n=0;n<3;n++){main(a,"advanceWallpaperSlideshow");Thread.sleep(90);check(stage.getChildCount()<=children+1,"Rapid transitions leaked background layers");}
  Thread.sleep(1800);check(stage.getChildCount()==children,"Rapid transitions did not settle");
  ImageView visible=(ImageView)field(a,"wallpaperImageView");float visibleAlpha=visible.getAlpha();String good=prefs.getString("background_image","");prefs.edit().putString("background_image","file:///does-not-exist-luma-wallpaper.jpg").commit();main(a,"updateBackgroundInPlace");Thread.sleep(500);check(visible.getParent()==stage&&Math.abs(visible.getAlpha()-visibleAlpha)<.01f,"Decode failure blanked existing image");prefs.edit().putString("background_image",good).commit();main(a,"updateBackgroundInPlace");
  result.putString("result","PASS: "+checks+" wallpaper transition checks");
 }catch(Throwable e){result.putString("result","FAIL: "+e);}finally{if(prefs!=null&&original!=null){prefs.edit().putString("background_image",original).commit();if(activity!=null)try{main(activity,"updateBackgroundInPlace");Thread.sleep(900);}catch(Throwable ignored){}}}finish(result.getString("result").startsWith("PASS")?-1:1,result);}
}