package com.lumahome.launcher;
import java.util.Arrays;
public final class ThemePaletteTest {
    static void check(boolean value){if(!value)throw new AssertionError("Unexpected palette result");}
    static double luminance(int c){double sum=0;double[] weights={.2126,.7152,.0722};for(int i=0;i<3;i++){double v=((c>>(16-i*8))&255)/255.0;sum+=weights[i]*(v<=.04045?v/12.92:Math.pow((v+.055)/1.055,2.4));}return sum;}
    public static void main(String[] args){
        check(ThemePalette.seed(new int[]{0,0xFFFFFFFF,0xFF000000,0xFF888888})==0);
        int[] pixels=new int[100];Arrays.fill(pixels,0xFF248A42);pixels[0]=0xFFFF0000;
        int green=ThemePalette.seed(pixels);check(((green>>8)&255)>((green>>16)&255));
        check(ThemePalette.seed(new int[]{0x00FF0000,0x00FF0000})==0);
        int checks=3;
        for(int seed:new int[]{0xFFFF0000,0xFF00FF00,0xFF0000FF,0xFFFFFF00,0xFF00FFFF,0xFFFF00FF,0xFF183A2A})
            for(int intensity:new int[]{0,45,100}){
                int surface=ThemePalette.tint(seed,0xF7161113,intensity),accent=ThemePalette.accent(seed,intensity),muted=ThemePalette.muted(seed,intensity);
                check((surface>>>24)==247);check((luminance(accent)+.05)/(luminance(surface)+.05)>=4.5);check((luminance(muted)+.05)/(luminance(surface)+.05)>=4.5);checks+=3;
                if(intensity==0){check(((accent>>16)&255)==((accent>>8)&255)&&(accent&255)==((accent>>8)&255));checks++;}
            }
        System.out.println("Passed "+checks+" palette, contrast and neutral-intensity checks.");
    }
}
