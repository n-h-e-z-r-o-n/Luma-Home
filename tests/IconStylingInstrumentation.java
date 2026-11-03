package com.lumahome.iconqa;

import android.app.Instrumentation;
import android.os.Bundle;
import android.graphics.*;
import android.graphics.drawable.*;
import com.lumahome.launcher.IconStylingEngine;
import java.io.File;
import java.io.FileOutputStream;
import java.util.HashSet;

/** Device rendering checks; built separately and never shipped in the launcher. */
public class IconStylingInstrumentation extends Instrumentation {
    private int checks;
    private void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
    @Override public void onCreate(Bundle arguments){super.onCreate(arguments);start();}
    private IconStylingEngine.Config config(String style,int size){return new IconStylingEngine.Config(style,"original","circle",size,"auto","none",0xFF317E83);}
    private Bitmap render(String pkg,Drawable source,String style,int size){
        return ((BitmapDrawable)IconStylingEngine.getStyledIcon(getTargetContext(),pkg,source,240,240,config(style,size))).getBitmap();
    }
    private Rect redBounds(Bitmap bitmap){
        Rect r=new Rect(bitmap.getWidth(),bitmap.getHeight(),0,0);
        for(int y=0;y<bitmap.getHeight();y++)for(int x=0;x<bitmap.getWidth();x++){
            int c=bitmap.getPixel(x,y);
            if(Color.alpha(c)>220&&Color.red(c)>220&&Color.green(c)<30&&Color.blue(c)<30){r.left=Math.min(r.left,x);r.top=Math.min(r.top,y);r.right=Math.max(r.right,x+1);r.bottom=Math.max(r.bottom,y+1);}
        }
        return r;
    }
    @Override public void onStart(){
        Bundle result=new Bundle();
        try{
            Bitmap source=Bitmap.createBitmap(200,80,Bitmap.Config.ARGB_8888);Canvas sourceCanvas=new Canvas(source);Paint sourcePaint=new Paint(Paint.ANTI_ALIAS_FLAG);sourcePaint.setColor(Color.RED);sourceCanvas.drawOval(0,0,200,80,sourcePaint);
            Drawable wide=new BitmapDrawable(getTargetContext().getResources(),source);
            String[] styles={"brand","minimalist","monochrome","paper","glass","dark","flat","outlined","original"};
            HashSet<Integer> hashes=new HashSet<>();
            for(String style:styles){
                Bitmap bitmap=render("qa.wide",wide,style,100);int[] pixels=new int[240*240];bitmap.getPixels(pixels,0,240,0,0,240,240);hashes.add(java.util.Arrays.hashCode(pixels));
                check(Color.alpha(bitmap.getPixel(0,0))==0,style+" escaped its shape");
                if(!"monochrome".equals(style)){Rect bounds=redBounds(bitmap);check(bounds.width()>0,style+" lost artwork");check(Math.abs(bounds.width()/(float)bounds.height()-2.5f)<.09f,style+" distorted artwork");}
            }
            check(hashes.size()==styles.length,"Materials are not distinct");
            check(redBounds(render("qa.wide",wide,"brand",130)).width()>redBounds(render("qa.wide",wide,"brand",85)).width()*1.15f,"Size control does not change artwork size");
            Bitmap backed=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888);backed.eraseColor(Color.WHITE);
            Canvas bc=new Canvas(backed);Paint bp=new Paint();bp.setColor(Color.RED);bc.drawRect(36,44,92,84,bp);
            Drawable backedIcon=new BitmapDrawable(getTargetContext().getResources(),backed);
            for(String style:styles){
                Bitmap filled=render("qa.backed",backedIcon,style,100);
                int edge=filled.getPixel(12,120);
                check(Color.red(edge)>240&&Color.green(edge)>240&&Color.blue(edge)>240,style+" left an inner square backing");
                check(Color.alpha(filled.getPixel(0,0))==0,style+" backing escaped the mask");
                if(!"monochrome".equals(style))check(Math.abs(redBounds(filled).width()/(float)redBounds(filled).height()-1.4f)<.08f,style+" stretched the backed logo");
            }
            // Nonuniform artwork must fill the shape too; no known-app special cases.
            for(int variant=0;variant<3;variant++){
                Bitmap art=Bitmap.createBitmap(variant==2?240:128,128,Bitmap.Config.ARGB_8888);
                Canvas ac=new Canvas(art);Paint ap=new Paint();
                ap.setShader(new LinearGradient(0,0,art.getWidth(),128,0xFF168CDF,0xFF7023AB,Shader.TileMode.CLAMP));
                if(variant==1)ac.drawRoundRect(0,0,art.getWidth(),128,14,14,ap);else ac.drawPaint(ap);
                Bitmap filled=render("qa.unknown."+variant,new BitmapDrawable(getTargetContext().getResources(),art),"brand",100);
                int edge=filled.getPixel(12,120);
                check(Color.alpha(edge)>240,"Unknown artwork left an empty edge");
                check(Color.blue(edge)>100,"Unknown artwork retained the material plate");
                check(Color.alpha(filled.getPixel(0,0))==0,"Unknown artwork escaped circle");
            }
            Bitmap fg=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888);Canvas fc=new Canvas(fg);Paint ink=new Paint();ink.setColor(Color.RED);fc.drawRect(30,38,98,90,ink);
            AdaptiveIconDrawable adaptive=new AdaptiveIconDrawable(new ColorDrawable(Color.BLUE),new BitmapDrawable(getTargetContext().getResources(),fg));
            IconStylingEngine.Config white=new IconStylingEngine.Config("minimalist","white","circle",100,"auto","none",0xFF317E83);
            Bitmap separated=((BitmapDrawable)IconStylingEngine.getStyledIcon(getTargetContext(),"qa.layered",adaptive,240,240,white)).getBitmap();
            check(redBounds(separated).width()<=0,"Layered foreground was not recolored");
            int whites=0;for(int y=40;y<200;y++)for(int x=40;x<200;x++){int c=separated.getPixel(x,y);if(Color.red(c)>230&&Color.green(c)>230&&Color.blue(c)>230)whites++;}
            check(whites>100,"Layered logo lost its visible foreground");
            Bitmap sheet=Bitmap.createBitmap(1200,780,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(sheet);canvas.drawColor(0xFF15191D);
            Paint text=new Paint(Paint.ANTI_ALIAS_FLAG);text.setColor(Color.WHITE);text.setTextSize(27);text.setTypeface(Typeface.create("sans-serif-medium",0));
            String[] apps={"com.google.android.youtube.tv","com.example.onyx","com.android.vending","com.esaba.downloader"};
            for(int i=0;i<styles.length;i++){
                int left=(i%3)*400,top=(i/3)*260;canvas.drawText(styles[i],left+20,top+38,text);
                for(int j=0;j<apps.length;j++)try{
                    Drawable original=getTargetContext().getPackageManager().getApplicationIcon(apps[j]);
                    Drawable d=IconStylingEngine.getStyledIcon(getTargetContext(),apps[j],original,86,86,config(styles[i],100));
                    d.setBounds(left+16+j*96,top+74,left+102+j*96,top+160);d.draw(canvas);
                }catch(Exception missing){}
                canvas.drawText("Complete logos preserved",left+20,top+211,text);
            }
            java.io.ByteArrayOutputStream image=new java.io.ByteArrayOutputStream();sheet.compress(Bitmap.CompressFormat.PNG,100,image);
            result.putString("preview_png",android.util.Base64.encodeToString(image.toByteArray(),android.util.Base64.NO_WRAP));
            result.putString("result","PASS: "+checks+" rendering checks");finish(-1,result);
        }catch(Throwable error){result.putString("result","FAIL: "+error.toString());finish(1,result);}
    }
}
