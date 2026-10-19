package com.lumahome.launcher;

/** Exhaustive gating checks: disabling any required input must preserve clock fallback. */
public final class HomePolicyTest {
    public static void main(String[] args) {
        int checks=0;
        for(int bits=0;bits<32;bits++) {
            boolean enabled=(bits&1)!=0,master=(bits&2)!=0,app=(bits&4)!=0;
            boolean content=(bits&8)!=0,artwork=(bits&16)!=0;
            boolean expected=enabled&&master&&app&&content&&artwork;
            check(HomePolicy.banner(enabled,"test.source",master,app,content,artwork)==expected);
            check(HomePolicy.recommendations(master,app,content)==(master&&app&&content));
            checks+=2;
        }
        check(!HomePolicy.banner(true,"",true,true,true,true));
        check(!HomePolicy.banner(true,null,true,true,true,true));
        System.out.println("Passed "+(checks+2)+" recommendation and clock-fallback checks.");
    }
    private static void check(boolean value) {if(!value)throw new AssertionError("Unexpected recommendation visibility");}
}
