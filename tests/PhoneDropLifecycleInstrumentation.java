package com.lumahome.iconqa;
import android.app.*;
import android.content.*;
import android.os.*;
import java.lang.reflect.*;
import java.net.*;
import java.io.*;
public class PhoneDropLifecycleInstrumentation extends Instrumentation {
 private int checks;
 private void check(boolean ok,String why){if(!ok)throw new AssertionError(why);checks++;}
 private Object server(Activity a)throws Exception{Field f=a.getClass().getDeclaredField("phoneDrop");f.setAccessible(true);return f.get(a);}
 private String url(Object s)throws Exception{Method m=s.getClass().getDeclaredMethod("getUrl");m.setAccessible(true);return (String)m.invoke(s);}
 private int status(String link)throws Exception{
  URI u=new URI(link);try(Socket s=new Socket()){s.connect(new InetSocketAddress(u.getHost(),u.getPort()),3000);s.setSoTimeout(3000);String path=u.getRawPath()+(u.getRawQuery()==null?"":"?"+u.getRawQuery());s.getOutputStream().write(("GET "+path+" HTTP/1.1\r\nHost: "+u.getHost()+"\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));String line=new BufferedReader(new InputStreamReader(s.getInputStream())).readLine();return Integer.parseInt(line.split(" ")[1]);}
 }
 private void invoke(Activity a,String method)throws Exception{Method m=a.getClass().getDeclaredMethod(method);m.setAccessible(true);m.invoke(a);}
 @Override public void onCreate(Bundle b){super.onCreate(b);start();}
 @Override public void onStart(){Bundle result=new Bundle();try{
  Intent i=new Intent();i.setClassName("com.lumahome.launcher","com.lumahome.launcher.MainActivity");i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
  Activity a=startActivitySync(i);waitForIdleSync();Thread.sleep(1000);
  Object first=server(a);check(first!=null,"Server did not start without QR screen");String original=url(first);check(status(original)==200,"Home upload link unreachable");check(status(original.split("\\?")[0])==403,"Upload token not enforced");
  runOnMainSync(()->{try{Method m=a.getClass().getDeclaredMethod("phoneDropPanel",Runnable.class);m.setAccessible(true);m.invoke(a,(Runnable)null);}catch(Exception e){throw new RuntimeException(e);}});waitForIdleSync();
  check(first==server(a),"Opening QR screen replaced server");
  runOnMainSync(()->{try{invoke(a,"closeBlade");}catch(Exception e){throw new RuntimeException(e);}});waitForIdleSync();
  check(status(original)==200,"Closing QR screen invalidated link");
  runOnMainSync(()->{try{invoke(a,"settingsMenu");}catch(NoSuchMethodException absent){}catch(Exception e){throw new RuntimeException(e);}});
  check(status(original)==200,"Settings navigation invalidated link");
  Field ip=first.getClass().getDeclaredField("lanIp");ip.setAccessible(true);ip.set(first,"192.0.2.1");Method refresh=first.getClass().getDeclaredMethod("refreshAddress");refresh.setAccessible(true);check((Boolean)refresh.invoke(first),"Address refresh missed change");check(original.equals(url(first)),"Network refresh changed private token or port");
  runOnMainSync(()->{try{invoke(a,"stopPhoneDrop");invoke(a,"ensurePhoneDrop");}catch(Exception e){throw new RuntimeException(e);}});
  check(original.equals(url(server(a))),"Server restart changed bookmarked link");check(status(original)==200,"Restarted upload server unreachable");
  runOnMainSync(()->{try{invoke(a,"closeBlade");}catch(Exception e){throw new RuntimeException(e);}});
  result.putString("result","PASS: "+checks+" upload lifecycle checks");finish(-1,result);
 }catch(Throwable e){result.putString("result","FAIL: "+e);finish(1,result);}}
}
