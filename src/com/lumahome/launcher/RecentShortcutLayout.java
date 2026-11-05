package com.lumahome.launcher;

/** Pixel layout for a single, non-scrolling recent-shortcut row. */
final class RecentShortcutLayout {
    static final class Plan {
        final int edge; final int[] widths;
        Plan(int edge,int[] widths){this.edge=edge;this.widths=widths;}
    }
    static Plan fit(int width,int items,int minimumWidth,int gap,int safeEdge,float focusScale){
        width=Math.max(1,width);gap=Math.max(0,gap);minimumWidth=Math.max(1,minimumWidth);
        if(items<=0)return new Plan(0,new int[0]);
        int baseEdge=Math.min(Math.max(0,safeEdge),(width-1)/2);
        int capacity=Math.max(1,(width-2*baseEdge+gap)/(minimumWidth+gap));
        int count=Math.min(items,capacity),edge=baseEdge;
        while(count>0){
            int cell=Math.max(1,(width-2*baseEdge-gap*(count-1))/count);
            edge=Math.min((width-1)/2,Math.max(baseEdge,(int)Math.ceil(cell*Math.max(0f,focusScale-1f)/2f)+2));
            int usable=width-2*edge-gap*(count-1);
            if(count==1 || usable/count>=minimumWidth){
                int[] widths=new int[count];int each=Math.max(1,usable/count),remainder=Math.max(0,usable-each*count);
                for(int i=0;i<count;i++)widths[i]=each+(i<remainder?1:0);
                return new Plan(edge,widths);
            }
            count--;
        }
        return new Plan(0,new int[]{width});
    }
}
