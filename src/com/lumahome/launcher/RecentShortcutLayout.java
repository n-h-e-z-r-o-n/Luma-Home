package com.lumahome.launcher;

/** Pixel layout for a single, non-scrolling recent-shortcut row. */
final class RecentShortcutLayout {
    static final class Plan {
        final int edge; final int[] widths;
        Plan(int edge,int[] widths){this.edge=edge;this.widths=widths;}
    }
    static Plan fit(int width,int items,int minimumWidth,int gap,int safeEdge,float focusScale){
        return fit(width,items,minimumWidth,Integer.MAX_VALUE,gap,safeEdge,focusScale);
    }
    static Plan fit(int width,int items,int minimumWidth,int maxWidth,int gap,int safeEdge,float focusScale){
        width=Math.max(1,width);gap=Math.max(0,gap);minimumWidth=Math.max(1,minimumWidth);
        maxWidth=Math.max(minimumWidth,maxWidth);
        if(items<=0)return new Plan(0,new int[0]);
        int baseEdge=Math.min(Math.max(0,safeEdge),(width-1)/2);
        int capacity=Math.max(1,(width-2*baseEdge+gap)/(minimumWidth+gap));
        int count=Math.min(items,capacity),edge=baseEdge;
        while(count>0){
            int cell=Math.max(1,(width-2*baseEdge-gap*(count-1))/count);
            edge=Math.min((width-1)/2,Math.max(baseEdge,(int)Math.ceil(cell*Math.max(0f,focusScale-1f)/2f)+2));
            int usable=width-2*edge-gap*(count-1);
            if(count==1 || usable/count>=minimumWidth){
                int rawEach=Math.max(1,usable/count);
                int each=Math.min(maxWidth,rawEach);
                int remainder=(each==maxWidth)?0:Math.max(0,usable-each*count);
                int[] widths=new int[count];
                int totalContentWidth=gap*(count-1);
                for(int i=0;i<count;i++){
                    widths[i]=each+(i<remainder?1:0);
                    totalContentWidth+=widths[i];
                }
                if(each==maxWidth && totalContentWidth<width){
                    edge=Math.max(baseEdge,(width-totalContentWidth)/2);
                }
                return new Plan(edge,widths);
            }
            count--;
        }
        int clampedWidth=Math.min(width,maxWidth);
        return new Plan(Math.max(0,(width-clampedWidth)/2),new int[]{clampedWidth});
    }
}
