package com.lumahome.iconqa;
import android.app.*;import android.content.*;import android.os.*;import android.view.*;import android.widget.*;import java.lang.reflect.*;import java.util.*;
public class UiContinuityInstrumentation extends Instrumentation {
 private int checks;private void check(boolean ok,String why){if(!ok)throw new AssertionError(why);checks++;}
 private Object get(Activity a,String n)throws Exception{Field f=a.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(a);}
 private void invoke(Activity a,String n)throws Exception{Method m=a.getClass().getDeclaredMethod(n);m.setAccessible(true);m.invoke(a);}
 private void main(Activity a,String n){runOnMainSync(()->{try{invoke(a,n);}catch(Exception e){throw new RuntimeException(e);}});}
 private void page(Activity a,boolean drawer){runOnMainSync(()->{try{Method m=a.getClass().getDeclaredMethod("switchLauncherPage",boolean.class);m.setAccessible(true);m.invoke(a,drawer);}catch(Exception e){throw new RuntimeException(e);}});}
 @Override public void onCreate(Bundle b){super.onCreate(b);start();}
 @Override public void onStart(){Bundle out=new Bundle();try{
 Intent i=new Intent().setClassName("com.lumahome.launcher","com.lumahome.launcher.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);Activity a=startActivitySync(i);waitForIdleSync();Thread.sleep(1100);
 final View home=(View)((List<?>)get(a,"appViews")).get(0);runOnMainSync(()->home.requestFocus());waitForIdleSync();Thread.sleep(400);String selected=(String)get(a,"lastFocus");ScrollView hs=(ScrollView)get(a,"homeScroll");runOnMainSync(()->hs.scrollTo(0,120));int saved=hs.getScrollY();
 page(a,true);Thread.sleep(450);check((Boolean)get(a,"drawer"),"All Apps did not open");check(get(a,"pageTransitionOverlay")==null,"Page overlay leaked");check(((View)get(a,"content")).getAlpha()>.99f,"Incoming page stayed faded");
 final View app=(View)((List<?>)get(a,"appViews")).get(0);runOnMainSync(()->app.requestFocus());waitForIdleSync();Thread.sleep(400);String appSelected=(String)get(a,"lastFocus");
 page(a,false);Thread.sleep(900);check(!(Boolean)get(a,"drawer"),"Home did not return");check(selected.equals(get(a,"lastFocus")),"Home selection was lost");ScrollView restored=(ScrollView)get(a,"homeScroll");int expected=Math.min(saved,Math.max(0,restored.getChildAt(0).getHeight()-restored.getHeight()));check(Math.abs(restored.getScrollY()-expected)<3,"Home scroll mismatch expected="+expected+" actual="+restored.getScrollY()+" max="+(restored.getChildAt(0).getHeight()-restored.getHeight()));
 page(a,true);Thread.sleep(450);check(appSelected.equals(get(a,"lastFocus")),"All Apps selection was lost");page(a,false);Thread.sleep(450);
 ViewGroup rail=(ViewGroup)get(a,"contentRail");int count=rail.getChildCount();View first=count>0?rail.getChildAt(0):null;main(a,"updateRecommendationRows");check(count==rail.getChildCount(),"Recommendation refresh changed row count");check(first==null||first==rail.getChildAt(0),"Recommendation refresh replaced existing rows");
 main(a,"settingsMenu");Thread.sleep(400);check(get(a,"activeBlade")!=null,"Settings failed to open");main(a,"appearanceMenu");Thread.sleep(260);FrameLayout host=(FrameLayout)get(a,"bladeHost");check(host.getChildCount()==1,"Old settings panel leaked");
 main(a,"closeBlade");main(a,"settingsMenu");Thread.sleep(450);Dialog dialog=(Dialog)get(a,"activeBlade");check(dialog!=null&&dialog.isShowing(),"Closing dialog dismissed the newly opened Settings");check(dialog.getCurrentFocus()!=null,"New Settings lost focus");main(a,"closeBlade");Thread.sleep(400);check(get(a,"activeBlade")==null,"Settings did not close");check(a.getCurrentFocus()!=null,"Page focus was not restored");
 page(a,true);page(a,false);page(a,true);Thread.sleep(450);check(get(a,"pageTransitionOverlay")==null,"Rapid navigation leaked transition overlay");check(((View)get(a,"content")).getAlpha()>.99f,"Rapid navigation left invisible content");page(a,false);Thread.sleep(400);
 out.putString("result","PASS: "+checks+" UI continuity checks");finish(-1,out);
 }catch(Throwable e){out.putString("result","FAIL: "+e);finish(1,out);}}
}