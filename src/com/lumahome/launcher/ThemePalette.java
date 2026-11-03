package com.lumahome.launcher;

/** Small wallpaper palette and restrained tones; independent of Android widgets. */
final class ThemePalette {
    static int seed(int[] pixels) {
        if(pixels==null || pixels.length==0) return 0xFF38BDF8;
        double[] weights=new double[24],red=new double[24],green=new double[24],blue=new double[24];
        for(int pixel:pixels) {
            if((pixel>>>24)<192)continue;
            float[] hsv=hsv(pixel);
            if(hsv[1]<.10f||hsv[2]<.08f||hsv[2]>.98f&&hsv[1]<.15f)continue;
            int bin=Math.min(23,(int)(hsv[0]/15));
            double weight=(.2+.8*hsv[1])*(.4+.6*Math.sqrt(hsv[2]));
            weights[bin]+=weight;red[bin]+=((pixel>>16)&255)*weight;green[bin]+=((pixel>>8)&255)*weight;blue[bin]+=(pixel&255)*weight;
        }
        int best=0;for(int i=1;i<24;i++)if(weights[i]>weights[best])best=i;
        if(weights[best]>=Math.max(1,pixels.length*.008)) {
            return 0xFF000000|((int)(red[best]/weights[best])<<16)|((int)(green[best]/weights[best])<<8)|(int)(blue[best]/weights[best]);
        }
        // Fallback pass for muted or subtle images
        double[] weights2=new double[24],r2=new double[24],g2=new double[24],b2=new double[24];
        for(int pixel:pixels) {
            if((pixel>>>24)<192)continue;
            float[] hsv=hsv(pixel);
            if(hsv[1]<.04f||hsv[2]<.06f)continue;
            int bin=Math.min(23,(int)(hsv[0]/15));
            double weight=hsv[1]*hsv[2];
            weights2[bin]+=weight;r2[bin]+=((pixel>>16)&255)*weight;g2[bin]+=((pixel>>8)&255)*weight;b2[bin]+=(pixel&255)*weight;
        }
        int best2=0;for(int i=1;i<24;i++)if(weights2[i]>weights2[best2])best2=i;
        if(weights2[best2]>0) {
            return 0xFF000000|((int)(r2[best2]/weights2[best2])<<16)|((int)(g2[best2]/weights2[best2])<<8)|(int)(b2[best2]/weights2[best2]);
        }
        return 0xFF38BDF8;
    }
    static int tint(int seed,int original,int intensity) {
        float strength=Math.max(0,Math.min(100,intensity))/100f;
        float value=hsv(original)[2];
        int tone=value>.55f?color(hsv(seed)[0],Math.max(0.18f,strength*.38f),.94f)
                :color(hsv(seed)[0],Math.max(0.24f,strength*.65f),.085f+strength*.065f);
        return (original&0xFF000000)|(tone&0xFFFFFF);
    }
    static int accent(int seed,int intensity){
        float[] hsv=hsv(seed);
        float factor=Math.max(0.1f,Math.min(1.0f,intensity/100f));
        float sat=Math.min(0.92f,Math.max(0.35f,0.40f+factor*0.45f));
        float val=Math.min(1.0f,Math.max(0.88f,0.92f+factor*0.06f));
        return color(hsv[0],sat,val);
    }
    static int muted(int seed,int intensity){
        float[] hsv=hsv(seed);
        float factor=Math.max(0.1f,Math.min(1.0f,intensity/100f));
        float sat=Math.min(0.35f,Math.max(0.08f,0.10f+factor*0.18f));
        return color(hsv[0],sat,.78f);
    }
    private static float[] hsv(int color) {
        float r=((color>>16)&255)/255f,g=((color>>8)&255)/255f,b=(color&255)/255f;
        float max=Math.max(r,Math.max(g,b)),min=Math.min(r,Math.min(g,b)),d=max-min,h=0;
        if(d>0){if(max==r)h=60*((g-b)/d%6);else if(max==g)h=60*((b-r)/d+2);else h=60*((r-g)/d+4);}
        if(h<0)h+=360;return new float[]{h,max==0?0:d/max,max};
    }
    private static int color(float hue,float saturation,float value) {
        float c=value*saturation,x=c*(1-Math.abs((hue/60)%2-1)),m=value-c,r=0,g=0,b=0;
        int sector=(int)(hue/60)%6;
        switch(sector){case 0:r=c;g=x;break;case 1:r=x;g=c;break;case 2:g=c;b=x;break;case 3:g=x;b=c;break;case 4:r=x;b=c;break;default:r=c;b=x;}
        return 0xFF000000|(Math.round((r+m)*255)<<16)|(Math.round((g+m)*255)<<8)|Math.round((b+m)*255);
    }
}
