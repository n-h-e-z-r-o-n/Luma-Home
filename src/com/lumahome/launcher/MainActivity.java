package com.lumahome.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.role.RoleManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewConfiguration;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Remote-first launcher. Preferences affect Luma Home only; no system apps are modified. */
public final class MainActivity extends Activity {
    private static final String ALL="dock.all", SETTINGS="dock.settings";
    private static final int PICK_IMAGE=40, TV_LISTINGS=41, DEFAULT_HOME=42, MANAGE_LAUNCHER=43;
    private static final String TV_LISTINGS_PERMISSION="android.permission.READ_TV_LISTINGS";
    private LauncherStore store;
    private AppCatalog catalog;
    private ImageLoader images;
    private RecommendationRepository recommendations;
    private final Handler handler=new Handler();
    private List<AppCatalog.App> apps=new ArrayList<>();
    private final Map<String,AppCatalog.App> byPackage=new HashMap<>();
    private final Map<String,View> views=new HashMap<>();
    private final List<View> appViews=new ArrayList<>(), dockViews=new ArrayList<>(), contentViews=new ArrayList<>(), topPillViews=new ArrayList<>();
    private final List<List<View>> appRows=new ArrayList<>();
    private final List<List<View>> contentRows=new ArrayList<>();
    private final Map<String,Integer> appColorCache=new HashMap<>();
    private FrameLayout root, content, header;
    private LinearLayout contentRail, drawerPage, homePage, recentSection;
    private ScrollView drawerScroll, homeScroll;
    private View clockPanel, focusedFavorite;
    private Dialog idleClockDialog;
    private final Runnable idleClockTrigger=this::showIdleClock;
    private FrameLayout heroPanel;
    private Button heroPrimary;
    private VideoView heroVideo;
    private ImageView heroImage;
    private List<RecommendationRepository.Item> heroItems=new ArrayList<>();
    private int heroIndex;
    private float heroArtworkRatio=16f/9f;
    private int wallpaperSeed;
    private String palettePending="";
    private final java.util.concurrent.ExecutorService paletteWorker=java.util.concurrent.Executors.newSingleThreadExecutor();
    private boolean heroWantsFocus;
    private final Runnable heroRotation=()->rotateHero();
    private boolean drawer, dirty=true, destroyed;
    private boolean suppressCenterUp;
    private boolean choosingLuma, disablingLaunchers;
    private Dialog activeBlade;
    private FrameLayout bladeHost;
    private Runnable bladeBack;
    private String lastBladeTitle="";
    private int lastBladeSelection=0, bladeGeneration=0;
    private SpringStage homeSpringStage, drawerSpringStage, recentSpringStage, previewSpringStage;
    private final List<SpringStage> recommendationSpringStages=new ArrayList<>();
    private DockSpringLens dockSpringLens;
    private View dockBar, searchBar, activeBladeBox;
    private TextView drawerFocusTitle;
    private android.animation.ValueAnimator drawerSearchAnimator;
    private UiSoundEngine sfx;
    private long lastUserNavMs;
    private int lastUserNavDir=1;
    private PhoneDropServer phoneDrop;
    private final Runnable wallpaperSlideshowTick=this::advanceWallpaperSlideshow;
    private android.graphics.Bitmap frostedBackdrop;
    private android.graphics.BitmapShader frostedShader;
    private String frostedBackdropKey="";
    private String overrideTreatmentStyle;
    private int overrideTreatmentIntensity=-1;
    private ImageView treatmentBlurLayer, wallpaperImageView;
    private View treatmentOverlayLayer;
    private android.graphics.Bitmap blurredWallpaperBackdrop;
    private String blurredWallpaperKey="";
    private int overrideFrostPct=-1;
    private final OvershootInterpolator liquidOvershoot=new OvershootInterpolator(0.75f);
    private final DecelerateInterpolator crispDecel=new DecelerateInterpolator(1.6f);
    private String lastFocus="", moving="", imageTarget="", search="";
    private int screenWidth, screenHeight, contentHeight, serial;
    private float scale;
    private Typeface regular=Typeface.create("sans-serif",Typeface.NORMAL);
    private Typeface medium=Typeface.create("sans-serif-medium",Typeface.NORMAL);
    private WeatherService weatherService;
    private View glanceWidget;
    private WeatherService.WeatherInfo latestWeather;
    private TextView glanceIconView, glanceTempView, glanceCondView, glanceCityView;
    private View ambientBackground;
    private android.graphics.drawable.BitmapDrawable ambientTexture;
    private android.animation.ValueAnimator ambientAnimator;
    private int ambientColor=0xFF604844;

    private final BroadcastReceiver packageChanges=new BroadcastReceiver() {
        @Override public void onReceive(Context context,Intent intent) { dirty=true; }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if(Build.VERSION.SDK_INT>=26){
            regular=getResources().getFont(getResources().getIdentifier("outfit_regular","font",getPackageName()));
            medium=getResources().getFont(getResources().getIdentifier("outfit_medium","font",getPackageName()));
        }
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        store=new LauncherStore(this); sfx=new UiSoundEngine(this,store); catalog=new AppCatalog(this);
        images=new ImageLoader(this); recommendations=new RecommendationRepository(this); weatherService=new WeatherService();
        if (state!=null) { imageTarget=state.getString("imageTarget",""); drawer=state.getBoolean("drawer"); }
        refreshApps(); applyIntent(getIntent()); initializeFavorites(); syncSystemSoundMuting();
        IntentFilter filter=new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED); filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED); filter.addDataScheme("package");
        if (Build.VERSION.SDK_INT>=33) registerReceiver(packageChanges,filter,Context.RECEIVER_EXPORTED);
        else registerReceiver(packageChanges,filter);
    }
    @Override protected void onResume() {
        super.onResume();
        if (dirty || root==null) { refreshApps(); dirty=false; recommendations.clear(); render(); }
        else { recommendations.clear(); updateHeader(); updateRecommendationRows(); }
        resetIdleTimer();
        resetWallpaperSlideshowTimer();
        syncSystemSoundMuting();
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent); applyIntent(intent);
        dismissIdleClock(); closeBlade(); drawer=false; search=""; moving=""; refreshApps(); render();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("imageTarget",imageTarget); state.putBoolean("drawer",drawer);
        super.onSaveInstanceState(state);
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        handler.removeCallbacks(heroRotation);
        if(focused&&heroPanel!=null&&store.flag("hero_rotate"))handler.postDelayed(heroRotation,10000);
        if(focused){resetIdleTimer();syncSystemSoundMuting();}
    }
    @Override protected void onPause() {
        if(ambientAnimator!=null)ambientAnimator.cancel();
        stopPhoneDrop();
        stopSpringStages();stopHeroVideo();handler.removeCallbacks(heroRotation);handler.removeCallbacks(idleClockTrigger);handler.removeCallbacks(wallpaperSlideshowTick);super.onPause();
    }
    @Override protected void onDestroy() {
        if(ambientAnimator!=null)ambientAnimator.cancel();
        if(blurredWallpaperBackdrop!=null) { blurredWallpaperBackdrop.recycle(); blurredWallpaperBackdrop=null; }
        wallpaperImageView=null; if(weatherService!=null){weatherService.close();weatherService=null;}
        destroyed=true; stopSpringStages(); dismissIdleClock(); closeBlade(); paletteWorker.shutdownNow(); handler.removeCallbacksAndMessages(null);
        unregisterReceiver(packageChanges); if(sfx!=null)sfx.close(); images.close(); recommendations.close(); super.onDestroy();
    }
    private void noteNavKey(int key,KeyEvent event) {
        if(event!=null && event.getAction()==KeyEvent.ACTION_DOWN) {
            if(key==KeyEvent.KEYCODE_DPAD_RIGHT || key==KeyEvent.KEYCODE_DPAD_DOWN) {
                lastUserNavMs=android.os.SystemClock.uptimeMillis();
                lastUserNavDir=1;
            } else if(key==KeyEvent.KEYCODE_DPAD_LEFT || key==KeyEvent.KEYCODE_DPAD_UP) {
                lastUserNavMs=android.os.SystemClock.uptimeMillis();
                lastUserNavDir=-1;
            }
        }
    }
    private boolean muteSystem() {
        return sfx!=null && sfx.enabled() && store!=null && store.flag("ui_sounds_mute_system",true);
    }
    private void applyViewSoundPolicy(View v) {
        if(v==null) return;
        boolean allowStock=!muteSystem();
        v.setSoundEffectsEnabled(allowStock);
        if(v instanceof ViewGroup) {
            ViewGroup vg=(ViewGroup)v;
            for(int i=0;i<vg.getChildCount();i++) applyViewSoundPolicy(vg.getChildAt(i));
        }
    }
    private void syncSystemSoundMuting() {
        if(getWindow()!=null && getWindow().getDecorView()!=null) {
            applyViewSoundPolicy(getWindow().getDecorView());
        }
        if(activeBlade!=null && activeBlade.getWindow()!=null && activeBlade.getWindow().getDecorView()!=null) {
            applyViewSoundPolicy(activeBlade.getWindow().getDecorView());
        }
        if(idleClockDialog!=null && idleClockDialog.getWindow()!=null && idleClockDialog.getWindow().getDecorView()!=null) {
            applyViewSoundPolicy(idleClockDialog.getWindow().getDecorView());
        }
        if(root!=null) applyViewSoundPolicy(root);
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if(event!=null) noteNavKey(event.getKeyCode(),event);
        resetIdleTimer();
        if(muteSystem()) {
            View decor=getWindow()!=null?getWindow().getDecorView():null;
            if(decor!=null && decor.isSoundEffectsEnabled()) decor.setSoundEffectsEnabled(false);
            if(activeBlade!=null && activeBlade.getWindow()!=null) {
                View bDecor=activeBlade.getWindow().getDecorView();
                if(bDecor!=null && bDecor.isSoundEffectsEnabled()) bDecor.setSoundEffectsEnabled(false);
            }
        }
        if(idleClockDialog!=null && idleClockDialog.isShowing()) {
            if(event.getAction()==KeyEvent.ACTION_UP) dismissIdleClock();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
    @Override public void onBackPressed() {
        if(heroVideo!=null){if(sfx!=null)sfx.playBack();stopHeroVideo();return;}
        if(!drawer&&header!=null&&header.hasFocus()&&!appViews.isEmpty()){if(sfx!=null)sfx.playBack();heroWantsFocus=false;appViews.get(0).requestFocus();return;}
        if (!moving.isEmpty()) { if(sfx!=null)sfx.playBack(); moving=""; render(); return; }
        if (drawer) { if(sfx!=null)sfx.playBack(); hideKeyboard(); drawer=false; search=""; lastFocus=ALL; render(); }
    }
    private void refreshApps() {
        apps=catalog.all(); byPackage.clear();
        for (AppCatalog.App app:apps) byPackage.put(app.pkg,app);
        Collections.sort(apps,(a,b)->name(a.pkg).compareToIgnoreCase(name(b.pkg)));
        syncFavorites();
    }
    private void initializeFavorites() {
        if (!store.flag("favorites_initialized")) {
            List<String> favorites=new ArrayList<>();
            for (String pkg:Arrays.asList("com.google.android.youtube.tv","com.example.onyx",
                    "com.streamflixreborn.streamflix","flix.com.vision","com.esaba.downloader",
                    "com.alphainventor.filemanager")) if (byPackage.containsKey(pkg)) favorites.add(pkg);
            for (String pkg:Apps.LIST) if (byPackage.containsKey(pkg) && !favorites.contains(pkg)) favorites.add(pkg);
            for (AppCatalog.App app:apps) if (!utility(app.pkg) && !favorites.contains(app.pkg)) favorites.add(app.pkg);
            store.list("favorites",favorites); store.setFlag("favorites_initialized",true);
        }
        syncFavorites();
    }
    private void syncFavorites() {
        if (store==null || apps.isEmpty() || !store.flag("favorites_initialized") || store.flag("favorites_custom_only")) return;
        List<String> current=store.list("favorites"), removed=store.list("removed_favorites"), updated=new ArrayList<>();
        for (String pkg:current) if (byPackage.containsKey(pkg) && !removed.contains(pkg) && !updated.contains(pkg)) updated.add(pkg);
        for (AppCatalog.App app:apps) if (!utility(app.pkg) && !removed.contains(app.pkg) && !updated.contains(app.pkg)) updated.add(app.pkg);
        if (!updated.equals(current)) store.list("favorites",updated);
    }
    private void applyIntent(Intent intent) {
        if (intent==null || !intent.hasExtra("apps")) return;
        String stamp=intent.getStringExtra("stamp"), raw=intent.getStringExtra("apps");
        if (stamp!=null && stamp.equals(store.value("intent_stamp",""))) return;
        if (stamp!=null) store.set("intent_stamp",stamp);
        if (raw==null || raw.equals("auto")) {
            store.setFlag("favorites_custom_only",false); store.setFlag("favorites_initialized",false);
            store.list("removed_favorites",new ArrayList<>()); initializeFavorites(); return;
        }
        List<String> favorites=new ArrayList<>();
        for (String pkg:raw.split("[,\\s]+")) if (byPackage.containsKey(pkg) && !favorites.contains(pkg)) favorites.add(pkg);
        store.list("favorites",favorites); store.setFlag("favorites_initialized",true); store.setFlag("favorites_custom_only",true);
    }
    private List<String> favorites() {
        List<String> result=new ArrayList<>(), removed=store.list("removed_favorites");
        for (String pkg:store.list("favorites")) if (byPackage.containsKey(pkg) && !removed.contains(pkg) && !result.contains(pkg)) result.add(pkg);
        return result;
    }
    private String name(String pkg) {
        AppCatalog.App app=byPackage.get(pkg);
        return store.value("name."+pkg,app==null?pkg:app.originalName);
    }
    private int px(float value) { return Math.max(1,Math.round(value*scale)); }
    private int columns() { return store.number("columns",6); }
    private int innerWidth() { return screenWidth-px(192); }
    private int drawerWidth() { return screenWidth-px(192); }
    private int recommendationSize() {return Math.max(0,Math.min(3,store.number("recommendation_size",1)));}
    private int recommendationWidth(int size) {return new int[]{224,256,336,416}[size];}
    private int recommendationTitleHeight(int size) {return new int[]{44,48,56,64}[size];}
    private int recommendationTextSize(int size) {return new int[]{19,21,25,29}[size];}
    private String recommendationSizeName(int size) {return new String[]{"Small","Standard","Large","Extra large"}[size];}
    private boolean utility(String pkg) {
        AppCatalog.App app=byPackage.get(pkg);
        return store.flag("utility."+pkg,app!=null&&app.utility);
    }
    private int foreground() { return Color.WHITE; }
    private boolean wallpaperTheme(){return "wallpaper".equals(store.value("theme","warm"))&&wallpaperSeed!=0;}
    private int colorIntensity(){return Math.max(0,Math.min(100,store.number("color_intensity",45)));}
    private int themed(int warm,int cool) { return wallpaperTheme()?ThemePalette.tint(wallpaperSeed,warm,colorIntensity()):"cool".equals(store.value("theme","warm"))?cool:warm; }
    private String themeName(){String theme=store.value("theme","warm");return "wallpaper".equals(theme)?"From wallpaper":"cool".equals(theme)?"Cool slate":"Warm glass";}
    private String paletteKey(){return "v1:"+store.value("background","ember")+":"+store.value("background_image","");}
    private void refreshWallpaperPalette(){
        String style=store.value("background","ember");
        if("photo".equals(style))wallpaperSeed=paletteKey().equals(store.value("palette_source",""))?store.number("palette_seed",0):0;
        else if("sky".equals(style))wallpaperSeed=skyPaletteSeed();
        else wallpaperSeed="forest".equals(style)?0xFF276849:"ocean".equals(style)?0xFF286A91:"midnight".equals(style)?0xFF465B9A:"ember".equals(style)?0xFF9A6A52:0;
    }
    private int skyPaletteSeed() {
        int hour=java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        if(hour>=5&&hour<9)return 0xFFC8744B;
        if(hour>=9&&hour<17)return 0xFF2A6FA8;
        if(hour>=17&&hour<21)return 0xFFB85C4E;
        return 0xFF34477A;
    }
    private Drawable buildSkyBackground() {
        java.util.Calendar cal=java.util.Calendar.getInstance();
        float h=cal.get(java.util.Calendar.HOUR_OF_DAY)+cal.get(java.util.Calendar.MINUTE)/60f;
        int top, mid, bot, sun;
        if(h>=5.5f&&h<8.5f) {
            top=0xFF141E38; mid=0xFF3A355C; bot=0xFF8C4E42; sun=0x44FF9E5E;
        } else if(h>=8.5f&&h<16.5f) {
            top=0xFF0E294B; mid=0xFF1A4773; bot=0xFF2B6592; sun=0x2888D4FF;
        } else if(h>=16.5f&&h<20.5f) {
            top=0xFF121830; mid=0xFF3D2647; bot=0xFF823B35; sun=0x48FF6E48;
        } else {
            top=0xFF05070E; mid=0xFF0B1122; bot=0xFF151E36; sun=0x224A68B8;
        }
        GradientDrawable base=new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{top,mid,bot});
        GradientDrawable glow=new GradientDrawable();
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glow.setGradientCenter(0.75f,0.22f);
        glow.setGradientRadius(px(1150));
        glow.setColors(new int[]{sun,0x00000000});
        return new android.graphics.drawable.LayerDrawable(new Drawable[]{base,glow});
    }
    private void extractWallpaperColors(android.graphics.Bitmap bitmap,final String key){
        if(!key.equals(paletteKey())||!"photo".equals(store.value("background","ember")))return;
        if(key.equals(store.value("palette_source",""))||key.equals(palettePending))return;
        palettePending=key;
        paletteWorker.execute(()->{
            int seed=0;
            try{
                float ratio=Math.min(1f,64f/Math.max(bitmap.getWidth(),bitmap.getHeight()));
                android.graphics.Bitmap sample=android.graphics.Bitmap.createScaledBitmap(bitmap,Math.max(1,Math.round(bitmap.getWidth()*ratio)),Math.max(1,Math.round(bitmap.getHeight()*ratio)),true);
                int[] pixels=new int[sample.getWidth()*sample.getHeight()];sample.getPixels(pixels,0,sample.getWidth(),0,0,sample.getWidth(),sample.getHeight());
                seed=ThemePalette.seed(pixels);if(sample!=bitmap)sample.recycle();
            }catch(Exception|OutOfMemoryError ignored){}
            final int extracted=seed;
            if(!destroyed)handler.post(()->{
                if(destroyed)return;
                if(key.equals(palettePending))palettePending="";
                if(!key.equals(paletteKey()))return;
                store.prefs.edit().putString("palette_source",key).putInt("palette_seed",extracted).apply();wallpaperSeed=extracted;
                if("wallpaper".equals(store.value("theme","warm")))render();
            });
        });
    }
    private int muted() { return store.flag("contrast")?0xFFF0F0F0:wallpaperTheme()?ThemePalette.muted(wallpaperSeed,colorIntensity()):themed(0xFFBDB2B4,0xFFB6BBC7); }
    private int accent() { return store.flag("contrast")?0xFFFFFF00:wallpaperTheme()?ThemePalette.accent(wallpaperSeed,colorIntensity()):themed(0xFFDCD2CC,0xFFB7D8FF); }

    private void stopSpringStages() {
        if(homeSpringStage!=null) homeSpringStage.stop();
        if(drawerSpringStage!=null) drawerSpringStage.stop();
        if(recentSpringStage!=null) recentSpringStage.stop();
        if(dockSpringLens!=null) dockSpringLens.stop();
        if(previewSpringStage!=null) { previewSpringStage.stop(); previewSpringStage=null; }
        for(SpringStage stage:recommendationSpringStages) if(stage!=null) stage.stop();
        recommendationSpringStages.clear();
    }

    private String uiSurfaceStyle() { return store.value("ui_surface_style","glass"); }
    private String uiSurfaceStyleName() {
        String st=uiSurfaceStyle();
        return "matte".equals(st)?"Matte":"solid".equals(st)?"Solid dark":"Liquid glass";
    }
    private boolean glassRefractionEnabled() { return store.flag("glass_refraction",true); }
    private int glassFrostDepth() { return Math.max(25,Math.min(92,store.number("glass_frost",64))); }
    private String glassFrostName(int v) { return v<45?"Crystal":v<=72?"Balanced":"Smoked"; }
    private int glassSheenMode() { return Math.max(0,Math.min(2,store.number("glass_sheen",1))); }
    private String glassSheenName() { int m=glassSheenMode(); return m==0?"Subtle":m==2?"Bright":"Balanced"; }
    private float sheenMultiplier() { int m=glassSheenMode(); return m==0?0.55f:m==2?1.48f:1.0f; }
    private String glassSurfacesSummary() {
        if("solid".equals(uiSurfaceStyle())) return "Solid dark";
        return uiSurfaceStyleName()+" · "+glassFrostDepth()+"%";
    }

    private static void blurBitmapInPlace(android.graphics.Bitmap bmp,int radius) {
        int w=bmp.getWidth(), h=bmp.getHeight();
        if(w<=2||h<=2||radius<1) return;
        int[] src=new int[w*h], tmp=new int[w*h];
        bmp.getPixels(src,0,w,0,0,w,h);
        int div=radius*2+1;
        for(int pass=0;pass<2;pass++) {
            for(int y=0;y<h;y++) {
                int row=y*w, rSum=0, gSum=0, bSum=0;
                for(int i=-radius;i<=radius;i++) {
                    int c=src[row+Math.max(0,Math.min(w-1,i))];
                    rSum+=(c>>16)&0xFF; gSum+=(c>>8)&0xFF; bSum+=c&0xFF;
                }
                for(int x=0;x<w;x++) {
                    tmp[row+x]=0xFF000000|((rSum/div)<<16)|((gSum/div)<<8)|(bSum/div);
                    int cOut=src[row+Math.max(0,x-radius)];
                    int cIn=src[row+Math.min(w-1,x+radius+1)];
                    rSum+=((cIn>>16)&0xFF)-((cOut>>16)&0xFF);
                    gSum+=((cIn>>8)&0xFF)-((cOut>>8)&0xFF);
                    bSum+=(cIn&0xFF)-(cOut&0xFF);
                }
            }
            for(int x=0;x<w;x++) {
                int rSum=0, gSum=0, bSum=0;
                for(int i=-radius;i<=radius;i++) {
                    int c=tmp[Math.max(0,Math.min(h-1,i))*w+x];
                    rSum+=(c>>16)&0xFF; gSum+=(c>>8)&0xFF; bSum+=c&0xFF;
                }
                for(int y=0;y<h;y++) {
                    src[y*w+x]=0xFF000000|((rSum/div)<<16)|((gSum/div)<<8)|(bSum/div);
                    int cOut=tmp[Math.max(0,y-radius)*w+x];
                    int cIn=tmp[Math.min(h-1,y+radius+1)*w+x];
                    rSum+=((cIn>>16)&0xFF)-((cOut>>16)&0xFF);
                    gSum+=((cIn>>8)&0xFF)-((cOut>>8)&0xFF);
                    bSum+=(cIn&0xFF)-(cOut&0xFF);
                }
            }
        }
        bmp.setPixels(src,0,w,0,0,w,h);
    }

    private void updateFrostedFromDrawable(Drawable bg,int dimAlpha,String key) {
        if(bg==null || (key.equals(frostedBackdropKey) && frostedShader!=null)) return;
        try {
            int bw=240, bh=135;
            if(frostedBackdrop==null || frostedBackdrop.getWidth()!=bw || frostedBackdrop.getHeight()!=bh) {
                frostedBackdrop=android.graphics.Bitmap.createBitmap(bw,bh,android.graphics.Bitmap.Config.ARGB_8888);
            }
            android.graphics.Canvas cv=new android.graphics.Canvas(frostedBackdrop);
            cv.drawColor(0xFF0B101A);
            android.graphics.Rect saved=new android.graphics.Rect(bg.getBounds());
            try { bg.setBounds(0,0,bw,bh); bg.draw(cv); } finally { bg.setBounds(saved); }
            if(dimAlpha>0) cv.drawColor(Color.argb(dimAlpha,0,0,0));
            blurBitmapInPlace(frostedBackdrop,6);
            frostedShader=new android.graphics.BitmapShader(frostedBackdrop,
                    android.graphics.Shader.TileMode.CLAMP,android.graphics.Shader.TileMode.CLAMP);
            frostedBackdropKey=key;
            if(treatmentBlurLayer!=null && !"photo".equals(store.value("background","ember"))) {
                treatmentBlurLayer.setImageBitmap(frostedBackdrop);
                syncTreatmentLayers();
            }
        } catch(Exception|OutOfMemoryError ignored) {}
    }

    private void updateBlurredWallpaper(android.graphics.Bitmap photo, String key) {
        if(photo==null || (key.equals(blurredWallpaperKey) && blurredWallpaperBackdrop!=null)) {
            if(treatmentBlurLayer!=null && blurredWallpaperBackdrop!=null) {
                treatmentBlurLayer.setImageBitmap(blurredWallpaperBackdrop);
                syncTreatmentLayers();
            }
            return;
        }
        try {
            int bw=384, bh=216;
            if(blurredWallpaperBackdrop==null || blurredWallpaperBackdrop.getWidth()!=bw || blurredWallpaperBackdrop.getHeight()!=bh) {
                blurredWallpaperBackdrop=android.graphics.Bitmap.createBitmap(bw,bh,android.graphics.Bitmap.Config.ARGB_8888);
            }
            android.graphics.Canvas cv=new android.graphics.Canvas(blurredWallpaperBackdrop);
            int pw=Math.max(1,photo.getWidth()), ph=Math.max(1,photo.getHeight());
            float scale=Math.max((float)bw/pw,(float)bh/ph);
            float dw=pw*scale, dh=ph*scale;
            android.graphics.RectF dst=new android.graphics.RectF((bw-dw)/2f,(bh-dh)/2f,(bw+dw)/2f,(bh+dh)/2f);
            android.graphics.Paint bp=new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
            cv.drawBitmap(photo,null,dst,bp);
            blurBitmapInPlace(blurredWallpaperBackdrop,12);
            blurBitmapInPlace(blurredWallpaperBackdrop,8);
            blurredWallpaperKey=key;
            if(treatmentBlurLayer!=null) {
                treatmentBlurLayer.setImageBitmap(blurredWallpaperBackdrop);
                syncTreatmentLayers();
            }
        } catch(Exception|OutOfMemoryError ignored) {}
    }

    private void updateFrostedFromPhoto(android.graphics.Bitmap photo,int dimAlpha,String key) {
        if(photo==null || (key.equals(frostedBackdropKey) && frostedShader!=null)) return;
        try {
            int bw=240, bh=135;
            if(frostedBackdrop==null || frostedBackdrop.getWidth()!=bw || frostedBackdrop.getHeight()!=bh) {
                frostedBackdrop=android.graphics.Bitmap.createBitmap(bw,bh,android.graphics.Bitmap.Config.ARGB_8888);
            }
            android.graphics.Canvas cv=new android.graphics.Canvas(frostedBackdrop);
            cv.drawColor(0xFF0B101A);
            int pw=Math.max(1,photo.getWidth()), ph=Math.max(1,photo.getHeight());
            float scale=Math.max((float)bw/pw,(float)bh/ph);
            float dw=pw*scale, dh=ph*scale;
            android.graphics.RectF dst=new android.graphics.RectF((bw-dw)/2f,(bh-dh)/2f,(bw+dw)/2f,(bh+dh)/2f);
            android.graphics.Paint bp=new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
            cv.drawBitmap(photo,null,dst,bp);
            if(dimAlpha>0) cv.drawColor(Color.argb(dimAlpha,0,0,0));
            blurBitmapInPlace(frostedBackdrop,6);
            frostedShader=new android.graphics.BitmapShader(frostedBackdrop,
                    android.graphics.Shader.TileMode.CLAMP,android.graphics.Shader.TileMode.CLAMP);
            frostedBackdropKey=key;
            refreshGlassSurfaces();
        } catch(Exception|OutOfMemoryError ignored) {}
    }

    private void refreshGlassSurfaces() {
        if(dockBar!=null) dockBar.invalidate();
        if(recentSection!=null) recentSection.invalidate();
        if(searchBar!=null) searchBar.invalidate();
        for(View pill:topPillViews) pill.invalidate();
        if(activeBladeBox!=null) activeBladeBox.invalidate();
    }

    private Drawable glassPanelDrawable(final View anchor,final float cornerDp,final boolean isBlade) {
        return new Drawable() {
            private final android.graphics.Paint p=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            private final android.graphics.RectF r=new android.graphics.RectF();
            private final android.graphics.Matrix mat=new android.graphics.Matrix();
            private final int[] loc=new int[2];

            @Override public void draw(android.graphics.Canvas c) {
                r.set(getBounds());
                if(r.width()<=0 || r.height()<=0) return;
                float rad=isBlade?0f:px(cornerDp);
                String style=uiSurfaceStyle();
                boolean isGlass="glass".equals(style);
                boolean isSolid="solid".equals(style);
                int frost=overrideFrostPct>=0?overrideFrostPct:glassFrostDepth();

                if(!isSolid && glassRefractionEnabled() && frostedShader!=null && frostedBackdrop!=null
                        && anchor!=null && screenWidth>0 && screenHeight>0) {
                    anchor.getLocationOnScreen(loc);
                    mat.reset();
                    mat.setScale((float)screenWidth/frostedBackdrop.getWidth(),(float)screenHeight/frostedBackdrop.getHeight());
                    mat.postTranslate(-loc[0],-loc[1]);
                    frostedShader.setLocalMatrix(mat);
                    p.setStyle(android.graphics.Paint.Style.FILL);
                    p.setShader(frostedShader);
                    if(isBlade) c.drawRect(r,p); else c.drawRoundRect(r,rad,rad,p);
                    p.setShader(null);
                }

                int baseRgb=themed(0xFF171114,0xFF0E1725)&0x00FFFFFF;
                int topAlpha, botAlpha;
                if(isSolid) {
                    topAlpha=248; botAlpha=252;
                } else {
                    int eff=isBlade?Math.min(94,Math.max(48,frost+12)):frost;
                    topAlpha=Math.min(242,Math.round(eff*2.18f));
                    botAlpha=Math.min(248,Math.round(eff*2.44f));
                }
                p.setStyle(android.graphics.Paint.Style.FILL);
                p.setShader(new android.graphics.LinearGradient(0,r.top,0,r.bottom,
                        (topAlpha<<24)|baseRgb,(botAlpha<<24)|baseRgb,android.graphics.Shader.TileMode.CLAMP));
                if(isBlade) c.drawRect(r,p); else c.drawRoundRect(r,rad,rad,p);
                p.setShader(null);

                float sh=sheenMultiplier();
                if(isGlass) {
                    if(isBlade) {
                        float gleamH=Math.min(r.height(),px(280));
                        c.save();
                        c.clipRect(r.left,r.top,r.right,r.top+gleamH);
                        p.setShader(new android.graphics.LinearGradient(0,r.top,0,r.top+gleamH,
                                Color.argb(Math.min(76,Math.round(28*sh)),255,255,255),0x00FFFFFF,
                                android.graphics.Shader.TileMode.CLAMP));
                        c.drawRect(r,p);
                        c.restore();
                    } else {
                        c.save();
                        c.clipRect(r.left,r.top,r.right,r.top+r.height()*0.44f);
                        p.setShader(new android.graphics.LinearGradient(0,r.top,0,r.top+r.height()*0.44f,
                                Color.argb(Math.min(115,Math.round(44*sh)),255,255,255),0x00FFFFFF,
                                android.graphics.Shader.TileMode.CLAMP));
                        c.drawRoundRect(r,rad,rad,p);
                        c.restore();
                    }
                    p.setShader(null);
                }

                if(isBlade) {
                    float sw=Math.max(1f,px(1.6f));
                    p.setStyle(android.graphics.Paint.Style.STROKE);
                    p.setStrokeWidth(sw);
                    if(store.flag("contrast")) {
                        p.setColor(accent());
                    } else if(isGlass) {
                        p.setShader(new android.graphics.LinearGradient(0,r.top,0,r.bottom,
                                new int[]{Color.argb(Math.min(200,Math.round(112*sh)),255,255,255),0x28FFFFFF,0x44FFFFFF},
                                new float[]{0f,0.5f,1f},android.graphics.Shader.TileMode.CLAMP));
                    } else {
                        p.setColor(themed(0x28FFFFFF,0x44607D9E));
                    }
                    c.drawLine(r.left+sw/2f,r.top,r.left+sw/2f,r.bottom,p);
                    p.setShader(null);
                } else {
                    float sw=Math.max(1f,px(store.flag("contrast")?2f:1.35f));
                    p.setStyle(android.graphics.Paint.Style.STROKE);
                    p.setStrokeWidth(sw);
                    if(store.flag("contrast")) {
                        p.setColor(accent());
                    } else if(isGlass) {
                        p.setShader(new android.graphics.LinearGradient(0,r.top,0,r.bottom,
                                new int[]{Color.argb(Math.min(200,Math.round(98*sh)),255,255,255),0x1EFFFFFF,0x36FFFFFF},
                                new float[]{0f,0.55f,1f},android.graphics.Shader.TileMode.CLAMP));
                    } else {
                        p.setColor(themed(0x24FFFFFF,0x5547556D));
                    }
                    float inset=sw/2f;
                    r.inset(inset,inset);
                    c.drawRoundRect(r,Math.max(0f,rad-inset),Math.max(0f,rad-inset),p);
                    p.setShader(null);
                }
            }
            @Override public void setAlpha(int a) {}
            @Override public void setColorFilter(android.graphics.ColorFilter cf) {}
            @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        };
    }

    private Drawable glassControlDrawable(final View anchor,final boolean focused,final float cornerDp,
            final int tintCol,final boolean refractBackdrop,final boolean transparentWhenIdle) {
        return new Drawable() {
            private final android.graphics.Paint p=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            private final android.graphics.RectF r=new android.graphics.RectF();
            private final android.graphics.Matrix mat=new android.graphics.Matrix();
            private final int[] loc=new int[2];

            @Override public void draw(android.graphics.Canvas c) {
                if(!focused && transparentWhenIdle) return;
                r.set(getBounds());
                if(r.width()<=0 || r.height()<=0) return;
                float rad=cornerDp<0?r.height()/2f:px(cornerDp);
                String style=uiSurfaceStyle();
                boolean isGlass="glass".equals(style);
                boolean isSolid="solid".equals(style);
                float sh=sheenMultiplier();

                if(refractBackdrop && !isSolid && glassRefractionEnabled() && frostedShader!=null && frostedBackdrop!=null
                        && anchor!=null && screenWidth>0 && screenHeight>0) {
                    anchor.getLocationOnScreen(loc);
                    mat.reset();
                    mat.setScale((float)screenWidth/frostedBackdrop.getWidth(),(float)screenHeight/frostedBackdrop.getHeight());
                    mat.postTranslate(-loc[0],-loc[1]);
                    frostedShader.setLocalMatrix(mat);
                    p.setStyle(android.graphics.Paint.Style.FILL);
                    p.setShader(frostedShader);
                    c.drawRoundRect(r,rad,rad,p);
                    p.setShader(null);
                }

                p.setStyle(android.graphics.Paint.Style.FILL);
                if(focused) {
                    if(isGlass) {
                        int effTint=tintCol!=0?tintCol:accent();
                        int botTint=(effTint&0x00FFFFFF)|0x2A000000;
                        int topWhite=Color.argb(Math.min(132,Math.round(62*sh)),255,255,255);
                        p.setShader(new android.graphics.LinearGradient(0,r.top,0,r.bottom,
                                topWhite,botTint,android.graphics.Shader.TileMode.CLAMP));
                        c.drawRoundRect(r,rad,rad,p);
                        p.setShader(null);

                        c.save();
                        c.clipRect(r.left,r.top,r.right,r.top+r.height()*0.45f);
                        p.setShader(new android.graphics.LinearGradient(0,r.top,0,r.top+r.height()*0.45f,
                                Color.argb(Math.min(148,Math.round(74*sh)),255,255,255),0x00FFFFFF,
                                android.graphics.Shader.TileMode.CLAMP));
                        c.drawRoundRect(r,rad,rad,p);
                        c.restore();
                        p.setShader(null);
                    } else {
                        p.setColor(themed(0x38FFFFFF,0xFF2B4360));
                        c.drawRoundRect(r,rad,rad,p);
                    }
                } else {
                    if(refractBackdrop) {
                        int frost=overrideFrostPct>=0?overrideFrostPct:glassFrostDepth();
                        int baseRgb=themed(0xFF171114,0xFF0E1725)&0x00FFFFFF;
                        int a=isSolid?244:Math.min(220,Math.round(frost*1.9f));
                        p.setColor((a<<24)|baseRgb);
                        c.drawRoundRect(r,rad,rad,p);
                    } else {
                        p.setShader(new android.graphics.LinearGradient(0,r.top,0,r.bottom,
                                isGlass?0x12FFFFFF:0x0AFFFFFF,0x05FFFFFF,android.graphics.Shader.TileMode.CLAMP));
                        c.drawRoundRect(r,rad,rad,p);
                        p.setShader(null);
                    }
                    if(isGlass) {
                        c.save();
                        c.clipRect(r.left,r.top,r.right,r.top+r.height()*0.42f);
                        p.setShader(new android.graphics.LinearGradient(0,r.top,0,r.top+r.height()*0.42f,
                                Color.argb(Math.min(64,Math.round(22*sh)),255,255,255),0x00FFFFFF,
                                android.graphics.Shader.TileMode.CLAMP));
                        c.drawRoundRect(r,rad,rad,p);
                        c.restore();
                        p.setShader(null);
                    }
                }

                float sw=Math.max(1f,px(focused?(store.flag("contrast")?2.8f:2.0f):1.1f));
                p.setStyle(android.graphics.Paint.Style.STROKE);
                p.setStrokeWidth(sw);
                if(focused) {
                    if(store.flag("contrast") || !isGlass) {
                        p.setColor(accent());
                    } else {
                        int tc=tintCol!=0?tintCol:accent();
                        int rr=(((tc>>16)&0xFF)+510)/3;
                        int gg=(((tc>>8)&0xFF)+510)/3;
                        int bb=((tc&0xFF)+510)/3;
                        p.setColor(0xEC000000|(Math.min(255,rr)<<16)|(Math.min(255,gg)<<8)|Math.min(255,bb));
                    }
                } else if(isGlass) {
                    p.setShader(new android.graphics.LinearGradient(0,r.top,0,r.bottom,
                            Color.argb(Math.min(120,Math.round(58*sh)),255,255,255),0x16FFFFFF,
                            android.graphics.Shader.TileMode.CLAMP));
                } else {
                    p.setColor(0x1EFFFFFF);
                }
                float inset=sw/2f;
                r.inset(inset,inset);
                c.drawRoundRect(r,Math.max(0f,rad-inset),Math.max(0f,rad-inset),p);
                p.setShader(null);
            }
            @Override public void setAlpha(int a) {}
            @Override public void setColorFilter(android.graphics.ColorFilter cf) {}
            @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        };
    }

    private void render() {
        if (destroyed) return;
        stopSpringStages(); homeSpringStage=null; drawerSpringStage=null; recentSpringStage=null; dockSpringLens=null;
        if(drawerSearchAnimator!=null)drawerSearchAnimator.cancel();
        drawerFocusTitle=null;
        dockBar=null; searchBar=null;
        stopHeroVideo();handler.removeCallbacks(heroRotation);heroPanel=null;heroPrimary=null;
        refreshWallpaperPalette();
        final String desiredFocus=lastFocus;
        serial++; views.clear(); appViews.clear();appRows.clear(); dockViews.clear(); contentViews.clear();contentRows.clear(); topPillViews.clear();
        focusedFavorite=null; drawerScroll=null;homeScroll=null;homePage=null;drawerPage=null;recentSection=null;
        screenWidth=getResources().getDisplayMetrics().widthPixels;
        screenHeight=getResources().getDisplayMetrics().heightPixels;
        scale=Math.min(screenWidth/1920f,screenHeight/1080f);
        if(ambientAnimator!=null){ambientAnimator.cancel();ambientAnimator=null;}
        ambientBackground=null;ambientTexture=null;
        root=new FrameLayout(this) {
            @Override public void playSoundEffect(int soundConstant) {
                if(muteSystem()) return;
                super.playSoundEffect(soundConstant);
            }
        };
        root.setSoundEffectsEnabled(!muteSystem());
        root.setBackgroundColor(0xFF0B101A);
        applyBackground();
        contentHeight=screenHeight;
        content=new FrameLayout(this);
        content.setClipChildren(false);
        root.setClipChildren(false);
        root.addView(content,at(0,0,screenWidth,screenHeight));
        if (drawer) renderDrawer(); else renderHome();
        if (drawer||store.flag("show_dock",true)) buildDock();
        setContentView(root);
        syncSystemSoundMuting();
        root.getViewTreeObserver().addOnGlobalFocusChangeListener((oldFocus,newFocus)->{
            if(destroyed || sfx==null || oldFocus==null || newFocus==null || oldFocus==newFocus) return;
            if(android.os.SystemClock.uptimeMillis()-lastUserNavMs<280L) {
                sfx.playFocus(newFocus,screenWidth,lastUserNavDir);
            }
        });
        wireNavigation();
        View restore=views.get(desiredFocus);
        if (restore==null) restore=!appViews.isEmpty()?appViews.get(0):!dockViews.isEmpty()?dockViews.get(0):!topPillViews.isEmpty()?topPillViews.get(0):null;
        if (restore!=null) {
            final View focus=restore;
            root.post(()->{ if (!destroyed && focus.isAttachedToWindow()) focus.requestFocus(); });
        }
    }
    private void applyBackground() {
        String style=store.value("background","ember");
        int[] colors;
        switch (style) {
            case "ocean": colors=new int[]{0xFF102D40,0xFF07121F}; break;
            case "forest": colors=new int[]{0xFF17382E,0xFF080F16}; break;
            case "midnight": colors=new int[]{0xFF202C49,0xFF090D18}; break;
            case "app_glow": colors=new int[]{0xFF050405,0xFF050405}; break;
            case "solid_black": colors=new int[]{0xFF000000,0xFF000000}; break;
            case "solid_slate": colors=new int[]{0xFF18202B,0xFF18202B}; break;
            default: colors=new int[]{0xFF33211D,0xFF140E16}; break;
        }
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,colors));
        if("sky".equals(style)) {
            root.setBackground(buildSkyBackground());
        } else if("ember".equals(style)) {
            GradientDrawable base=new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{0xFF15100F,0xFF0A0809,0xFF050405});
            GradientDrawable warm=new GradientDrawable();warm.setGradientType(GradientDrawable.RADIAL_GRADIENT);warm.setGradientCenter(.08f,-.12f);warm.setGradientRadius(px(1250));warm.setColors(new int[]{0x36FF8048,0x00FF8048});
            GradientDrawable plum=new GradientDrawable();plum.setGradientType(GradientDrawable.RADIAL_GRADIENT);plum.setGradientCenter(1f,1.1f);plum.setGradientRadius(px(1000));plum.setColors(new int[]{0x2EAA3C8C,0x00AA3C8C});
            root.setBackground(new android.graphics.drawable.LayerDrawable(new Drawable[]{base,warm,plum}));
        }
        int dim=store.flag("contrast")?Math.max(55,store.number("dimming",0)):store.number("dimming",0);
        final int dimAlpha=Math.round(dim*2.55f);
        final String frostKey=paletteKey()+":dim="+dim;
        treatmentBlurLayer=new ImageView(this);
        treatmentBlurLayer.setScaleType(ImageView.ScaleType.FIT_XY);
        treatmentBlurLayer.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        if("photo".equals(style) && blurredWallpaperBackdrop!=null) {
            treatmentBlurLayer.setImageBitmap(blurredWallpaperBackdrop);
        } else if(frostedBackdrop!=null) {
            treatmentBlurLayer.setImageBitmap(frostedBackdrop);
        }
        root.addView(treatmentBlurLayer,new FrameLayout.LayoutParams(-1,-1));

        if ("photo".equals(style)) {
            String path=store.value("background_image","");
            if (!path.isEmpty()) {
                final String backgroundPaletteKey=paletteKey();
                ImageView image=new ImageView(this){
                    @Override public void setImageBitmap(android.graphics.Bitmap bitmap){
                        super.setImageBitmap(bitmap);
                        if(bitmap!=null&&!destroyed){
                            extractWallpaperColors(bitmap,backgroundPaletteKey);
                            updateFrostedFromPhoto(bitmap,dimAlpha,frostKey);
                            updateBlurredWallpaper(bitmap,path);
                        }
                    }
                };image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                wallpaperImageView=image;
                root.addView(image,new FrameLayout.LayoutParams(-1,-1));
                images.show(image,path,Math.min(screenWidth,1920),Math.min(screenHeight,1080));
            } else {
                wallpaperImageView=null;
                updateFrostedFromDrawable(root.getBackground(),dimAlpha,frostKey);
            }
        } else {
            wallpaperImageView=null;
            updateFrostedFromDrawable(root.getBackground(),dimAlpha,frostKey);
        }
        if("app_glow".equals(style)) {
            ambientBackground=new View(this);
            android.graphics.Bitmap mask=android.graphics.Bitmap.createBitmap(480,270,android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas=new android.graphics.Canvas(mask);
            android.graphics.Paint paint=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            paint.setShader(new android.graphics.RadialGradient(154,113,298,
                    new int[]{0x70FFFFFF,0x30FFFFFF,0x00FFFFFF},new float[]{0f,.45f,1f},android.graphics.Shader.TileMode.CLAMP));
            canvas.drawRect(0,0,480,270,paint);
            ambientTexture=new android.graphics.drawable.BitmapDrawable(getResources(),mask);
            ambientTexture.setGravity(Gravity.FILL);ambientTexture.setFilterBitmap(true);
            ambientTexture.setTint(ambientColor);ambientBackground.setBackground(ambientTexture);
            ambientBackground.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            root.addView(ambientBackground,new FrameLayout.LayoutParams(-1,-1));
        }

        treatmentOverlayLayer=new View(this);
        treatmentOverlayLayer.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        root.addView(treatmentOverlayLayer,new FrameLayout.LayoutParams(-1,-1));

        syncTreatmentLayers();
    }
    private String backgroundTreatment() { return store.value("background_treatment","dim"); }
    private String treatmentStyleName() {
        String t=backgroundTreatment();
        switch(t) {
            case "blur": return "Frosted Silk";
            case "vignette": return "Cinema Vignette";
            case "scrim": return "Horizon Scrim";
            case "studio": return "Studio Glass";
            default: return "Classic Dim";
        }
    }
    private int treatmentIntensity() { return store.number("dimming",0); }
    private Drawable buildTreatmentDrawable(String style,int intensity,int width,int height) {
        if(intensity<=0) return new ColorDrawable(Color.TRANSPARENT);
        final int alpha=Math.round(intensity*2.55f);
        if("dim".equals(style)) {
            return new ColorDrawable(Color.argb(alpha,0,0,0));
        }
        if("blur".equals(style)) {
            int veilAlpha=Math.round(alpha*0.35f);
            return new ColorDrawable(Color.argb(veilAlpha,0,0,0));
        }
        if("vignette".equals(style)) {
            return new Drawable() {
                private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
                private final RectF rect=new RectF();
                @Override public void draw(Canvas c) {
                    rect.set(getBounds());
                    float w=rect.width(), h=rect.height();
                    if(w<=0||h<=0) return;
                    float cx=w*0.48f, cy=h*0.42f;
                    float radius=Math.max(w,h)*0.72f;
                    RadialGradient radial=new RadialGradient(cx,cy,radius,
                            new int[]{0x00000000, 0x00000000, Color.argb(Math.round(alpha*0.45f),0,0,0), Color.argb(alpha,0,0,0)},
                            new float[]{0f, 0.45f, 0.72f, 1f}, Shader.TileMode.CLAMP);
                    paint.setShader(radial);
                    c.drawRect(rect,paint);
                    LinearGradient shelf=new LinearGradient(0,h*0.62f,0,h,
                            0x00000000, Color.argb(Math.round(alpha*0.80f),0,0,0), Shader.TileMode.CLAMP);
                    paint.setShader(shelf);
                    c.drawRect(0,h*0.62f,w,h,paint);
                }
                @Override public void setAlpha(int a) {}
                @Override public void setColorFilter(ColorFilter cf) {}
                @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
            };
        }
        if("scrim".equals(style)) {
            return new Drawable() {
                private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
                private final RectF rect=new RectF();
                @Override public void draw(Canvas c) {
                    rect.set(getBounds());
                    float w=rect.width(), h=rect.height();
                    if(w<=0||h<=0) return;
                    LinearGradient grad=new LinearGradient(0,0,0,h,
                            new int[]{0x00000000, 0x00000000, Color.argb(Math.round(alpha*0.28f),0,0,0), Color.argb(Math.round(alpha*0.75f),0,0,0), Color.argb(alpha,0,0,0)},
                            new float[]{0f, 0.32f, 0.50f, 0.76f, 1f}, Shader.TileMode.CLAMP);
                    paint.setShader(grad);
                    c.drawRect(rect,paint);
                }
                @Override public void setAlpha(int a) {}
                @Override public void setColorFilter(ColorFilter cf) {}
                @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
            };
        }
        if("studio".equals(style)) {
            return new Drawable() {
                private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
                private final RectF rect=new RectF();
                @Override public void draw(Canvas c) {
                    rect.set(getBounds());
                    float w=rect.width(), h=rect.height();
                    if(w<=0||h<=0) return;
                    float cx=w*0.48f, cy=h*0.42f;
                    float radius=Math.max(w,h)*0.70f;
                    RadialGradient radial=new RadialGradient(cx,cy,radius,
                            new int[]{0x00000000, 0x00000000, Color.argb(Math.round(alpha*0.35f),0,0,0), Color.argb(Math.round(alpha*0.85f),0,0,0)},
                            new float[]{0f, 0.40f, 0.70f, 1f}, Shader.TileMode.CLAMP);
                    paint.setShader(radial);
                    c.drawRect(rect,paint);
                    LinearGradient shelf=new LinearGradient(0,h*0.65f,0,h,
                            0x00000000, Color.argb(Math.round(alpha*0.65f),0,0,0), Shader.TileMode.CLAMP);
                    paint.setShader(shelf);
                    c.drawRect(0,h*0.65f,w,h,paint);
                }
                @Override public void setAlpha(int a) {}
                @Override public void setColorFilter(ColorFilter cf) {}
                @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
            };
        }
        return new ColorDrawable(Color.argb(alpha,0,0,0));
    }
    private void syncTreatmentLayers() {
        if(treatmentOverlayLayer==null) return;
        String style=overrideTreatmentStyle!=null?overrideTreatmentStyle:backgroundTreatment();
        int intensity=overrideTreatmentIntensity>=0?overrideTreatmentIntensity:treatmentIntensity();
        if(store.flag("contrast")) intensity=Math.max(55,intensity);

        boolean needsBlur="blur".equals(style)||"studio".equals(style);
        if(treatmentBlurLayer!=null) {
            android.graphics.Bitmap blurBmp="photo".equals(store.value("background","ember"))?blurredWallpaperBackdrop:frostedBackdrop;
            if(blurBmp!=null) treatmentBlurLayer.setImageBitmap(blurBmp);
            if(needsBlur && intensity>0 && blurBmp!=null) {
                treatmentBlurLayer.setVisibility(View.VISIBLE);
                if(wallpaperImageView!=null) {
                    treatmentBlurLayer.setAlpha(1f);
                    float sharpAlpha="studio".equals(style)?Math.max(0f,1f-(intensity*0.75f/100f)):Math.max(0f,1f-(intensity/100f));
                    wallpaperImageView.setAlpha(sharpAlpha);
                } else {
                    float blurAlpha="studio".equals(style)?(intensity*0.75f/100f):(intensity/100f);
                    treatmentBlurLayer.setAlpha(Math.min(1f,Math.max(0f,blurAlpha)));
                }
            } else {
                treatmentBlurLayer.setVisibility(View.GONE);
                if(wallpaperImageView!=null) wallpaperImageView.setAlpha(1f);
            }
        } else if(wallpaperImageView!=null) {
            wallpaperImageView.setAlpha(1f);
        }
        if(intensity>0) {
            treatmentOverlayLayer.setVisibility(View.VISIBLE);
            treatmentOverlayLayer.setBackground(buildTreatmentDrawable(style,intensity,screenWidth,screenHeight));
        } else {
            treatmentOverlayLayer.setVisibility(View.GONE);
        }
    }
    private boolean backgroundGlow(){return "app_glow".equals(store.value("background","ember"));}
    private void updateAmbient(String pkg){
        if(!backgroundGlow()||ambientTexture==null)return;
        int color=extractAppColor(pkg)|0xFF000000;
        if(ambientAnimator!=null)ambientAnimator.cancel();
        if(color==ambientColor)return;
        final android.graphics.drawable.BitmapDrawable texture=ambientTexture;
        ambientAnimator=android.animation.ValueAnimator.ofObject(new android.animation.ArgbEvaluator(),ambientColor,color);
        ambientAnimator.setDuration(focusDuration(360));
        ambientAnimator.addUpdateListener(a->{ambientColor=(Integer)a.getAnimatedValue();texture.setTint(ambientColor);});
        ambientAnimator.start();
    }
    private boolean topControlsVisible(){return !store.flag("show_dock",true);}
    private void renderHome() {
        homeScroll=scroll();content.addView(homeScroll,new FrameLayout.LayoutParams(-1,-1));
        homePage=new LinearLayout(this);homePage.setOrientation(LinearLayout.VERTICAL);
        homePage.setPadding(px(96),px(36),px(96),px(96));homePage.setClipChildren(false);homePage.setClipToPadding(false);
        homeScroll.addView(homePage,new ScrollView.LayoutParams(-1,-2));
        header=new FrameLayout(this);
        LinearLayout.LayoutParams headerParams=new LinearLayout.LayoutParams(screenWidth,px(330));headerParams.topMargin=-px(36);headerParams.leftMargin=-px(96);headerParams.rightMargin=-px(96);
        homePage.addView(header,headerParams);
        updateHeader();
        FrameLayout heading=new FrameLayout(this);
        TextView title=text("Home",38,foreground()); title.setTypeface(medium);
        heading.addView(title,at(0,0,px(500),px(52)));
        TextView hint=text(moving.isEmpty()?"":"",21,muted());
        hint.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);
        heading.addView(hint,at(px(480),px(5),innerWidth()-px(480),px(44)));
        LinearLayout.LayoutParams headingParams=new LinearLayout.LayoutParams(-1,px(64));headingParams.topMargin=px(42);
        homePage.addView(heading,headingParams);
        List<String> favorite=favorites();
        HorizontalScrollView appsScroll=new HorizontalScrollView(this) {
            @Override public void playSoundEffect(int soundConstant) {
                if(muteSystem()) return;
                super.playSoundEffect(soundConstant);
            }
        };
        appsScroll.setSoundEffectsEnabled(!muteSystem());
        appsScroll.setHorizontalScrollBarEnabled(false);
        appsScroll.setClipChildren(false);appsScroll.setClipToPadding(false);appsScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout appsRow=new LinearLayout(this);appsRow.setClipChildren(false);appsRow.setClipToPadding(false);
        int rowTopPad=Math.max(px(82),px(glowRadiusSetting()+34));
        int rowBotPad=Math.max(px(44),px(glowRadiusSetting()-6));
        appsRow.setPadding(px(88),rowTopPad,px(88),rowBotPad);
        List<View> navigationRow=new ArrayList<>();appRows.add(navigationRow);
        int iconSize=px(columns()==4?176:columns()==8?112:144);
        for(int i=0;i<favorite.size();i++) {
            View tile=appTile(favorite.get(i),iconSize,iconSize,false);
            LinearLayout.LayoutParams tileParams=new LinearLayout.LayoutParams(iconSize,iconSize+px(58));
            if(i+1<favorite.size())tileParams.rightMargin=px(52);
            appsRow.addView(tile,tileParams);appViews.add(tile);navigationRow.add(tile);
        }
        homeSpringStage=new SpringStage(appsRow,true);
        appsScroll.addView(homeSpringStage.host);
        LinearLayout.LayoutParams appsParams=new LinearLayout.LayoutParams(screenWidth,-2);
        appsParams.leftMargin=-px(96);appsParams.rightMargin=-px(96);
        appsParams.topMargin=px(48)-rowTopPad;appsParams.bottomMargin=px(56)-rowBotPad;
        homePage.addView(appsScroll,appsParams);
        if (favorite.isEmpty()) {
            TextView empty=text("Your home, your choices.\nOpen All Apps and hold OK to add an app to Home.",28,muted());
            empty.setGravity(Gravity.CENTER);homePage.addView(empty,new LinearLayout.LayoutParams(-1,px(190)));
        }
        contentRail=new LinearLayout(this); contentRail.setOrientation(LinearLayout.VERTICAL);
        contentRail.setClipChildren(false);contentRail.setClipToPadding(false);
        LinearLayout.LayoutParams railParams=new LinearLayout.LayoutParams(screenWidth,-2);
        railParams.leftMargin=-px(96);railParams.rightMargin=-px(96);railParams.topMargin=px(24);
        homePage.addView(contentRail,railParams);
        root.post(this::updateRecommendationRows);
    }
    private String greetingText() {
        int h=java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        return h>=5&&h<12?"GOOD MORNING":h>=12&&h<17?"GOOD AFTERNOON":h>=17&&h<22?"GOOD EVENING":"GOOD NIGHT";
    }
    private View buildTopPillBar() {
        topPillViews.clear();
        LinearLayout bar=new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setClipChildren(false);bar.setClipToPadding(false);
        addTopPill(bar,"pill.inputs","Inputs",LineIcon.HDMI,this::quickInputsMenu);
        addTopPill(bar,"pill.wifi","Wi-Fi",LineIcon.WIFI,()->openSystemIntent(Settings.ACTION_WIFI_SETTINGS,Settings.ACTION_WIRELESS_SETTINGS));
        addTopPill(bar,"pill.apps",drawer?"Home":"All Apps",drawer?LineIcon.HOME:LineIcon.SPARKLES,()->{ hideKeyboard(); drawer=!drawer; search=""; lastFocus=""; render(); });
        addTopPill(bar,"pill.settings","Settings",LineIcon.SETTINGS,this::settingsMenu);
        return bar;
    }
    private void addTopPill(LinearLayout parent,String key,String label,int[] iconOps,Runnable action) {
        LinearLayout pill=new LinearLayout(this);
        pill.setSoundEffectsEnabled(!muteSystem());
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setId(View.generateViewId());
        pill.setFocusable(true);pill.setClickable(true);pill.setFocusableInTouchMode(true);
        pill.setPadding(px(18),px(10),px(20),px(10));
        pill.setBackground(glassControlDrawable(pill,false,-1f,accent(),true,false));
        ImageView icon=new ImageView(this);
        icon.setImageDrawable(new LineIcon(iconOps,0xEAFFFFFF));
        LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(px(20),px(20));
        ip.rightMargin=px(10);
        pill.addView(icon,ip);
        TextView tv=text(label,26,0xF2FFFFFF);
        tv.setTypeface(medium);
        tv.setSingleLine(true);
        pill.addView(tv);
        pill.setOnFocusChangeListener((v,has)->{
            pill.setBackground(glassControlDrawable(pill,has,-1f,accent(),true,false));
            icon.setImageDrawable(new LineIcon(iconOps,has?accent():0xEAFFFFFF));
            tv.setTextColor(has?foreground():0xEAFFFFFF);
            pill.animate().scaleX(has&&!instantFocus()?1.06f:1f).scaleY(has&&!instantFocus()?1.06f:1f).translationZ(has?px(14):0).setDuration(focusDuration(130)).start();
            if(has){lastFocus=key;if(!drawer&&homeScroll!=null)homeScroll.post(()->homeScroll.smoothScrollTo(0,0));}
        });
        pill.setOnClickListener(v->{
            if(sfx!=null && !"pill.settings".equals(key) && !"pill.inputs".equals(key)) sfx.playSelect(v,screenWidth);
            action.run();
        });
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,px(64));
        if(parent.getChildCount()>0)lp.leftMargin=px(12);
        parent.addView(pill,lp);
        views.put(key,pill);
        topPillViews.add(pill);
    }
    private Drawable glassPillDrawable(boolean focused) {
        return new Drawable() {
            private final android.graphics.Paint p=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            private final android.graphics.RectF r=new android.graphics.RectF();
            @Override public void draw(android.graphics.Canvas c) {
                r.set(getBounds());
                float rad=r.height()/2f;
                p.setStyle(android.graphics.Paint.Style.FILL);
                p.setShader(new android.graphics.LinearGradient(0,r.top,0,r.bottom,
                        focused?0x42FFFFFF:0x1EFFFFFF,focused?0x18FFFFFF:0x0AFFFFFF,android.graphics.Shader.TileMode.CLAMP));
                c.drawRoundRect(r,rad,rad,p);
                p.setShader(null);
                p.setStyle(android.graphics.Paint.Style.STROKE);
                p.setStrokeWidth(Math.max(1f,px(focused?2f:1.2f)));
                p.setColor(focused?(store.flag("contrast")?accent():0xD8FFFFFF):0x32FFFFFF);
                float inset=p.getStrokeWidth()/2f;
                r.inset(inset,inset);
                c.drawRoundRect(r,rad,rad,p);
            }
            @Override public void setAlpha(int a) {}
            @Override public void setColorFilter(android.graphics.ColorFilter cf) {}
            @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        };
    }
    private void quickInputsMenu() {
        List<TvInputInfo> visible=hdmiInputs();
        List<String> labels=new ArrayList<>();List<Runnable> actions=new ArrayList<>();
        add(labels,actions,"§SWITCH INPUT",null);
        for(TvInputInfo input:visible) {
            add(labels,actions,"@hdmi|"+inputName(input),()->openInput(input.getId()));
        }
        if(visible.isEmpty()) {
            add(labels,actions,"No active HDMI inputs shown",this::inputMenu);
        }
        add(labels,actions,"§MANAGE",null);
        add(labels,actions,"@settings|Configure visible HDMI inputs",this::inputMenu);
        menu("TV Inputs",labels,actions,null);
    }
    private LinearLayout clockBlock(){
        LinearLayout block=new LinearLayout(this);block.setOrientation(LinearLayout.VERTICAL);
        if(store.flag("show_greeting",true)){
            TextView greeting=text(greetingText(),22,muted());greeting.setTypeface(medium);greeting.setLetterSpacing(.14f);
            LinearLayout.LayoutParams gp=new LinearLayout.LayoutParams(-2,-2);gp.bottomMargin=px(16);block.addView(greeting,gp);
        }
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.BOTTOM);row.setBaselineAligned(true);
        TextClock time=new TextClock(this);time.setFormat12Hour("h:mm");time.setFormat24Hour("H:mm");
        time.setTypeface(clockTypeface());time.setTextColor(foreground());time.setIncludeFontPadding(false);
        String cStyle=store.value("clock_style","modern");
        if("thin".equals(cStyle)) time.setLetterSpacing(0.04f);
        else if("mono".equals(cStyle)) time.setLetterSpacing(0.06f);
        time.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,px(store.number("clock_size",176)));
        row.addView(time,new LinearLayout.LayoutParams(-2,-2));
        View separator=new View(this);separator.setBackgroundColor(0x55FFFFFF);
        LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(px(1),px(52));sp.leftMargin=px(38);sp.rightMargin=px(32);sp.bottomMargin=px(32);row.addView(separator,sp);
        TextClock date=new TextClock(this);date.setFormat12Hour("EEEE, d MMMM");date.setFormat24Hour("EEEE, d MMMM");
        date.setTypeface("editorial".equals(cStyle)?Typeface.SERIF:medium);date.setTextColor(foreground());date.setIncludeFontPadding(false);
        date.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,px(28));date.setMaxLines(2);
        LinearLayout.LayoutParams dp=new LinearLayout.LayoutParams(0,-2,1f);row.addView(date,dp);
        block.addView(row,new LinearLayout.LayoutParams(-1,-2));return block;
    }
    private void updateHeader() {
        if (header==null || drawer) return;
        stopHeroVideo();handler.removeCallbacks(heroRotation);heroPanel=null;heroPrimary=null;
        header.removeAllViews();header.getLayoutParams().height=px(330);header.requestLayout();
        FrameLayout barHost=new FrameLayout(this);
        LinearLayout clock=clockBlock();clock.setPadding(px(96),px(32),px(96),0);
        barHost.addView(clock,new FrameLayout.LayoutParams(-1,-1));
        topPillViews.clear();
        if(topControlsVisible()) {
            View pills=buildTopPillBar();
            FrameLayout.LayoutParams pp=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.RIGHT);
            pp.topMargin=px(250);pp.rightMargin=px(96);
            barHost.addView(pills,pp);
        }
        if(store.flag("show_glance",true)) {
            glanceWidget=buildGlanceWidget();
            FrameLayout.LayoutParams gp=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.RIGHT);
            gp.topMargin=px(36);gp.rightMargin=px(96);
            barHost.addView(glanceWidget,gp);
            if(weatherService!=null) {
                weatherService.fetch(store,false,this::updateGlanceWeather);
            }
        } else {
            glanceWidget=null;
        }
        header.addView(barHost,new FrameLayout.LayoutParams(-1,-1)); clockPanel=barHost;
        String source=store.value("featured_source","");
        if (!store.flag("featured") || !store.flag("recommendations_master") || !store.recommendations(source) || !byPackage.containsKey(source)) return;
        final int current=serial;
        recommendations.load(source,feed->{
            if (destroyed || current!=serial || drawer) return;
            RecommendationRepository.Item item=feed.featured();
            if (!HomePolicy.banner(store.flag("featured"),source,store.flag("recommendations_master"),
                    store.recommendations(source),!feed.items.isEmpty(),item!=null)) return;
            heroItems=new ArrayList<>();for(RecommendationRepository.Item entry:feed.items)if(!entry.artwork.isEmpty())heroItems.add(entry);
            heroIndex=Math.min(heroIndex,heroItems.size()-1);showHero(false);
        });
    }
    private int heroHeight(boolean expanded) {
        int percent=expanded?store.number("hero_height",0):48;
        if(expanded&&percent==0)return Math.max(screenHeight*60/100,Math.min(screenHeight*85/100,Math.round(screenWidth/heroArtworkRatio)));
        return screenHeight*Math.max(40,Math.min(85,percent))/100;
    }
    private void stopHeroVideo() {
        if(heroVideo!=null){heroVideo.stopPlayback();if(heroVideo.getParent() instanceof ViewGroup)((ViewGroup)heroVideo.getParent()).removeView(heroVideo);heroVideo=null;}
        if(heroImage!=null)heroImage.setVisibility(View.VISIBLE);
    }
    private void resizeHero(boolean expanded) {
        if(header==null||heroPanel==null)return;
        header.getLayoutParams().height=heroHeight(expanded);header.requestLayout();
        if(expanded&&homeScroll!=null)homeScroll.post(()->homeScroll.smoothScrollTo(0,0));
        if(!expanded)stopHeroVideo();
    }
    private void rotateHero() {
        if(destroyed||heroPanel==null||!hasWindowFocus()||!store.flag("hero_rotate"))return;
        if(!heroPanel.hasFocus()&&heroVideo==null)changeHero(1,false);
        else handler.postDelayed(heroRotation,10000);
    }
    private void changeHero(int delta,boolean focus) {
        if(heroItems.isEmpty())return;
        heroIndex=(heroIndex+delta+heroItems.size())%heroItems.size();showHero(focus);
    }
    private boolean usablePreview(RecommendationRepository.Item item) {
        if(item.preview.isEmpty())return false;
        String scheme=Uri.parse(item.preview).getScheme();
        return "https".equals(scheme)||"content".equals(scheme)||"android.resource".equals(scheme);
    }
    private void playHeroPreview(RecommendationRepository.Item item,FrameLayout hero) {
        if(!store.flag("hero_video",true)||!usablePreview(item))return;
        if(heroVideo!=null){stopHeroVideo();return;}
        VideoView video=new VideoView(this);heroVideo=video;
        FrameLayout.LayoutParams videoParams=new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER);
        hero.addView(video,1,videoParams);
        video.setOnPreparedListener(player->{
            if(heroVideo!=video){video.stopPlayback();return;}
            player.setVolume(store.flag("hero_sound")?1f:0f,store.flag("hero_sound")?1f:0f);
            video.start();
        });
        video.setOnCompletionListener(player->stopHeroVideo());
        video.setOnErrorListener((player,what,extra)->{stopHeroVideo();toast("Preview unavailable. Open the title in its app.");return true;});
        try{video.setVideoURI(Uri.parse(item.preview));}catch(Exception error){stopHeroVideo();toast("Preview unavailable. Open the title in its app.");}
    }
    private void showHero(boolean focus) {
        if(heroItems.isEmpty()||header==null||drawer)return;
        stopHeroVideo();handler.removeCallbacks(heroRotation);heroWantsFocus=focus;
        RecommendationRepository.Item item=heroItems.get(heroIndex);
        FrameLayout old=heroPanel;
        FrameLayout hero=new FrameLayout(this);heroPanel=hero;
        FrameLayout scenery=new FrameLayout(this) {
            private final android.graphics.Paint mask=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            @Override public void draw(android.graphics.Canvas canvas) {
                int layer=canvas.saveLayer(0,0,getWidth(),getHeight(),null);
                super.draw(canvas);
                mask.setShader(new android.graphics.LinearGradient(0,Math.max(0,getHeight()-px(320)),0,getHeight(),Color.BLACK,Color.TRANSPARENT,android.graphics.Shader.TileMode.CLAMP));
                mask.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN));
                canvas.drawRect(0,0,getWidth(),getHeight(),mask);canvas.restoreToCount(layer);
            }
        };
        scenery.setBackgroundColor(0xFF090909);hero.addView(scenery,new FrameLayout.LayoutParams(-1,-1));
        ImageView image=new ImageView(this) {
            @Override public void setImageBitmap(android.graphics.Bitmap bitmap) {
                super.setImageBitmap(bitmap);
                if(bitmap!=null&&!destroyed&&heroPanel==hero&&hero.getParent()==header){
                    heroArtworkRatio=(float)bitmap.getWidth()/Math.max(1,bitmap.getHeight());
                    updateArtworkMatrix();
                    boolean takeFocus=heroWantsFocus;clockPanel.setVisibility(View.GONE);if(old!=null)header.removeView(old);hero.setVisibility(View.VISIBLE);resizeHero(takeFocus||hero.hasFocus());wireNavigation();
                    if(takeFocus&&heroPrimary!=null)heroPrimary.requestFocus();
                }
            }
            private void updateArtworkMatrix() {
                if(!(getDrawable() instanceof android.graphics.drawable.BitmapDrawable)||getWidth()==0)return;
                android.graphics.Bitmap bitmap=((android.graphics.drawable.BitmapDrawable)getDrawable()).getBitmap();
                float fit=(float)getWidth()/Math.max(1,bitmap.getWidth());
                android.graphics.Matrix matrix=new android.graphics.Matrix();matrix.setScale(fit,fit);setImageMatrix(matrix);
            }
            @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){super.onSizeChanged(w,h,oldw,oldh);updateArtworkMatrix();}
        };
        heroImage=image;image.setScaleType(ImageView.ScaleType.MATRIX);scenery.addView(image,new FrameLayout.LayoutParams(-1,-1));
        View scrim=new View(this);scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,new int[]{0xF5090909,0xAA090909,0x00090909}));
        scenery.addView(scrim,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout words=new LinearLayout(this);words.setOrientation(LinearLayout.VERTICAL);words.setGravity(Gravity.CENTER_VERTICAL);words.setPadding(px(96),px(32),px(24),px(28));
        words.addView(text("FROM "+name(item.source).toUpperCase(Locale.ROOT),21,accent()));
        TextView title=text(item.title,54,foreground());title.setTypeface(medium);title.setMaxLines(2);title.setEllipsize(android.text.TextUtils.TruncateAt.END);words.addView(title);
        TextView detail=text(item.description,24,muted());detail.setMaxLines(3);detail.setEllipsize(android.text.TextUtils.TruncateAt.END);words.addView(detail);
        LinearLayout controls=new LinearLayout(this);controls.setPadding(0,px(22),0,px(14));
        Button open=button("Play",()->openTitle(item));heroPrimary=open;open.setId(View.generateViewId());
        controls.addView(open,new LinearLayout.LayoutParams(px(280),px(64)));
        List<Button> buttons=new ArrayList<>();buttons.add(open);
        if(store.flag("hero_video",true)&&usablePreview(item)){
            Button preview=button("Watch preview",()->playHeroPreview(item,scenery));preview.setId(View.generateViewId());
            LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(px(230),px(64));params.leftMargin=px(16);controls.addView(preview,params);buttons.add(preview);
        }
        words.addView(controls);
        TextView pages=text((heroIndex+1)+" / "+heroItems.size(),21,muted());words.addView(pages);
        hero.addView(words,new FrameLayout.LayoutParams(px(960),-1));
        for(Button button:buttons){
            button.setOnFocusChangeListener((v,has)->{
                v.setBackground(shape(has?themed(0x66FFFFFF,0xFF365879):themed(0x26FFFFFF,0xDD1C2C40),has?accent():0,has?2:0));
                if(has)resizeHero(true);else hero.post(()->{if(heroPanel==hero&&!hero.hasFocus())resizeHero(false);});
            });
            button.setOnKeyListener((v,key,event)->{
                if(event.getAction()!=KeyEvent.ACTION_DOWN)return false;
                if(key==KeyEvent.KEYCODE_DPAD_DOWN){if(v==open&&buttons.size()>1){buttons.get(1).requestFocus();return true;}stopHeroVideo();if(!appViews.isEmpty())appViews.get(0).requestFocus();else if(!dockViews.isEmpty())dockViews.get(0).requestFocus();return true;}
                if(key==KeyEvent.KEYCODE_DPAD_UP){open.requestFocus();return true;}
                if(key==KeyEvent.KEYCODE_DPAD_LEFT||key==KeyEvent.KEYCODE_DPAD_RIGHT){changeHero(key==KeyEvent.KEYCODE_DPAD_LEFT?-1:1,true);return true;}
                if(key==KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE){playHeroPreview(item,scenery);return true;}
                if(key==KeyEvent.KEYCODE_BACK&&heroVideo!=null){stopHeroVideo();return true;}
                return false;
            });
        }
        // Left/Right browses titles; Down reaches the optional preview action, then Home.
        open.setOnLongClickListener(v->{if(buttons.size()>1)buttons.get(1).requestFocus();else toast("This title has no shared video preview.");return true;});
        hero.setVisibility(View.INVISIBLE);header.addView(hero,new FrameLayout.LayoutParams(-1,-1));
        images.show(image,item.artwork,Math.min(screenWidth,1920),heroHeight(true));
        if(focus)hero.post(()->{if(heroPanel==hero&&heroWantsFocus)open.requestFocus();});
        wireNavigation();if(store.flag("hero_rotate"))handler.postDelayed(heroRotation,10000);
    }
    private void updateDrawerFocusTitle(String newTitle){
        if(drawerFocusTitle==null||newTitle==null)return;
        CharSequence cur=drawerFocusTitle.getText();
        if(cur!=null&&cur.toString().equals(newTitle))return;
        drawerFocusTitle.animate().cancel();
        final float shift=px(18);
        drawerFocusTitle.animate().alpha(0f).translationX(-shift).setDuration(120).withEndAction(()->{
            if(drawerFocusTitle==null)return;
            drawerFocusTitle.setText(newTitle);
            drawerFocusTitle.setTranslationX(shift);
            drawerFocusTitle.animate().alpha(1f).translationX(0f).setDuration(200).start();
        }).start();
    }
    private void resizeDrawerSearch(EditText filter,boolean expanded){
        if(drawerSearchAnimator!=null)drawerSearchAnimator.cancel();
        int target=expanded?px(520):px(220);
        int from=filter.getWidth()>0?filter.getWidth():filter.getLayoutParams().width;
        if(from==target)return;
        drawerSearchAnimator=android.animation.ValueAnimator.ofInt(from,target);
        drawerSearchAnimator.setDuration(focusDuration(220));drawerSearchAnimator.setInterpolator(crispDecel);
        drawerSearchAnimator.addUpdateListener(anim->{
            ViewGroup.LayoutParams lp=filter.getLayoutParams();lp.width=(Integer)anim.getAnimatedValue();filter.setLayoutParams(lp);
        });drawerSearchAnimator.start();
    }
    private void renderDrawer() {
        ScrollView page=scroll();drawerScroll=page;page.setClipChildren(false);page.setClipToPadding(false);
        content.addView(page,new FrameLayout.LayoutParams(-1,-1));
        drawerPage=new LinearLayout(this);drawerPage.setOrientation(LinearLayout.VERTICAL);
        drawerPage.setPadding(px(96),px(36),px(96),px(120));drawerPage.setClipChildren(false);drawerPage.setClipToPadding(false);
        page.addView(drawerPage,new ScrollView.LayoutParams(-1,-2));
        topPillViews.clear();

        FrameLayout headerRow=new FrameLayout(this);
        headerRow.setClipChildren(false);headerRow.setClipToPadding(false);
        LinearLayout clock=clockBlock();
        headerRow.addView(clock,new FrameLayout.LayoutParams(-1,-2,Gravity.TOP|Gravity.LEFT));

        EditText filter=new EditText(this);filter.setId(View.generateViewId());filter.setSingleLine(true);
        filter.setSoundEffectsEnabled(!muteSystem());filter.setShowSoftInputOnFocus(false);searchBar=filter;
        filter.setTypeface(regular);filter.setTextColor(foreground());filter.setHintTextColor(muted());filter.setHint("Search apps");
        filter.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,px(24));filter.setPadding(px(22),0,px(22),0);
        Drawable searchIcon=getDrawable(android.R.drawable.ic_menu_search).mutate();searchIcon.setTint(foreground());searchIcon.setBounds(0,0,px(26),px(26));
        filter.setCompoundDrawables(searchIcon,null,null,null);filter.setCompoundDrawablePadding(px(12));
        filter.setBackground(glassControlDrawable(filter,false,28f,accent(),true,false));
        filter.setText(search);filter.setSelectAllOnFocus(true);filter.setContentDescription("Search all apps");
        filter.setOnFocusChangeListener((v,has)->{
            filter.setBackground(glassControlDrawable(filter,has,28f,accent(),true,false));
            resizeDrawerSearch(filter,has||!filter.getText().toString().isEmpty());
            if(has){
                if(drawerScroll!=null)drawerScroll.smoothScrollTo(0,0);
                updateDrawerFocusTitle("Find an app");
            }else if(!has&&!appViews.isEmpty()&&lastFocus!=null&&byPackage.containsKey(lastFocus)){
                updateDrawerFocusTitle(name(lastFocus));
            }
            if(!has)hideKeyboard();
        });
        filter.setOnClickListener(v->((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(filter,InputMethodManager.SHOW_IMPLICIT));
        filter.setOnKeyListener((v,key,event)->{
            if(key==KeyEvent.KEYCODE_DPAD_DOWN&&event.getAction()==KeyEvent.ACTION_DOWN&&!appViews.isEmpty()){appViews.get(0).requestFocus();return true;}
            return false;
        });
        FrameLayout.LayoutParams searchParams=new FrameLayout.LayoutParams(search.isEmpty()?px(220):px(520),px(58),Gravity.TOP|Gravity.RIGHT);
        searchParams.topMargin=px(18);
        headerRow.addView(filter,searchParams);
        drawerPage.addView(headerRow,new LinearLayout.LayoutParams(-1,-2));

        String initialTitle=apps.isEmpty()?"All Apps":name(apps.get(0).pkg);
        drawerFocusTitle=text(initialTitle,52,foreground());
        drawerFocusTitle.setTypeface(regular);drawerFocusTitle.setLetterSpacing(-0.02f);
        drawerFocusTitle.setSingleLine(true);drawerFocusTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleParams=new LinearLayout.LayoutParams(-1,px(76));
        titleParams.topMargin=px(28);titleParams.bottomMargin=px(16);
        drawerPage.addView(drawerFocusTitle,titleParams);

        FrameLayout grid=new FrameLayout(this);grid.setClipChildren(false);grid.setClipToPadding(false);drawerPage.addView(grid,new LinearLayout.LayoutParams(-1,-2));
        filter.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence value,int start,int before,int count){search=value.toString();populateDrawer(grid,filter);}
            public void afterTextChanged(Editable value){}
        });
        views.put("search",filter);filter.setNextFocusUpId(filter.getId());populateDrawer(grid,filter);
    }
    private void populateDrawer(FrameLayout grid,EditText searchView) {
        List<String> visible=new ArrayList<>(); String query=search.toLowerCase(Locale.ROOT).trim();
        for (AppCatalog.App app:apps) if (name(app.pkg).toLowerCase(Locale.ROOT).contains(query) || app.originalName.toLowerCase(Locale.ROOT).contains(query)) visible.add(app.pkg);
        appViews.clear();appRows.clear();grid.removeAllViews();
        if(drawerSpringStage!=null){drawerSpringStage.stop();drawerSpringStage=null;}
        LinearLayout groups=new LinearLayout(this);groups.setOrientation(LinearLayout.VERTICAL);
        groups.setClipChildren(false);groups.setClipToPadding(false);
        List<String> regular=new ArrayList<>(),utilities=new ArrayList<>();
        for(String pkg:visible) {
            if(store.flag("group_utilities",true)&&utility(pkg))utilities.add(pkg);else regular.add(pkg);
        }
        if(!regular.isEmpty())groups.addView(gridRows(regular,true),new LinearLayout.LayoutParams(-1,-2));
        if(!utilities.isEmpty()) {
            TextView label=text("TV utilities",26,muted());
            LinearLayout.LayoutParams heading=new LinearLayout.LayoutParams(-1,px(46));heading.topMargin=regular.isEmpty()?0:px(56);heading.bottomMargin=px(20);
            groups.addView(label,heading);groups.addView(gridRows(utilities,true),new LinearLayout.LayoutParams(-1,-2));
        }
        drawerSpringStage=new SpringStage(groups,false);
        grid.addView(drawerSpringStage.host,new FrameLayout.LayoutParams(-1,-2));
        for (int i=0;i<Math.min(4,appViews.size());i++) appViews.get(i).setNextFocusUpId(searchView.getId());
        wireNavigation();
        applyViewSoundPolicy(grid);
        if (visible.isEmpty()) {
            TextView empty=text("No apps match your search.",28,muted()); empty.setGravity(Gravity.CENTER);
            grid.removeAllViews(); grid.addView(empty,new FrameLayout.LayoutParams(-1,px(150)));
        }
    }
    private LinearLayout gridRows(List<String> packages,boolean allApps) {
        LinearLayout rows=new LinearLayout(this); rows.setOrientation(LinearLayout.VERTICAL); rows.setClipChildren(false);rows.setClipToPadding(false);rows.setPadding(0,px(16),0,px(24));
        int width=allApps?drawerWidth():innerWidth(), count=allApps?4:columns();
        int tileWidth;
        int gap;
        if(allApps){
            tileWidth=px(380);
            gap=count>1?(width-count*tileWidth)/(count-1):px(48);
        } else {
            gap=px(36);
            tileWidth=(width-gap*(count-1))/count;
        }
        int tileHeight=Math.round(tileWidth*9f/16f);
        LinearLayout row=null;
        List<View> navigationRow=null;
        for (int i=0;i<packages.size();i++) {
            if (i%count==0) {
                row=new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);row.setClipChildren(false);row.setClipToPadding(false);
                LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,tileHeight+px(58)); lp.bottomMargin=px(56); rows.addView(row,lp);
                navigationRow=new ArrayList<>();appRows.add(navigationRow);
            }
            View tile=appTile(packages.get(i),tileWidth,tileHeight,allApps);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(tileWidth,tileHeight+px(58));
            if (i%count!=count-1) lp.rightMargin=gap;
            row.addView(tile,lp); appViews.add(tile);navigationRow.add(tile);
        }
        return rows;
    }
    private Drawable glassSurface(boolean oval,boolean bannerStyle,boolean focused,int glowColor) {
        return new Drawable() {
            private final android.graphics.Paint p=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            private final android.graphics.RectF r=new android.graphics.RectF();
            @Override public void draw(android.graphics.Canvas c) {
                r.set(getBounds());
                float rad=oval?Math.min(r.width(),r.height())/2f:px(22);
                p.setStyle(android.graphics.Paint.Style.FILL);
                if(bannerStyle) {
                    p.setShader(null);
                    p.setColor(0xFF1E1A1C);
                    c.drawRoundRect(r,rad,rad,p);
                } else {
                    int tint=(glowColor&0x00FFFFFF)|(focused?0x38000000:0x14000000);
                    p.setShader(new android.graphics.LinearGradient(0,r.top,0,r.bottom,
                            focused?0x46FFFFFF:0x1EFFFFFF,tint,android.graphics.Shader.TileMode.CLAMP));
                    c.drawRoundRect(r,rad,rad,p);
                    // Top 45% specular sheen
                    android.graphics.RectF sheen=new android.graphics.RectF(r.left,r.top,r.right,r.top+r.height()*0.45f);
                    p.setShader(new android.graphics.LinearGradient(0,sheen.top,0,sheen.bottom,
                            focused?0x34FFFFFF:0x14FFFFFF,0x00FFFFFF,android.graphics.Shader.TileMode.CLAMP));
                    c.drawRoundRect(sheen,rad,rad,p);
                }
                p.setShader(null);
                if(focused || !bannerStyle) {
                    p.setStyle(android.graphics.Paint.Style.STROKE);
                    p.setStrokeWidth(Math.max(1f,px(focused?(store.flag("contrast")?3f:2.2f):1f)));
                    int rim=focused?(store.flag("contrast")?accent():0xE8FFFFFF):0x26FFFFFF;
                    p.setColor(rim);
                    float inset=p.getStrokeWidth()/2f;
                    r.inset(inset,inset);
                    c.drawRoundRect(r,rad,rad,p);
                }
            }
            @Override public void setAlpha(int a) {}
            @Override public void setColorFilter(android.graphics.ColorFilter cf) {}
            @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        };
    }
    private int extractAppColor(String pkg) {
        Integer cached=appColorCache.get(pkg);
        if(cached!=null) return cached;
        int color=0xFF56B4FF;
        try {
            Drawable d=catalog.artwork(pkg,true).drawable;
            if(d==null) d=catalog.artwork(pkg,false).drawable;
            if(d!=null) {
                android.graphics.Bitmap bmp=android.graphics.Bitmap.createBitmap(32,32,android.graphics.Bitmap.Config.ARGB_8888);
                android.graphics.Canvas cv=new android.graphics.Canvas(bmp);
                android.graphics.Rect saved=new android.graphics.Rect(d.getBounds());
                try { d.setBounds(0,0,32,32); d.draw(cv); } finally { d.setBounds(saved); }
                int[] px=new int[32*32]; bmp.getPixels(px,0,32,0,0,32,32);
                bmp.recycle();
                float[] wSum=new float[12], rSum=new float[12], gSum=new float[12], bSum=new float[12], hsv=new float[3];
                int bestBin=-1; float bestW=0f;
                for(int c:px) {
                    if((c>>>24)<96) continue;
                    Color.colorToHSV(c,hsv);
                    if(hsv[1]<0.20f || hsv[2]<0.18f) continue;
                    int bin=Math.min(11,(int)(hsv[0]/30f));
                    float w=hsv[1]*hsv[1]*(0.35f+0.65f*hsv[2]);
                    wSum[bin]+=w;
                    rSum[bin]+=((c>>16)&0xFF)*w;
                    gSum[bin]+=((c>>8)&0xFF)*w;
                    bSum[bin]+=(c&0xFF)*w;
                    if(wSum[bin]>bestW) { bestW=wSum[bin]; bestBin=bin; }
                }
                if(bestBin>=0 && bestW>0.2f) {
                    color=Color.rgb(Math.round(rSum[bestBin]/bestW),Math.round(gSum[bestBin]/bestW),Math.round(bSum[bestBin]/bestW));
                } else {
                    int seed=ThemePalette.seed(px);
                    if(seed!=0) color=seed;
                }
            }
        } catch(Exception|OutOfMemoryError ignored) {}
        appColorCache.put(pkg,color);
        return color;
    }
    private int glowIntensity() { return Math.max(10,Math.min(100,store.number("glow_intensity",80))); }
    private int glowRadiusSetting() { return Math.max(20,Math.min(96,store.number("glow_radius",56))); }
    private String glowRadiusName(int r) { return r<=34?"Subtle":r<=62?"Balanced":r<=80?"Wide":"Expansive"; }
    private String glowHueName(int hue) {
        int h=((hue%360)+360)%360;
        return h<18||h>=345?"Crimson":h<45?"Amber":h<75?"Gold":h<155?"Emerald":h<205?"Cyan":h<255?"Royal blue":h<305?"Violet":"Magenta";
    }
    private String glowColorModeName() {
        String mode=store.value("glow_color_mode","auto");
        if("accent".equals(mode)) return "Theme";
        if("white".equals(mode)) return "Pure white";
        if("custom".equals(mode)) return glowHueName(store.number("glow_custom_hue",190));
        return "App color";
    }
    private String glowSummary() {
        if(!store.flag("ambient_glow",true)) return "Off";
        return glowColorModeName()+" · "+glowIntensity()+"%";
    }
    private int resolveGlowColor(String pkg) {
        if(pkg!=null && !pkg.isEmpty()) {
            String over=store.value("glow_app."+pkg,"default");
            if("off".equals(over)) return 0;
            if("white".equals(over)) return 0xFFFFFFFF;
            if(over.startsWith("hue:")) {
                try { return Color.HSVToColor(new float[]{Integer.parseInt(over.substring(4)),0.88f,1f}); } catch(Exception ignored){}
            }
        }
        String mode=store.value("glow_color_mode","auto");
        if("accent".equals(mode)) return accent();
        if("white".equals(mode)) return 0xFFFFFFFF;
        if("custom".equals(mode)) return Color.HSVToColor(new float[]{store.number("glow_custom_hue",190),0.88f,1f});
        return pkg==null||pkg.isEmpty()?0xFF00E5FF:extractAppColor(pkg);
    }
    private String appGlowOverrideName(String pkg) {
        String over=store.value("glow_app."+pkg,"default");
        if("off".equals(over)) return "Off";
        if("white".equals(over)) return "Pure white";
        if(over.startsWith("hue:")) {
            try { return glowHueName(Integer.parseInt(over.substring(4))); } catch(Exception ignored){}
        }
        return "Default ("+glowColorModeName()+")";
    }
    private Drawable iconGlowDrawable(int color,boolean oval,float baseRadiusPx,float spreadPx,int intensityPct) {
        return new Drawable() {
            private final android.graphics.Paint paint=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            private final android.graphics.Matrix matrix=new android.graphics.Matrix();
            @Override public void draw(android.graphics.Canvas canvas) {
                if(color==0) return;
                android.graphics.Rect bounds=getBounds();
                float cx=bounds.exactCenterX();
                float cy=bounds.exactCenterY();
                float rx=bounds.width()/2f;
                float ry=bounds.height()/2f;
                if(rx<=0||ry<=0) return;
                float[] hsv=new float[3];
                Color.colorToHSV(color,hsv);
                boolean achromatic=hsv[1]<0.08f;
                hsv[1]=achromatic?0f:Math.min(1f,Math.max(0.75f,hsv[1]*1.3f));
                hsv[2]=1.0f;
                int vivid=Color.HSVToColor(hsv);
                int vr=(vivid>>16)&0xFF, vg=(vivid>>8)&0xFF, vb=vivid&0xFF;
                float k=Math.max(0.12f,Math.min(1.25f,intensityPct/80f));
                int aInner=Math.min(255,Math.round(245*k));
                int aRim=Math.min(255,Math.round(230*k));
                int aBloom=Math.min(255,Math.round(175*k));
                int aSoft=Math.min(255,Math.round(82*k));
                int cInner=Color.argb(aInner,Math.min(255,vr+65),Math.min(255,vg+65),Math.min(255,vb+65));
                int cRim=Color.argb(aRim,Math.min(255,vr+40),Math.min(255,vg+40),Math.min(255,vb+40));
                int cBloom=Color.argb(aBloom,vr,vg,vb);
                int cSoft=Color.argb(aSoft,vr,vg,vb);
                int cEdge=0x00000000;

                float denom=Math.max(1f,(baseRadiusPx+spreadPx)*1.15f);
                float rimStop=Math.max(0.25f,Math.min(0.80f,(baseRadiusPx*1.08f)/denom));
                float rem=1.0f-rimStop;

                android.graphics.RadialGradient shader=new android.graphics.RadialGradient(0f,0f,1f,
                        new int[]{cInner,cRim,cBloom,cSoft,cEdge},
                        new float[]{0.0f,rimStop,rimStop+rem*0.30f,rimStop+rem*0.66f,1.0f},
                        android.graphics.Shader.TileMode.CLAMP);
                matrix.reset();
                matrix.setScale(rx,ry);
                matrix.postTranslate(cx,cy);
                shader.setLocalMatrix(matrix);
                paint.setShader(shader);

                if(oval) {
                    canvas.drawRect(bounds,paint);
                } else {
                    android.graphics.RectF rf=new android.graphics.RectF(bounds);
                    canvas.drawRoundRect(rf,px(34),px(34),paint);
                }
            }
            @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
            @Override public void setColorFilter(android.graphics.ColorFilter cf) { paint.setColorFilter(cf); }
            @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        };
    }
    private String focusMotionStyle() { return store.value("focus_motion_style","liquid"); }
    private boolean instantFocus(){return "instant".equals(focusMotionStyle());}
    private boolean springFocusActive(){String s=focusMotionStyle();return "liquid".equals(s)||"crisp".equals(s)||"magnetic".equals(s);}
    private long focusDuration(int ms){return instantFocus()?0:Math.round(ms*100f/focusSpringSpeed());}
    private float focusLift(){return instantFocus()?0f:"soft".equals(focusMotionStyle())?-px(8):springFocusActive()?-px(4):0f;}
    private int motionStyleIndex(){String[] modes={"liquid","crisp","classic","soft","magnetic","instant"};for(int i=0;i<modes.length;i++)if(modes[i].equals(focusMotionStyle()))return i;return 0;}
    private int focusSpringSpeed() { return Math.max(50,Math.min(150,store.number("focus_spring_speed",100))); }
    private String focusGlassStyle() { return store.value("focus_glass_style","full"); }
    private int focusZoomMode() { return Math.max(0,Math.min(2,store.number("focus_zoom_mode",1))); }
    private float focusZoomScale(boolean allApps) {
        if(instantFocus())return 1f;
        int m=focusZoomMode();
        float homeScale=m==0?1.06f:m==2?1.17f:1.12f;
        if("soft".equals(focusMotionStyle()))homeScale=Math.min(homeScale,1.08f);
        return allApps?Math.min(1.09f,1f+(homeScale-1f)*0.72f):homeScale;
    }
    private String focusZoomName() { int m=focusZoomMode(); return m==0?"Subtle (1.06x)":m==2?"Bold (1.17x)":"Balanced (1.12x)"; }
    private String focusMotionStyleName() {
        String s=focusMotionStyle();
        return "soft".equals(s)?"Soft lift":"magnetic".equals(s)?"Magnetic":"instant".equals(s)?"Instant":"crisp".equals(s)?"Crisp glide":"classic".equals(s)?"Classic":"Liquid spring";
    }
    private String focusGlassStyleName() {
        String s=focusGlassStyle();
        return "rim".equals(s)?"Clean rim":"none".equals(s)?"Glow only":"Specular glass";
    }
    private String focusMotionSummary() {
        if(instantFocus()||"classic".equals(focusMotionStyle()))return focusMotionStyleName();
        return focusMotionStyleName()+" · "+focusSpringSpeed()+"%";
    }
    private static int lerpRgb(int c1,int c2,float t) {
        float u=Math.max(0f,Math.min(1f,t));
        u=1f-(1f-u)*(1f-u);
        int r1=(c1>>16)&0xFF, g1=(c1>>8)&0xFF, b1=c1&0xFF;
        int r2=(c2>>16)&0xFF, g2=(c2>>8)&0xFF, b2=c2&0xFF;
        return 0xFF000000
                |(Math.round(r1+(r2-r1)*u)<<16)
                |(Math.round(g1+(g2-g1)*u)<<8)
                |Math.round(b1+(b2-b1)*u);
    }

    private final class DynamicGlowView extends View {
        private final android.graphics.Paint paint=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Matrix matrix=new android.graphics.Matrix();
        private final android.graphics.RectF rectF=new android.graphics.RectF();
        private final float[] hsv=new float[3];
        private int color;
        private boolean oval;
        private float zoomedRadiusPx=1f;
        private int intensityPct=80;
        private android.graphics.RadialGradient cachedShader;
        private int lastW, lastH, lastColor, lastIntensity;
        private float lastZoomR;

        DynamicGlowView(Context ctx,boolean isOval) {
            super(ctx);
            this.oval=isOval;
            setClickable(false); setFocusable(false);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            setLayerType(View.LAYER_TYPE_HARDWARE,null);
        }
        void configure(int col,float zoomedRadPx,int intensity) {
            if(this.color==col && Math.abs(this.zoomedRadiusPx-zoomedRadPx)<0.5f && this.intensityPct==intensity) return;
            this.color=col; this.zoomedRadiusPx=zoomedRadPx; this.intensityPct=intensity;
            cachedShader=null;
            invalidate();
        }
        @Override protected void onDraw(android.graphics.Canvas canvas) {
            if(color==0) return;
            int w=getWidth(), h=getHeight();
            if(w<=0||h<=0) return;
            if(cachedShader==null || w!=lastW || h!=lastH || color!=lastColor || intensityPct!=lastIntensity || Math.abs(zoomedRadiusPx-lastZoomR)>0.5f) {
                lastW=w; lastH=h; lastColor=color; lastIntensity=intensityPct; lastZoomR=zoomedRadiusPx;
                Color.colorToHSV(color,hsv);
                boolean achromatic=hsv[1]<0.08f;
                hsv[1]=achromatic?0f:Math.min(1f,Math.max(0.75f,hsv[1]*1.3f));
                hsv[2]=1.0f;
                int vivid=Color.HSVToColor(hsv);
                int vr=(vivid>>16)&0xFF, vg=(vivid>>8)&0xFF, vb=vivid&0xFF;
                float k=Math.max(0.12f,Math.min(1.25f,intensityPct/80f));
                int cInner=Color.argb(Math.min(255,Math.round(245*k)),Math.min(255,vr+65),Math.min(255,vg+65),Math.min(255,vb+65));
                int cRim=Color.argb(Math.min(255,Math.round(232*k)),Math.min(255,vr+40),Math.min(255,vg+40),Math.min(255,vb+40));
                int cBloom=Color.argb(Math.min(255,Math.round(176*k)),vr,vg,vb);
                int cSoft=Color.argb(Math.min(255,Math.round(84*k)),vr,vg,vb);
                float halfMin=Math.max(1f,Math.min(w,h)/2f);
                float rimStop=Math.max(0.22f,Math.min(0.82f,(zoomedRadiusPx*0.96f)/halfMin));
                float rem=1.0f-rimStop;
                cachedShader=new android.graphics.RadialGradient(0f,0f,1f,
                        new int[]{cInner,cRim,cBloom,cSoft,0x00000000},
                        new float[]{0.0f,rimStop,rimStop+rem*0.30f,rimStop+rem*0.66f,1.0f},
                        android.graphics.Shader.TileMode.CLAMP);
                matrix.reset();
                matrix.setScale(w/2f,h/2f);
                matrix.postTranslate(w/2f,h/2f);
                cachedShader.setLocalMatrix(matrix);
                paint.setShader(cachedShader);
            }
            if(oval) {
                canvas.drawRect(0,0,w,h,paint);
            } else {
                rectF.set(0,0,w,h);
                canvas.drawRoundRect(rectF,px(34),px(34),paint);
            }
        }
    }

    private final class TravellingGlassView extends View {
        private final android.graphics.Paint fill=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint spec=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint rim=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF rect=new android.graphics.RectF();
        private final boolean oval;
        private float cornerRadius;
        private int tintColor;
        private int shaderH=-1;

        TravellingGlassView(Context ctx,boolean isOval) {
            super(ctx);
            this.oval=isOval;
            this.cornerRadius=px(24);
            rim.setStyle(android.graphics.Paint.Style.STROKE);
            setClickable(false); setFocusable(false);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            setLayerType(View.LAYER_TYPE_HARDWARE,null);
        }
        void setTintAndCorner(int color,float cornerPx) {
            if(this.tintColor==color && Math.abs(this.cornerRadius-cornerPx)<0.5f) return;
            this.tintColor=color;
            this.cornerRadius=cornerPx;
            invalidate();
        }
        @Override protected void onDraw(android.graphics.Canvas c) {
            String style=focusGlassStyle();
            if("none".equals(style)) return;
            int w=getWidth(), h=getHeight();
            if(w<=0||h<=0) return;
            float pad=px(3);
            rect.set(pad,pad,w-pad,h-pad);
            float rad=oval?Math.min(rect.width(),rect.height())/2f:cornerRadius;
            if(h!=shaderH) {
                shaderH=h;
                fill.setShader(new android.graphics.LinearGradient(0,rect.top,0,rect.bottom,
                        0x20FFFFFF,0x04FFFFFF,android.graphics.Shader.TileMode.CLAMP));
                spec.setShader(new android.graphics.LinearGradient(0,rect.top,0,rect.top+rect.height()*0.44f,
                        0x44FFFFFF,0x00FFFFFF,android.graphics.Shader.TileMode.CLAMP));
            }
            if("full".equals(style)) {
                c.drawRoundRect(rect,rad,rad,fill);
                c.save();
                c.clipRect(rect.left,rect.top,rect.right,rect.top+rect.height()*0.44f);
                c.drawRoundRect(rect,rad,rad,spec);
                c.restore();
            }
            rim.setStrokeWidth(Math.max(2f,px(store.flag("contrast")?3f:2.35f)));
            int rimCol;
            if(store.flag("contrast")) {
                rimCol=accent();
            } else if(tintColor!=0) {
                int r=(((tintColor>>16)&0xFF)+510)/3;
                int g=(((tintColor>>8)&0xFF)+510)/3;
                int b=((tintColor&0xFF)+510)/3;
                rimCol=0xEC000000|(Math.min(255,r)<<16)|(Math.min(255,g)<<8)|Math.min(255,b);
            } else {
                rimCol=0xE8FFFFFF;
            }
            rim.setColor(rimCol);
            c.drawRoundRect(rect,rad,rad,rim);
        }
    }

    private final class SpringStage implements Choreographer.FrameCallback {
        final FrameLayout host;
        final View contentChild;
        final DynamicGlowView glowView;
        final TravellingGlassView glassView;
        final boolean oval;
        View activeSlot;
        float cx, cy, vx, vy, targetX, targetY;
        int startColor, targetColor, currentColor;
        float colorT=1f;
        int glowW, glowH, glassW, glassH;
        float zoomedR=1f;
        boolean shown, running;
        long lastFrameNanos;
        int overrideSpeedPct=-1;

        SpringStage(View content,boolean isOval) {
            this.contentChild=content;
            this.oval=isOval;
            this.glowView=new DynamicGlowView(MainActivity.this,isOval);
            this.glassView=new TravellingGlassView(MainActivity.this,isOval);
            this.glowView.setAlpha(0f);
            this.glassView.setAlpha(0f);
            this.glassView.setElevation(px(24));
            this.host=new FrameLayout(MainActivity.this) {
                @Override protected void onMeasure(int widthMeasureSpec,int heightMeasureSpec) {
                    contentChild.measure(widthMeasureSpec,heightMeasureSpec);
                    int cw=contentChild.getMeasuredWidth(), ch=contentChild.getMeasuredHeight();
                    ViewGroup.LayoutParams glp=glowView.getLayoutParams();
                    glowView.measure(MeasureSpec.makeMeasureSpec(Math.max(0,glp.width),MeasureSpec.EXACTLY),
                            MeasureSpec.makeMeasureSpec(Math.max(0,glp.height),MeasureSpec.EXACTLY));
                    ViewGroup.LayoutParams slp=glassView.getLayoutParams();
                    glassView.measure(MeasureSpec.makeMeasureSpec(Math.max(0,slp.width),MeasureSpec.EXACTLY),
                            MeasureSpec.makeMeasureSpec(Math.max(0,slp.height),MeasureSpec.EXACTLY));
                    setMeasuredDimension(cw,ch);
                }
                @Override protected void onDetachedFromWindow() {
                    stop();
                    super.onDetachedFromWindow();
                }
            };
            host.setClipChildren(false);
            host.setClipToPadding(false);
            host.addView(glowView,new FrameLayout.LayoutParams(0,0));
            host.addView(contentChild,new FrameLayout.LayoutParams(-1,-2));
            host.addView(glassView,new FrameLayout.LayoutParams(0,0));
        }

        void onTileFocused(View tile,View iconSlot,int tileW,int tileH,int glowCol) {
            activeSlot=iconSlot;
            if(!springFocusActive()) { hideImmediate(); return; }
            if(iconSlot.getWidth()<=0 || host.getWidth()<=0) {
                iconSlot.post(()->{ if(!destroyed && tile.isFocused() && activeSlot==iconSlot) commitTarget(iconSlot,tileW,tileH,glowCol); });
            } else {
                commitTarget(iconSlot,tileW,tileH,glowCol);
            }
        }

        void commitTarget(View iconSlot,int tileW,int tileH,int glowCol) {
            int lx=0, ly=0;
            View cur=iconSlot;
            while(cur!=null && cur!=host) {
                lx+=cur.getLeft();
                ly+=cur.getTop();
                ViewParent vp=cur.getParent();
                cur=(vp instanceof View)?(View)vp:null;
            }
            float zoom=focusZoomScale(!oval);
            float liftY=focusLift();
            float tx=lx+tileW/2f;
            float ty=ly+tileH/2f+liftY;

            int zw=Math.round(tileW*zoom), zh=Math.round(tileH*zoom);
            int pad=px(3);
            int newGlassW=zw+pad*2, newGlassH=zh+pad*2;
            int rawRad=glowRadiusSetting();
            int spread=px(!oval?Math.round(rawRad*0.68f):rawRad);
            int newGlowW=Math.round(zw+spread*2.3f), newGlowH=Math.round(zh+spread*2.3f);
            zoomedR=Math.min(zw,zh)/2f;

            if(newGlassW!=glassW || newGlassH!=glassH || newGlowW!=glowW || newGlowH!=glowH) {
                glassW=newGlassW; glassH=newGlassH;
                glowW=newGlowW; glowH=newGlowH;
                glassView.setLayoutParams(new FrameLayout.LayoutParams(glassW,glassH));
                glowView.setLayoutParams(new FrameLayout.LayoutParams(glowW,glowH));
            }
            float cornerPx=oval?zoomedR:(px(22)*zoom);
            boolean glowEnabled=store.flag("ambient_glow",true) && !backgroundGlow() && glowCol!=0;

            targetX=tx; targetY=ty;
            if(!shown || glassView.getAlpha()<0.08f) {
                stop();
                cx=tx; cy=ty; vx=0f; vy=0f;
                currentColor=targetColor=startColor=glowCol;
                colorT=1f;
                glowView.configure(glowCol,zoomedR,glowIntensity());
                glassView.setTintAndCorner(glowCol,cornerPx);
                applyTranslations();
                glassView.animate().cancel();
                glassView.setScaleX(0.92f); glassView.setScaleY(0.92f);
                glassView.animate().alpha("none".equals(focusGlassStyle())?0f:1f).scaleX(1f).scaleY(1f).setDuration(175).start();
                glowView.animate().cancel();
                if(glowEnabled) {
                    glowView.setScaleX(0.90f); glowView.setScaleY(0.90f);
                    glowView.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200).start();
                } else {
                    glowView.setAlpha(0f);
                }
                shown=true;
            } else {
                glassView.animate().cancel();
                glassView.setScaleX(1f); glassView.setScaleY(1f);
                glassView.setAlpha("none".equals(focusGlassStyle())?0f:1f);
                glowView.animate().cancel();
                glowView.setScaleX(1f); glowView.setScaleY(1f);
                if(glowEnabled) {
                    if(glowView.getAlpha()<0.96f) glowView.animate().alpha(1f).setDuration(150).start();
                    else glowView.setAlpha(1f);
                    startColor=(currentColor!=0)?currentColor:glowCol;
                    targetColor=glowCol;
                    if(startColor==targetColor) {
                        currentColor=glowCol; colorT=1f;
                        glowView.configure(glowCol,zoomedR,glowIntensity());
                        glassView.setTintAndCorner(glowCol,cornerPx);
                    } else {
                        colorT=0f;
                    }
                } else {
                    glowView.animate().alpha(0f).setDuration(140).start();
                    targetColor=0; colorT=1f;
                    glassView.setTintAndCorner(0,cornerPx);
                }
                startSpring();
            }
        }

        void onTileUnfocused(View tile,View iconSlot) {
            host.post(()->{
                if(destroyed) return;
                if(activeSlot==iconSlot && !tile.isFocused()) {
                    activeSlot=null;
                    glassView.animate().alpha(0f).scaleX(0.95f).scaleY(0.95f).setDuration(140)
                            .withEndAction(()->{ if(activeSlot==null) shown=false; }).start();
                    glowView.animate().alpha(0f).scaleX(0.92f).scaleY(0.92f).setDuration(140).start();
                }
            });
        }

        void hideImmediate() {
            stop();
            shown=false; activeSlot=null;
            glassView.animate().cancel(); glowView.animate().cancel();
            glassView.setAlpha(0f); glowView.setAlpha(0f);
        }

        private void applyTranslations() {
            glowView.setTranslationX(cx-glowW/2f);
            glowView.setTranslationY(cy-glowH/2f);
            glassView.setTranslationX(cx-glassW/2f);
            glassView.setTranslationY(cy-glassH/2f);
            if("magnetic".equals(focusMotionStyle())){
                float stretch=Math.min(.16f,Math.abs(targetX-cx)/Math.max(1,glassW)*.14f);
                glassView.setScaleX(1f+stretch);glassView.setScaleY(1f-stretch*.3f);
            }
        }

        private void startSpring() {
            if(!running) {
                running=true;
                lastFrameNanos=0L;
                Choreographer.getInstance().postFrameCallback(this);
            }
        }

        void stop() {
            if(running) {
                running=false;
                Choreographer.getInstance().removeFrameCallback(this);
            }
        }

        @Override public void doFrame(long frameTimeNanos) {
            if(!running || destroyed) { running=false; return; }
            float dt=lastFrameNanos==0L?0.016f:Math.min(0.034f,Math.max(0.001f,(frameTimeNanos-lastFrameNanos)*1e-9f));
            lastFrameNanos=frameTimeNanos;

            boolean crisp="crisp".equals(focusMotionStyle());
            int speedPct=overrideSpeedPct>0?overrideSpeedPct:focusSpringSpeed();
            float speedFactor=(float)Math.pow(speedPct/100.0,1.45);
            float stiffness=("magnetic".equals(focusMotionStyle())?420f:crisp?490f:340f)*speedFactor;
            float zeta="magnetic".equals(focusMotionStyle())?.88f:crisp?1.0f:.76f;
            float damping=2f*zeta*(float)Math.sqrt(stiffness);

            int steps=Math.max(1,(int)Math.ceil(dt/0.008f));
            float h=dt/steps;
            for(int i=0;i<steps;i++) {
                float ax=-stiffness*(cx-targetX)-damping*vx;
                float ay=-stiffness*(cy-targetY)-damping*vy;
                vx+=ax*h; vy+=ay*h;
                cx+=vx*h; cy+=vy*h;
            }
            applyTranslations();

            if(colorT<1f && targetColor!=0) {
                colorT=Math.min(1f,colorT+dt/0.18f);
                currentColor=lerpRgb(startColor,targetColor,colorT);
                glowView.configure(currentColor,zoomedR,glowIntensity());
                float zoom=focusZoomScale(!oval);
                glassView.setTintAndCorner(currentColor,oval?zoomedR:(px(22)*zoom));
            }

            boolean posSettled=Math.abs(cx-targetX)<0.35f && Math.abs(cy-targetY)<0.35f
                    && Math.abs(vx)<3f && Math.abs(vy)<3f;
            if(posSettled && colorT>=1f) {
                cx=targetX; cy=targetY; vx=0f; vy=0f;
                applyTranslations();
                running=false;
            } else {
                Choreographer.getInstance().postFrameCallback(this);
            }
        }
    }

    private View appTile(String pkg,int width,int height,boolean allApps) {
        return appTile(pkg,width,height,allApps,false);
    }
    private View appTile(String pkg,int width,int height,boolean allApps,boolean recent) {
        LinearLayout tile=new LinearLayout(this); tile.setOrientation(LinearLayout.VERTICAL);
        tile.setSoundEffectsEnabled(!muteSystem());
        tile.setId(View.generateViewId()); tile.setTag(pkg); tile.setFocusable(true); tile.setClickable(true);
        tile.setFocusableInTouchMode(true);
        tile.setClipChildren(false);tile.setClipToPadding(false);
        final int appColor=extractAppColor(pkg);
        final int glowColor=resolveGlowColor(pkg);
        final int surfaceTint=glowColor!=0?glowColor:appColor;

        FrameLayout iconSlot=new FrameLayout(this);
        iconSlot.setClipChildren(false);iconSlot.setClipToPadding(false);

        int rawRad=glowRadiusSetting();
        int spread=px(allApps?Math.round(rawRad*0.68f):rawRad);
        float baseR=Math.min(width,height)/2f;
        View glow=new View(this);
        glow.setBackground(iconGlowDrawable(glowColor,!allApps,baseR,spread,glowIntensity()));
        glow.setAlpha(0f);
        glow.setVisibility(View.INVISIBLE);
        FrameLayout.LayoutParams glowLp=new FrameLayout.LayoutParams(width+spread*2,height+spread*2);
        glowLp.leftMargin=-spread; glowLp.topMargin=-spread;
        iconSlot.addView(glow,glowLp);

        FrameLayout picture=new FrameLayout(this) {
            private final android.graphics.Paint halo=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            @Override protected void dispatchDraw(android.graphics.Canvas canvas) {
                super.dispatchDraw(canvas);
                if(tile.isFocused() && !springFocusActive()) {
                    float rad=!allApps?Math.min(getWidth(),getHeight())/2f:px(22);
                    halo.setStyle(android.graphics.Paint.Style.STROKE);
                    halo.setStrokeWidth(Math.max(2f,px(2.4f)));
                    halo.setColor(store.flag("contrast")?accent():0xE8FFFFFF);
                    float inset=halo.getStrokeWidth()/2f;
                    android.graphics.RectF rect=new android.graphics.RectF(inset,inset,getWidth()-inset,getHeight()-inset);
                    canvas.drawRoundRect(rect,rad,rad,halo);
                }
            }
        };
        picture.setClipToOutline(true);
        picture.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View view,android.graphics.Outline outline) {
                if(!allApps) outline.setOval(0,0,view.getWidth(),view.getHeight());
                else outline.setRoundRect(0,0,view.getWidth(),view.getHeight(),px(22));
            }
        });
        ImageView image=new ImageView(this); image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        String artwork=store.value("art."+pkg,"");
        AppCatalog.Artwork appArt=catalog.artwork(pkg,recent?false:allApps?store.flag("icon."+pkg):!store.flag("home_banner."+pkg));
        final boolean bannerStyle=!artwork.isEmpty()||appArt.banner;
        picture.setBackground(glassSurface(!allApps,bannerStyle,false,surfaceTint));
        if(!allApps) {
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            if(artwork.isEmpty()) {
                image.setImageDrawable(homeIconDrawable(appArt.drawable));
            } else images.show(image,artwork,width,height);
            picture.addView(image,new FrameLayout.LayoutParams(-1,-1));
        } else if(bannerStyle) {
            if(!artwork.isEmpty())images.show(image,artwork,width,height);
            else if(appArt.drawable!=null)image.setImageDrawable(fitDrawable(appArt.drawable,width,height));
            picture.addView(image,new FrameLayout.LayoutParams(-1,-1));
        } else if(recent) {
            image.setImageDrawable(appArt.drawable);image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int iconSize=Math.min(px(80),Math.round(height*0.65f));
            picture.addView(image,new FrameLayout.LayoutParams(iconSize,iconSize,Gravity.CENTER));
        } else {
            LinearLayout plain=new LinearLayout(this);plain.setOrientation(LinearLayout.VERTICAL);plain.setGravity(Gravity.CENTER);
            int iconSize=allApps?px(96):px(64);
            if(appArt.drawable!=null)image.setImageDrawable(fitDrawable(appArt.drawable,iconSize,iconSize));
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);plain.addView(image,new LinearLayout.LayoutParams(iconSize,iconSize));
            TextView inside=text(name(pkg),allApps?26:24,foreground());inside.setGravity(Gravity.CENTER);inside.setSingleLine(true);inside.setEllipsize(android.text.TextUtils.TruncateAt.END);inside.setPadding(px(16),0,px(16),0);
            LinearLayout.LayoutParams labelParams=new LinearLayout.LayoutParams(-1,px(44));labelParams.topMargin=px(allApps?14:8);plain.addView(inside,labelParams);
            picture.addView(plain,new FrameLayout.LayoutParams(-1,-1));
        }
        iconSlot.addView(picture,new FrameLayout.LayoutParams(-1,-1));
        tile.addView(iconSlot,new LinearLayout.LayoutParams(-1,height));
        String label=name(pkg);
        TextView caption=text(label,24,foreground()); caption.setSingleLine(true);
        caption.setEllipsize(android.text.TextUtils.TruncateAt.END); caption.setGravity(Gravity.CENTER);
        if(!recent)tile.addView(caption,new LinearLayout.LayoutParams(-1,px(48)));
        final boolean showCaptionRole=!recent&&(!allApps||bannerStyle);
        final boolean alwaysLabels=store.flag("labels",false);
        final boolean slideLabels=store.flag("focus_slide_labels",true);
        if(!showCaptionRole) {
            caption.setVisibility(View.INVISIBLE);
        } else if(alwaysLabels) {
            caption.setVisibility(View.VISIBLE);
            caption.setAlpha(0.84f);
        } else if(slideLabels) {
            caption.setVisibility(View.VISIBLE);
            caption.setAlpha(0f);
            caption.setTranslationY(-px(5));
        } else {
            caption.setVisibility(View.INVISIBLE);
        }
        tile.setContentDescription(name(pkg)+(recent?" recent app":allApps?"":" home app"));
        tile.setOnFocusChangeListener((v,has)->{
            boolean useSpring=springFocusActive();
            float zoom=focusZoomScale(allApps);
            boolean liquid="liquid".equals(focusMotionStyle());
            android.view.animation.Interpolator interp=useSpring&&liquid?liquidOvershoot:crispDecel;
            int dur=has?(useSpring?220:140):(useSpring?170:140);

            tile.animate().translationZ(has?px(16):0).setDuration(focusDuration(dur)).start();
            picture.setBackground(glassSurface(!allApps,bannerStyle,has&&!useSpring,surfaceTint));
            picture.invalidate();
            picture.animate().scaleX(has?zoom:1f).scaleY(has?zoom:1f)
                    .translationY(has?focusLift():0f)
                    .translationZ(has?px(12):0)
                    .setInterpolator(interp).setDuration(focusDuration(dur)).start();

            if(!instantFocus()&&store.flag("focus_parallax",true) && (!allApps || bannerStyle)) {
                image.animate().scaleX(has&&!instantFocus()?1.045f:1f).scaleY(has&&!instantFocus()?1.045f:1f)
                        .setInterpolator(crispDecel).setDuration(focusDuration(has?280:180)).start();
            } else {
                image.setScaleX(1f); image.setScaleY(1f);
            }

            if(showCaptionRole) {
                caption.animate().cancel();
                if(alwaysLabels) {
                    caption.setVisibility(View.VISIBLE);
                    caption.animate().alpha(has?1f:0.84f).translationY(has&&useSpring?px(3):0f).setDuration(focusDuration(180)).start();
                } else if(slideLabels) {
                    caption.setVisibility(View.VISIBLE);
                    caption.animate().alpha(has?1f:0f).translationY(instantFocus()?0f:has?px(3):-px(5))
                            .setInterpolator(crispDecel).setDuration(focusDuration(has?210:150)).start();
                } else {
                    caption.setAlpha(1f); caption.setTranslationY(0f);
                    caption.setVisibility(has?View.VISIBLE:View.INVISIBLE);
                }
            }

            SpringStage stage=recent?recentSpringStage:allApps?drawerSpringStage:homeSpringStage;
            if(useSpring && stage!=null) {
                glow.animate().cancel();
                glow.setAlpha(0f);
                glow.setVisibility(View.INVISIBLE);
                if(has) stage.onTileFocused(tile,iconSlot,width,height,glowColor);
                else stage.onTileUnfocused(tile,iconSlot);
            } else {
                if(stage!=null) stage.hideImmediate();
                boolean showGlow=has&&store.flag("ambient_glow",true)&&!backgroundGlow()&&glowColor!=0;
                if(showGlow) {
                    glow.setVisibility(View.VISIBLE);
                    glow.animate().alpha(1f).scaleX(1.15f).scaleY(1.15f).setDuration(focusDuration(180)).start();
                } else {
                    glow.animate().alpha(0f).scaleX(0.92f).scaleY(0.92f).setDuration(focusDuration(130))
                        .withEndAction(()->{if(!tile.isFocused())glow.setVisibility(View.INVISIBLE);}).start();
                }
            }
            if (has) {
                updateAmbient(pkg);
                if(drawer&&allApps)updateDrawerFocusTitle(name(pkg));
                if(!recent)lastFocus=pkg;
                if (!drawer) {heroWantsFocus=false;resizeHero(false);if(!recent)focusedFavorite=tile;wireNavigation();revealHorizontalFocus(tile,recent?px(40):0);}
                else if(allApps && drawerScroll!=null) drawerScroll.post(()->centerDrawerChild(tile));
            }
        });
        tile.setOnClickListener(v->launch(pkg));
        tile.setOnLongClickListener(v->{ appMenu(pkg); return true; });
        final boolean[] held={false};
        tile.setOnKeyListener((v,code,event)->{
            boolean down=event.getAction()==KeyEvent.ACTION_DOWN;
            if(down && code==KeyEvent.KEYCODE_DPAD_UP && !drawer && !recent && (heroPanel==null || heroPanel.getVisibility()!=View.VISIBLE)) {
                if(glanceWidget!=null && glanceWidget.getVisibility()==View.VISIBLE) {
                    glanceWidget.requestFocus();
                    return true;
                }
                if(!topPillViews.isEmpty()) {
                    topPillViews.get(0).requestFocus();
                    return true;
                }
            }
            boolean ok=code==KeyEvent.KEYCODE_DPAD_CENTER||code==KeyEvent.KEYCODE_ENTER;
            if (!moving.isEmpty() && moving.equals(pkg)) {
                if (down && event.getRepeatCount()==0) {
                    int delta=code==KeyEvent.KEYCODE_DPAD_LEFT?-1:code==KeyEvent.KEYCODE_DPAD_RIGHT?1:0;
                    if (delta!=0) { moveFavorite(pkg,delta); return true; }
                }
                if (ok) { if (!down) { moving=""; render(); } return true; }
            }
            if (code==KeyEvent.KEYCODE_MENU) { if (!down) appMenu(pkg); return true; }
            if (!ok) return false;
            if (down) {
                if (event.getRepeatCount()==0) held[0]=false;
                else if (!held[0] && (event.isLongPress()||event.getEventTime()-event.getDownTime()>=ViewConfiguration.getLongPressTimeout())) {
                    held[0]=true; suppressCenterUp=true; appMenu(pkg);
                }
            } else if (!held[0]) launch(pkg);
            return true;
        });
        if(!recent)views.put(pkg,tile); return tile;
    }
    private final class DockSpringLens implements Choreographer.FrameCallback {
        final FrameLayout shell;
        final View lens;
        View activeItem;
        float x, vx, targetX, w, vw, targetW;
        boolean shown, running;
        long lastNanos;

        DockSpringLens(FrameLayout dockShell,View lensView) {
            this.shell=dockShell;
            this.lens=lensView;
        }
        boolean enabled() { return store.flag("dock_spring_lens",true) && springFocusActive(); }
        void onItemFocused(View item) {
            activeItem=item;
            if(!enabled()) { hideImmediate(); return; }
            if(item.getWidth()<=0 || shell.getWidth()<=0) {
                item.post(()->{ if(!destroyed && item.isFocused() && activeItem==item) commit(item); });
            } else commit(item);
        }
        private void commit(View item) {
            int lx=0, ly=0;
            View cur=item;
            while(cur!=null && cur!=shell) {
                lx+=cur.getLeft(); ly+=cur.getTop();
                ViewParent vp=cur.getParent();
                cur=(vp instanceof View)?(View)vp:null;
            }
            int ih=item.getHeight();
            if(lens.getHeight()!=ih) {
                FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(Math.max(1,item.getWidth()),ih);
                lp.topMargin=ly;
                lens.setLayoutParams(lp);
            }
            targetX=lx;
            targetW=item.getWidth();
            lens.setBackground(glassControlDrawable(null,true,18f,accent(),false,false));
            if(!shown || lens.getAlpha()<0.08f) {
                stop();
                x=targetX; w=targetW; vx=0f; vw=0f;
                apply();
                lens.animate().cancel();
                lens.setScaleX(0.95f); lens.setScaleY(0.95f);
                lens.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(165).start();
                shown=true;
            } else {
                lens.animate().cancel();
                lens.setAlpha(1f); lens.setScaleX(1f); lens.setScaleY(1f);
                if(!running) { running=true; lastNanos=0L; Choreographer.getInstance().postFrameCallback(this); }
            }
        }
        void onItemUnfocused(View item) {
            shell.post(()->{
                if(destroyed) return;
                if(activeItem==item && !item.isFocused()) {
                    activeItem=null;
                    lens.animate().alpha(0f).scaleX(0.95f).scaleY(0.95f).setDuration(140)
                            .withEndAction(()->{ if(activeItem==null) shown=false; }).start();
                }
            });
        }
        void hideImmediate() {
            stop(); shown=false; activeItem=null;
            lens.animate().cancel(); lens.setAlpha(0f);
        }
        private void apply() {
            int curW=Math.max(1,Math.round(w));
            if(lens.getLayoutParams()!=null && lens.getLayoutParams().width!=curW) {
                lens.getLayoutParams().width=curW;
                lens.requestLayout();
            }
            lens.setTranslationX(x);
            if("magnetic".equals(focusMotionStyle())){
                float stretch=Math.min(.14f,Math.abs(targetX-x)/Math.max(1,w)*.12f);
                lens.setScaleX(1f+stretch);lens.setScaleY(1f-stretch*.25f);
            }
        }
        void stop() {
            if(running) { running=false; Choreographer.getInstance().removeFrameCallback(this); }
        }
        @Override public void doFrame(long nanos) {
            if(!running || destroyed) { running=false; return; }
            float dt=lastNanos==0L?0.016f:Math.min(0.034f,Math.max(0.001f,(nanos-lastNanos)*1e-9f));
            lastNanos=nanos;
            boolean crisp="crisp".equals(focusMotionStyle());
            float speedFactor=(float)Math.pow(focusSpringSpeed()/100.0,1.45);
            float k=("magnetic".equals(focusMotionStyle())?420f:crisp?490f:340f)*speedFactor;
            float c=2f*("magnetic".equals(focusMotionStyle())?.88f:crisp?1.0f:.78f)*(float)Math.sqrt(k);
            int steps=Math.max(1,(int)Math.ceil(dt/0.008f));
            float h=dt/steps;
            for(int i=0;i<steps;i++) {
                float ax=-k*(x-targetX)-c*vx;
                float aw=-k*(w-targetW)-c*vw;
                vx+=ax*h; vw+=aw*h;
                x+=vx*h; w+=vw*h;
            }
            apply();
            if(Math.abs(x-targetX)<0.4f && Math.abs(w-targetW)<0.5f && Math.abs(vx)<3f && Math.abs(vw)<3f) {
                x=targetX; w=targetW; vx=0f; vw=0f; apply(); running=false;
            } else {
                Choreographer.getInstance().postFrameCallback(this);
            }
        }
    }

    private void buildDock() {
        FrameLayout shell=new FrameLayout(this);
        dockBar=shell;
        shell.setClipChildren(false); shell.setClipToPadding(false);
        shell.setBackground(glassPanelDrawable(shell,26f,false));
        View lens=new View(this);
        lens.setAlpha(0f);
        lens.setClickable(false); lens.setFocusable(false);
        lens.setLayerType(View.LAYER_TYPE_HARDWARE,null);
        shell.addView(lens,new FrameLayout.LayoutParams(0,0));
        dockSpringLens=new DockSpringLens(shell,lens);

        LinearLayout dock=new LinearLayout(this); dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setClipChildren(false); dock.setClipToPadding(false);
        dock.setPadding(px(12),px(12),px(12),px(12));
        shell.addView(dock,new FrameLayout.LayoutParams(-1,-1));

        LinearLayout.LayoutParams place=new LinearLayout.LayoutParams(-1,px(132));place.topMargin=px(64);place.bottomMargin=px(40);
        (drawer?drawerPage:homePage).addView(shell,place);
        List<TvInputInfo> inputs=hdmiInputs();
        addDock(dock,ALL,drawer?"Home":"All Apps",null,()->{ hideKeyboard(); drawer=!drawer; search=""; lastFocus=""; render(); });
        for (TvInputInfo input:inputs) addDock(dock,input.getId(),inputName(input),LineIcon.HDMI,()->openInput(input.getId()));
        addDock(dock,SETTINGS,"Settings",LineIcon.SETTINGS,this::settingsMenu);
        for (View view:dockViews) {
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-1,1); lp.leftMargin=px(5); lp.rightMargin=px(5); dock.addView(view,lp);
        }
    }
    private void addDock(LinearLayout parent,String key,String label,int[] symbol,Runnable action) {
        LinearLayout tile=new LinearLayout(this); tile.setOrientation(LinearLayout.VERTICAL); tile.setGravity(Gravity.CENTER);
        tile.setSoundEffectsEnabled(!muteSystem());
        tile.setId(View.generateViewId()); tile.setFocusable(true); tile.setClickable(true); tile.setContentDescription(label);
        tile.setFocusableInTouchMode(true);
        final ImageView iconView=symbol!=null?new ImageView(this):null;
        if (iconView!=null) { iconView.setImageDrawable(new LineIcon(symbol,0xE8FFFFFF)); tile.addView(iconView,new LinearLayout.LayoutParams(px(30),px(30))); }
        TextView text=text(label,24,0xECFFFFFF); text.setSingleLine(true); text.setEllipsize(android.text.TextUtils.TruncateAt.END); text.setGravity(Gravity.CENTER);
        tile.addView(text,new LinearLayout.LayoutParams(-1,px(44)));
        tile.setBackground(glassControlDrawable(tile,false,18f,accent(),false,true));
        tile.setOnFocusChangeListener((v,has)->{
            boolean useDockSpring=dockSpringLens!=null && dockSpringLens.enabled();
            tile.setBackground(glassControlDrawable(tile,has && !useDockSpring,18f,accent(),false,true));
            if(iconView!=null) iconView.setImageDrawable(new LineIcon(symbol,has?accent():0xE8FFFFFF));
            text.setTextColor(has?foreground():0xECFFFFFF);
            if(has) text.setTypeface(medium); else text.setTypeface(regular);
            tile.animate().scaleX(has&&!instantFocus()?1.045f:1f).scaleY(has&&!instantFocus()?1.045f:1f).setDuration(focusDuration(150)).start();
            if(dockSpringLens!=null) {
                if(has && useDockSpring) dockSpringLens.onItemFocused(tile);
                else dockSpringLens.onItemUnfocused(tile);
            }
            if(has) {
                lastFocus=key;
                final ScrollView page=drawer?drawerScroll:homeScroll;
                final LinearLayout pageContent=drawer?drawerPage:homePage;
                if(page!=null && pageContent!=null) {
                    page.post(()->{
                        if(page==(drawer?drawerScroll:homeScroll) && v.hasFocus())
                            page.smoothScrollTo(0,Math.max(0,pageContent.getHeight()-page.getHeight()));
                    });
                }
            }
        });
        tile.setOnClickListener(v->{
            if(sfx!=null && !SETTINGS.equals(key)) sfx.playSelect(v,screenWidth);
            action.run();
        }); views.put(key,tile); dockViews.add(tile);
    }
    private void wireNavigation() {
        List<List<View>> rows=new ArrayList<>();contentViews.clear();
        for(List<View> row:contentRows)if(!row.isEmpty()){rows.add(row);contentViews.addAll(row);}
        if(glanceWidget!=null) {
            glanceWidget.setNextFocusUpId(glanceWidget.getId());
            if(!topPillViews.isEmpty()) glanceWidget.setNextFocusDownId(topPillViews.get(0).getId());
            else if(focusedFavorite!=null) glanceWidget.setNextFocusDownId(focusedFavorite.getId());
            else if(!appViews.isEmpty()) glanceWidget.setNextFocusDownId(appViews.get(0).getId());
            else if(!dockViews.isEmpty()) glanceWidget.setNextFocusDownId(dockViews.get(0).getId());
        }
        for(int i=0;i<topPillViews.size();i++) {
            View pill=topPillViews.get(i);
            pill.setNextFocusLeftId(topPillViews.get(Math.max(0,i-1)).getId());
            pill.setNextFocusRightId(topPillViews.get(Math.min(topPillViews.size()-1,i+1)).getId());
            pill.setNextFocusUpId(pill.getId());
            if(drawer && views.get("search")!=null) pill.setNextFocusDownId(views.get("search").getId());
            else if(focusedFavorite!=null) pill.setNextFocusDownId(focusedFavorite.getId());
            else if(!appViews.isEmpty()) pill.setNextFocusDownId(appViews.get(0).getId());
            else if(!dockViews.isEmpty()) pill.setNextFocusDownId(dockViews.get(0).getId());
        }
        if(drawer && views.get("search")!=null && !topPillViews.isEmpty()) {
            views.get("search").setNextFocusUpId(topPillViews.get(topPillViews.size()-1).getId());
        }
        for(int r=0;r<appRows.size();r++) {
            List<View> row=appRows.get(r);
            for(int c=0;c<row.size();c++) {
                View v=row.get(c);
                v.setNextFocusLeftId(row.get(Math.max(0,c-1)).getId());
                v.setNextFocusRightId(row.get(Math.min(row.size()-1,c+1)).getId());
                if(r==0&&!drawer) {
                    if(heroPrimary!=null && heroPanel!=null && heroPanel.getVisibility()==View.VISIBLE) v.setNextFocusUpId(heroPrimary.getId());
                    else if(glanceWidget!=null && glanceWidget.getVisibility()==View.VISIBLE) v.setNextFocusUpId(glanceWidget.getId());
                    else if(!topPillViews.isEmpty()) v.setNextFocusUpId(topPillViews.get(0).getId());
                }
                if(r==0&&drawer&&views.get("search")!=null)v.setNextFocusUpId(views.get("search").getId());
                if(r>0) {
                    List<View> above=appRows.get(r-1);v.setNextFocusUpId(above.get(Math.min(c,above.size()-1)).getId());
                }
                if(r+1<appRows.size()) {
                    List<View> below=appRows.get(r+1);v.setNextFocusDownId(below.get(Math.min(c,below.size()-1)).getId());
                } else if(!contentViews.isEmpty())v.setNextFocusDownId(contentViews.get(0).getId());
                else if(!dockViews.isEmpty())v.setNextFocusDownId(dockViews.get(Math.min(c,dockViews.size()-1)).getId());
            }
        }
        for (int i=0;i<dockViews.size();i++) {
            View v=dockViews.get(i); v.setNextFocusLeftId(dockViews.get(Math.max(0,i-1)).getId());
            v.setNextFocusRightId(dockViews.get(Math.min(dockViews.size()-1,i+1)).getId()); v.setNextFocusDownId(v.getId());
            if (!rows.isEmpty()) {
                List<View> last=rows.get(rows.size()-1);v.setNextFocusUpId(last.get(Math.min(i,last.size()-1)).getId());
            }
            else if (!appRows.isEmpty()) {
                List<View> last=appRows.get(appRows.size()-1);
                v.setNextFocusUpId((focusedFavorite!=null?focusedFavorite:last.get(Math.min(i,last.size()-1))).getId());
            }
        }
        for(int r=0;r<rows.size();r++)for(int c=0;c<rows.get(r).size();c++) {
            List<View> row=rows.get(r);View v=row.get(c);
            v.setNextFocusLeftId(row.get(Math.max(0,c-1)).getId());v.setNextFocusRightId(row.get(Math.min(c+1,row.size()-1)).getId());
            if(r>0) {
                List<View> above=rows.get(r-1);v.setNextFocusUpId(above.get(Math.min(c,above.size()-1)).getId());
            } else if(focusedFavorite!=null)v.setNextFocusUpId(focusedFavorite.getId());
            else if(!appViews.isEmpty())v.setNextFocusUpId(appViews.get(0).getId());
            if(r+1<rows.size()) {
                List<View> below=rows.get(r+1);v.setNextFocusDownId(below.get(Math.min(c,below.size()-1)).getId());
            } else if(!dockViews.isEmpty())v.setNextFocusDownId(dockViews.get(Math.min(c,dockViews.size()-1)).getId());
        }
    }
    private void updateRecommendationRows() {
        if (drawer || contentRail==null || destroyed) return;
        contentRail.removeAllViews();contentRows.clear();contentViews.clear();
        for(SpringStage stage:recommendationSpringStages) if(stage!=null) stage.stop();
        recommendationSpringStages.clear();
        final int current=serial;
        final int size=recommendationSize(),cardWidth=recommendationWidth(size),artHeight=cardWidth*9/16;
        List<String> sources=new ArrayList<>(favorites());
        for(AppCatalog.App app:apps)if(!sources.contains(app.pkg))sources.add(app.pkg);
        if(store.flag("recommendations_master"))for(String pkg:sources) {
            if(!store.recommendations(pkg))continue;
            LinearLayout section=new LinearLayout(this);section.setOrientation(LinearLayout.VERTICAL);
            section.setClipChildren(false);section.setClipToPadding(false);section.setVisibility(View.GONE);
            LinearLayout.LayoutParams sectionParams=new LinearLayout.LayoutParams(-1,-2);
            sectionParams.topMargin=px(28);sectionParams.bottomMargin=px(44);
            contentRail.addView(section,sectionParams);
            List<View> rowViews=new ArrayList<>();contentRows.add(rowViews);
            recommendations.load(pkg,feed->{
                if(destroyed||drawer||current!=serial||section.getParent()!=contentRail)return;
                if(!HomePolicy.recommendations(store.flag("recommendations_master"),store.recommendations(pkg),!feed.items.isEmpty()))return;
                TextView label=text(name(pkg),34,foreground());label.setTypeface(medium);label.setPadding(px(96),0,px(96),0);
                LinearLayout.LayoutParams labelParams=new LinearLayout.LayoutParams(-1,px(52));
                labelParams.bottomMargin=px(16);section.addView(label,labelParams);
                HorizontalScrollView scroll=new HorizontalScrollView(this) {
                    @Override public void playSoundEffect(int soundConstant) {
                        if(muteSystem()) return;
                        super.playSoundEffect(soundConstant);
                    }
                };
                scroll.setSoundEffectsEnabled(!muteSystem());
                scroll.setHorizontalScrollBarEnabled(false);
                scroll.setClipChildren(false);scroll.setClipToPadding(false);scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
                LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);row.setClipToPadding(false);
                int edgePadding=px(96);
                int vertPad=Math.max(px(32),px(glowRadiusSetting()+14));
                row.setPadding(edgePadding,vertPad,edgePadding,vertPad);

                final SpringStage rowStage=new SpringStage(row,false);
                recommendationSpringStages.add(rowStage);
                final int appColor=extractAppColor(pkg);
                final int glowColor=resolveGlowColor(pkg);
                final int surfaceTint=glowColor!=0?glowColor:appColor;

                for (RecommendationRepository.Item item:feed.items) {
                    LinearLayout card=new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setId(View.generateViewId()); card.setTag(pkg); card.setFocusable(true); card.setClickable(true);
                    card.setFocusableInTouchMode(true);card.setClipChildren(false);card.setClipToPadding(false);
                    card.setSoundEffectsEnabled(!muteSystem());

                    FrameLayout iconSlot=new FrameLayout(this);
                    iconSlot.setClipChildren(false);iconSlot.setClipToPadding(false);

                    int rawRad=glowRadiusSetting();
                    int spread=px(Math.round(rawRad*0.68f));
                    View glow=new View(this);
                    glow.setBackground(iconGlowDrawable(glowColor,false,Math.min(cardWidth,artHeight)/2f,spread,glowIntensity()));
                    glow.setAlpha(0f);
                    glow.setVisibility(View.INVISIBLE);
                    FrameLayout.LayoutParams glowLp=new FrameLayout.LayoutParams(-1,-1);
                    glowLp.setMargins(-spread,-spread,-spread,-spread);
                    iconSlot.addView(glow,glowLp);

                    FrameLayout picture=new FrameLayout(this) {
                        private final android.graphics.Paint halo=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                        @Override protected void dispatchDraw(android.graphics.Canvas canvas) {
                            super.dispatchDraw(canvas);
                            if(card.isFocused() && !springFocusActive()) {
                                float rad=px(22);
                                halo.setStyle(android.graphics.Paint.Style.STROKE);
                                halo.setStrokeWidth(Math.max(2f,px(2.4f)));
                                halo.setColor(store.flag("contrast")?accent():0xE8FFFFFF);
                                float inset=halo.getStrokeWidth()/2f;
                                android.graphics.RectF rect=new android.graphics.RectF(inset,inset,getWidth()-inset,getHeight()-inset);
                                canvas.drawRoundRect(rect,rad,rad,halo);
                            }
                        }
                    };
                    picture.setClipToOutline(true);
                    picture.setOutlineProvider(new android.view.ViewOutlineProvider() {
                        @Override public void getOutline(View view,android.graphics.Outline outline) {
                            outline.setRoundRect(0,0,view.getWidth(),view.getHeight(),px(22));
                        }
                    });
                    picture.setBackground(glassSurface(false,true,false,surfaceTint));
                    iconSlot.addView(picture,new FrameLayout.LayoutParams(-1,-1));
                    card.addView(iconSlot,new LinearLayout.LayoutParams(-1,px(artHeight)));

                    final int[] naturalWidth={px(cardWidth)};
                    final ImageView[] imgRef=new ImageView[1];
                    if (!item.artwork.isEmpty()) {
                        ImageView image=new ImageView(this) {
                            @Override public void setImageBitmap(android.graphics.Bitmap bitmap) {
                                super.setImageBitmap(bitmap);
                                if(bitmap==null||destroyed||current!=serial||drawer)return;
                                naturalWidth[0]=artworkWidth(bitmap.getWidth(),bitmap.getHeight(),px(artHeight),screenWidth-px(96));
                                android.view.ViewGroup.LayoutParams params=card.getLayoutParams();
                                if(params!=null && params.width!=naturalWidth[0]) {
                                    params.width=naturalWidth[0];
                                    card.setLayoutParams(params);
                                }
                            }
                        };
                        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                        imgRef[0]=image;
                        picture.addView(image,new FrameLayout.LayoutParams(-1,-1));
                        images.show(image,item.artwork,screenWidth-px(96),px(artHeight));
                    }
                    TextView title=text(item.title,recommendationTextSize(size),foreground()); title.setMaxLines(1); title.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    title.setGravity(Gravity.CENTER_VERTICAL);title.setPadding(px(4),px(4),px(4),0);
                    card.addView(title,new LinearLayout.LayoutParams(-1,px(recommendationTitleHeight(size))));
                    card.setContentDescription(item.title+" · "+name(pkg)); card.setOnClickListener(v->openTitle(item));

                    card.setOnFocusChangeListener((v,has)->{
                        boolean useSpring=springFocusActive();
                        float zoom=focusZoomScale(true);
                        boolean liquid="liquid".equals(focusMotionStyle());
                        android.view.animation.Interpolator interp=useSpring&&liquid?liquidOvershoot:crispDecel;
                        int dur=has?(useSpring?220:140):(useSpring?170:140);

                        card.animate().translationZ(has?px(16):0).setDuration(focusDuration(dur)).start();
                        picture.setBackground(glassSurface(false,true,has&&!useSpring,surfaceTint));
                        picture.invalidate();
                        picture.animate().scaleX(has?zoom:1f).scaleY(has?zoom:1f)
                                .translationY(has?focusLift():0f)
                                .translationZ(has?px(12):0)
                                .setInterpolator(interp).setDuration(focusDuration(dur)).start();

                        if(imgRef[0]!=null) {
                            if(!instantFocus()&&store.flag("focus_parallax",true)) {
                                imgRef[0].animate().scaleX(has&&!instantFocus()?1.045f:1f).scaleY(has&&!instantFocus()?1.045f:1f)
                                        .setInterpolator(crispDecel).setDuration(focusDuration(has?280:180)).start();
                            } else {
                                imgRef[0].setScaleX(1f); imgRef[0].setScaleY(1f);
                            }
                        }

                        title.animate().cancel();
                        title.animate().alpha(has?1f:0.76f)
                                .translationY(has&&useSpring?px(3):0f)
                                .setInterpolator(crispDecel).setDuration(focusDuration(has?200:150)).start();
                        title.setTextColor(has?foreground():muted());

                        if(useSpring && rowStage!=null) {
                            glow.animate().cancel();
                            glow.setAlpha(0f);
                            glow.setVisibility(View.INVISIBLE);
                            if(has) {
                                int w=picture.getWidth()>0?picture.getWidth():naturalWidth[0];
                                int h=picture.getHeight()>0?picture.getHeight():px(artHeight);
                                rowStage.onTileFocused(card,picture,w,h,glowColor);
                            } else {
                                rowStage.onTileUnfocused(card,picture);
                            }
                        } else {
                            if(rowStage!=null) rowStage.hideImmediate();
                            boolean showGlow=has&&store.flag("ambient_glow",true)&&!backgroundGlow()&&glowColor!=0;
                            glow.setVisibility(showGlow?View.VISIBLE:View.INVISIBLE);
                            glow.animate().cancel();
                            if(showGlow) {
                                glow.setScaleX(0.88f); glow.setScaleY(0.88f);
                                glow.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(focusDuration(180)).start();
                            } else {
                                glow.setAlpha(0f);
                            }
                        }

                        if(has){updateAmbient(pkg);revealHorizontalFocus(card,vertPad);}
                    });
                    card.addOnLayoutChangeListener((v,left,top,right,bottom,oldLeft,oldTop,oldRight,oldBottom)->{
                        if(v.hasFocus()&&(left!=oldLeft||right!=oldRight||top!=oldTop||bottom!=oldBottom)){
                            if(springFocusActive() && rowStage!=null) {
                                int w=picture.getWidth()>0?picture.getWidth():naturalWidth[0];
                                int h=picture.getHeight()>0?picture.getHeight():px(artHeight);
                                rowStage.onTileFocused(card,picture,w,h,glowColor);
                            }
                            revealHorizontalFocus(v,vertPad);
                        }
                    });
                    card.setOnKeyListener((v,key,event)->{
                        if (key==KeyEvent.KEYCODE_BACK) { if(event.getAction()==KeyEvent.ACTION_UP){View source=views.get(pkg);if(source!=null)source.requestFocus();else if(focusedFavorite!=null)focusedFavorite.requestFocus();} return true; } return false;
                    });
                    LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(naturalWidth[0],px(artHeight+recommendationTitleHeight(size)));
                    if(rowViews.size()+1<feed.items.size())lp.rightMargin=px(40);
                    row.addView(card,lp); rowViews.add(card);
                }
                scroll.addView(rowStage.host);section.addView(scroll);section.setVisibility(View.VISIBLE);applyViewSoundPolicy(section);wireNavigation();
            });
        }
        showRecentRow();
        if(recentSection!=null) applyViewSoundPolicy(recentSection);
        wireNavigation();
    }
    private int artworkWidth(int width,int height,int targetHeight,int limit) {
        if(width<=0||height<=0)return Math.min(limit,targetHeight*16/9);
        return Math.max(px(64),Math.min(limit,(int)Math.round((double)width*targetHeight/height)));
    }
    private void revealHorizontalFocus(View view) {
        revealHorizontalFocus(view,0);
    }
    private void positionHomeSection(View focused) {
        if(homeScroll==null||homePage==null||focused==null||!focused.hasFocus())return;
        View section=focused;
        if(appViews.contains(focused)||topPillViews.contains(focused)||header.hasFocus()) {
            homeScroll.smoothScrollTo(0,0);
            return;
        }
        if(dockViews.contains(focused)) {
            homeScroll.smoothScrollTo(0,Math.max(0,homePage.getHeight()-homeScroll.getHeight()));
            return;
        }
        // Include the section heading and row padding, rather than just the focused card.
        while(section.getParent() instanceof View && section.getParent()!=homePage
                && section.getParent()!=contentRail) section=(View)section.getParent();
        if(section.getParent()!=homePage && section.getParent()!=contentRail)return;
        android.graphics.Rect bounds=new android.graphics.Rect(0,0,section.getWidth(),section.getHeight());
        homePage.offsetDescendantRectToMyCoords(section,bounds);
        int room=Math.max(0,homeScroll.getHeight()-bounds.height());
        int target=Math.max(0,bounds.top-room/2);
        target=Math.min(target,Math.max(0,homePage.getHeight()-homeScroll.getHeight()));
        homeScroll.smoothScrollTo(0,target);
    }
    private void centerDrawerChild(View focused) {
        if(destroyed || !drawer || drawerScroll==null || drawerPage==null || focused==null || !focused.hasFocus()) return;
        if(focused==searchBar) {
            drawerScroll.smoothScrollTo(0,0);
            return;
        }
        if(dockViews.contains(focused)) {
            drawerScroll.smoothScrollTo(0,Math.max(0,drawerPage.getHeight()-drawerScroll.getHeight()));
            return;
        }
        if(!appRows.isEmpty() && appRows.get(0).contains(focused)) {
            drawerScroll.smoothScrollTo(0,0);
            return;
        }
        android.graphics.Rect bounds=new android.graphics.Rect(0,0,focused.getWidth(),focused.getHeight());
        drawerPage.offsetDescendantRectToMyCoords(focused,bounds);
        int viewH=drawerScroll.getHeight()>0?drawerScroll.getHeight():screenHeight;
        int room=Math.max(0,viewH-bounds.height());
        int target=Math.max(0,bounds.top-room/2);
        int maxScroll=Math.max(0,drawerPage.getHeight()-viewH);
        target=Math.min(target,maxScroll);
        drawerScroll.smoothScrollTo(0,target);
    }
    private void revealHorizontalFocus(View view,int verticalPad) {
        // Reveal room for the focus lift and full radial glow halo, not only the unscaled tile bounds.
        view.post(()->{
            if(!view.hasFocus()||!view.isAttachedToWindow())return;
            int pad=Math.max(px(72),px(glowRadiusSetting()+18));
            android.graphics.Rect area=new android.graphics.Rect(-pad,-verticalPad,view.getWidth()+pad,view.getHeight()+verticalPad);
            view.requestRectangleOnScreen(area,false);
        });
    }
    private void showRecentRow() {
        if(recentSpringStage!=null){recentSpringStage.stop();recentSpringStage=null;}
        if(recentSection!=null && recentSection.getParent()==homePage)homePage.removeView(recentSection);
        recentSection=null;
        if(homePage==null || drawer || !store.flag("recents"))return;
        List<String> recent=new ArrayList<>();
        for(String pkg:store.list("recent_apps"))if(byPackage.containsKey(pkg))recent.add(pkg);
        if(recent.isEmpty())return;
        recentSection=new LinearLayout(this);recentSection.setOrientation(LinearLayout.VERTICAL);
        recentSection.setClipChildren(false);recentSection.setClipToPadding(false);
        recentSection.setPadding(px(12),px(20),px(12),px(20));
        recentSection.setBackground(glassPanelDrawable(recentSection,26f,false));
        int count=Math.max(4,hdmiInputs().size()+2);
        int availableWidth=screenWidth-homePage.getPaddingLeft()-homePage.getPaddingRight()
                -recentSection.getPaddingLeft()-recentSection.getPaddingRight();
        int cardWidth=Math.max(1,(availableWidth-px(24)*(count-1))/count);
        int cardHeight=Math.max(1,Math.round(cardWidth*9f/16f));
        HorizontalScrollView scroll=new HorizontalScrollView(this) {
            @Override public void playSoundEffect(int soundConstant) {
                if(muteSystem())return;
                super.playSoundEffect(soundConstant);
            }
            @Override protected void dispatchDraw(android.graphics.Canvas canvas) {
                int save=canvas.save();
                canvas.clipRect(getScrollX(),getScrollY(),getScrollX()+getWidth(),getScrollY()+getHeight());
                super.dispatchDraw(canvas);
                canvas.restoreToCount(save);
            }
        };
        scroll.setSoundEffectsEnabled(!muteSystem());scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipChildren(true);scroll.setClipToPadding(false);scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);row.setClipToPadding(false);
        int edgePadding=Math.max(px(40),px(glowRadiusSetting()+18));
        row.setPadding(edgePadding,px(40),edgePadding,px(40));
        List<View> rowViews=new ArrayList<>();contentRows.add(rowViews);
        for(int i=0;i<recent.size();i++) {
            View tile=appTile(recent.get(i),cardWidth,cardHeight,true,true);
            LinearLayout.LayoutParams cell=new LinearLayout.LayoutParams(cardWidth,cardHeight);
            if(i+1<recent.size())cell.rightMargin=px(24);
            row.addView(tile,cell);rowViews.add(tile);
        }
        recentSpringStage=new SpringStage(row,false);
        scroll.addView(recentSpringStage.host);
        recentSection.addView(scroll,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout.LayoutParams place=new LinearLayout.LayoutParams(-1,-2);place.topMargin=px(72);
        homePage.addView(recentSection,Math.max(0,homePage.getChildCount()-1),place);
    }

    private void appMenu(String pkg) {
        List<String> labels=new ArrayList<>(); List<Runnable> actions=new ArrayList<>();
        List<String> favorite=favorites(); boolean pinned=favorite.contains(pkg);
        add(labels,actions,"§HOME ROW",null);
        add(labels,actions,pinned?"Move on Home":"Add to Home",()->{
            if (pinned) { drawer=false; moving=pkg; lastFocus=pkg; render(); }
            else {
                List<String> removed=store.list("removed_favorites"); removed.remove(pkg); store.list("removed_favorites",removed);
                if (!favorite.contains(pkg)) favorite.add(pkg);
                store.list("favorites",favorite); render(); toast("Added to Home");
            }
        });
        if (pinned) {
            add(labels,actions,"Replace with another app",()->chooseApp("Replace "+name(pkg),true,chosen->{
                List<String> removed=store.list("removed_favorites"); removed.remove(chosen);
                if (!removed.contains(pkg)) removed.add(pkg);
                store.list("removed_favorites",removed);
                List<String> latest=store.list("favorites"); int index=latest.indexOf(pkg);
                if(index>=0) latest.set(index,chosen); else if(!latest.contains(chosen)) latest.add(chosen);
                store.list("favorites",latest); lastFocus=chosen; render();
            },()->appMenu(pkg)));
            add(labels,actions,"Remove from Home",()->{
                List<String> removed=store.list("removed_favorites");
                if (!removed.contains(pkg)) removed.add(pkg);
                store.list("removed_favorites",removed);
                favorite.remove(pkg); store.list("favorites",favorite); lastFocus=ALL; render();
                toast("Removed from Home. Still available in All Apps.");
            });
        }
        add(labels,actions,"§CUSTOMIZE TILE",null);
        add(labels,actions,"Rename tile: "+name(pkg),()->rename(pkg));
        add(labels,actions,"Artwork: "+(store.flag("home_banner."+pkg)?"Banner":"Icon"),()->artworkMenu(pkg));
        add(labels,actions,"Tile glow: "+appGlowOverrideName(pkg),()->appGlowMenu(pkg));
        add(labels,actions,"Section: "+(utility(pkg)?"TV utilities":"Apps"),()->{
            store.setFlag("utility."+pkg,!utility(pkg));syncFavorites();render();appMenu(pkg);
        });
        add(labels,actions,"§CONTENT",null);
        RecommendationRepository.Feed known=recommendations.cached(pkg);
        String status=store.recommendations(pkg)?"Recommendations: On":known!=null&&known.items.isEmpty()
                ?"Recommendations: Unavailable":"Recommendations: Off";
        if (!store.flag("recommendations_master") && store.recommendations(pkg)) status="Recommendations: On · paused";
        add(labels,actions,status,()->toggleAppRecommendations(pkg));
        add(labels,actions,"Featured banner source",()->{
            store.set("featured_source",pkg); store.setFlag("featured",true); render(); bannerMenu();
        });
        menu(name(pkg),labels,actions,null);
    }
    private void moveFavorite(String pkg,int delta) {
        List<String> list=favorites(); int from=list.indexOf(pkg), to=from+delta;
        if (from<0||to<0||to>=list.size()) return;
        if(sfx!=null) sfx.playDetent(0.5f,Math.max(10,Math.min(90,to*100/Math.max(1,list.size()-1))));
        list.remove(from); list.add(to,pkg); store.list("favorites",list); lastFocus=pkg; render();
    }
    private void rename(String pkg) {
        LinearLayout box=new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        TextView subtitle=text("Enter a custom label or leave blank to restore the original app name.",19,muted());
        LinearLayout.LayoutParams subParams=new LinearLayout.LayoutParams(-1,-2); subParams.bottomMargin=px(18);
        box.addView(subtitle,subParams);
        EditText edit=new EditText(this); edit.setText(name(pkg)); edit.setSingleLine(true);
        edit.setTextColor(foreground()); edit.setHintTextColor(muted());
        edit.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,px(22));
        edit.setPadding(px(18),px(14),px(18),px(14));
        edit.setBackground(shapeRadius(themed(0x1AFFFFFF,0xBB182435),accent(),2,12));
        edit.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(40)});
        edit.setSelectAllOnFocus(true);
        box.addView(edit,new LinearLayout.LayoutParams(-1,px(60)));
        Button restore=button("Restore original name",()->{
            store.set("name."+pkg,byPackage.get(pkg).originalName); refreshApps(); render(); appMenu(pkg);
        });
        LinearLayout.LayoutParams restoreParams=new LinearLayout.LayoutParams(-1,px(52)); restoreParams.topMargin=px(16);
        box.addView(restore,restoreParams);
        showBladePanel("Rename tile",box,"Save",()->{
            String value=edit.getText().toString().trim();
            store.set("name."+pkg,value.isEmpty()?byPackage.get(pkg).originalName:value);
            refreshApps(); render(); appMenu(pkg);
        },()->appMenu(pkg),edit);
    }
    private void artworkMenu(String pkg) {
        boolean custom=!store.value("art."+pkg,"").isEmpty();
        boolean banner=store.flag("home_banner."+pkg);
        menu(name(pkg)+" · Artwork",Arrays.asList(
                (!custom&&!banner?"✓ ":"○ ")+"App icon · Circular Home default",
                (!custom&&banner?"✓ ":"○ ")+"App banner · Widescreen artwork",
                (custom?"✓ ":"○ ")+"Choose custom photo"),
                Arrays.asList(
                        ()->{store.set("art."+pkg,"");store.setFlag("icon."+pkg,true);store.setFlag("home_banner."+pkg,false);render();appMenu(pkg);},
                        ()->{store.set("art."+pkg,"");store.setFlag("icon."+pkg,false);store.setFlag("home_banner."+pkg,true);render();appMenu(pkg);},
                        ()->pickImage("art."+pkg)),()->appMenu(pkg));
    }
    private void toggleAppRecommendations(String pkg) {
        if (store.recommendations(pkg)) {
            store.recommendations(pkg,false); render(); appMenu(pkg); return;
        }
        toast("Checking shared recommendations…");
        recommendations.load(pkg,feed->{
            if (feed.items.isEmpty()) {
                menu("Recommendations unavailable",Arrays.asList(feed.status,"Refresh availability","Recommendation settings"),
                        Arrays.asList(()->info("About shared content","Apps must publish accessible recommendations. An account or subscription alone does not provide this launcher with a feed.",()->appMenu(pkg)),
                                ()->{recommendations.clear();toggleAppRecommendations(pkg);},this::recommendationMenu),()->appMenu(pkg));
            } else {
                store.recommendations(pkg,true); render(); appMenu(pkg);
                if (!store.flag("recommendations_master")) toast("Preference saved. Recommendations are paused in Settings.");
            }
        });
    }
    private String versionName() {
        try { return getPackageManager().getPackageInfo(getPackageName(),0).versionName; }
        catch (Exception ignored) { return "0.5.5"; }
    }
    private void settingsMenu() {
        List<String> labels=new ArrayList<>(); List<Runnable> actions=new ArrayList<>();
        String source=store.value("featured_source","");
        String bannerState=!store.flag("featured")?"Off":source.isEmpty()?"Choose app":name(source);
        String recState=store.flag("recommendations_master")?recommendationSizeName(recommendationSize()):"Off";
        add(labels,actions,"§PERSONALIZE",null);
        add(labels,actions,"@palette|Appearance: "+themeName(),this::appearanceMenu);
        add(labels,actions,"@wifi|Wallpapers & QR drop: "+backgroundSummary(),()->backgroundMenu(this::settingsMenu));
        add(labels,actions,"@sparkles|Glass & surfaces: "+glassSurfacesSummary(),()->glassSurfacesMenu(this::settingsMenu));
        add(labels,actions,"@sparkles|Focus glow: "+glowSummary(),()->focusGlowMenu(this::settingsMenu));
        add(labels,actions,"@sparkles|Focus motion: "+focusMotionSummary(),()->focusMotionMenu(this::settingsMenu));
        add(labels,actions,"@sound|UI sounds: "+(sfx!=null?sfx.summary():"On"),()->soundMenu(this::settingsMenu));
        add(labels,actions,"@clock|Idle screensaver: "+screensaverSummary(),()->screensaverMenu(this::settingsMenu));
        add(labels,actions,"@banner|Featured banner: "+bannerState,this::bannerMenu);
        add(labels,actions,"@sparkles|Recommendations: "+recState,this::recommendationMenu);
        add(labels,actions,"@clock|Recent shortcuts: "+onOff(store.flag("recents")),()->{
            store.setFlag("recents",!store.flag("recents"));render();settingsMenu();
        });
        if (!store.list("recent_apps").isEmpty()) {
            add(labels,actions,"Clear recent history",()->{
                store.list("recent_apps",new ArrayList<>());render();toast("Local recent history cleared");settingsMenu();
            });
        }
        if (!store.list("removed_favorites").isEmpty()) {
            add(labels,actions,"Restore all Home icons",()->{
                store.list("removed_favorites",new ArrayList<>());store.setFlag("favorites_custom_only",false);
                syncFavorites();render();toast("All regular apps restored to Home");settingsMenu();
            });
        }
        add(labels,actions,"§TV & LAUNCHER",null);
        add(labels,actions,"@hdmi|HDMI inputs: "+hdmiInputs().size()+" shown",this::inputMenu);
        add(labels,actions,"@home|Default launcher: "+(isDefaultHome()?"Active":"Preview"),this::defaultLauncherMenu);
        add(labels,actions,"§SYSTEM",null);
        add(labels,actions,"@display|Picture & display ↗",()->openSystemIntent(Settings.ACTION_DISPLAY_SETTINGS,"android.settings.PICTURE_SETTINGS"));
        add(labels,actions,"@sound|Sound & audio ↗",()->openSystemIntent(Settings.ACTION_SOUND_SETTINGS));
        add(labels,actions,"@wifi|Wi-Fi & network ↗",()->openSystemIntent(Settings.ACTION_WIFI_SETTINGS,Settings.ACTION_WIRELESS_SETTINGS));
        add(labels,actions,"@sparkles|Manage apps ↗",()->openSystemIntent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS));
        add(labels,actions,"@settings|All TV settings ↗",()->openSystemIntent(Settings.ACTION_SETTINGS));
        add(labels,actions,"@info|About Luma Home: v"+versionName(),this::aboutMenu);
        menu("Luma Settings",labels,actions,null);
    }
    private void openSystemIntent(String... actions) {
        for(String action:actions) {
            Intent intent=new Intent(action);
            if(intent.resolveActivity(getPackageManager())!=null) {
                try{startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));return;}catch(Exception ignored){}
            }
        }
        startSafely(new Intent(Settings.ACTION_SETTINGS));
    }
    private String backgroundSummary() {
        String bg=store.value("background","ember");
        if("photo".equals(bg)) {
            int slide=store.number("wallpaper_interval_min",0);
            int saved=PhoneDropServer.listSavedFiles(this,store).size();
            if(slide>0 && saved>=2) return "Slideshow · "+slideshowIntervalLabel(slide);
            return saved>0?"Phone photo ("+saved+")":"Custom photo";
        }
        return "app_glow".equals(bg)?"App-color glow":"sky".equals(bg)?"Dynamic sky":"solid_black".equals(bg)?"Black":"solid_slate".equals(bg)?"Slate":
                bg.substring(0,1).toUpperCase(Locale.ROOT)+bg.substring(1);
    }
    private String slideshowIntervalLabel(int min) {
        if(min<=0) return "Off";
        if(min>=60) return (min/60)+" hr";
        return min+" min";
    }
    private void appearanceMenu() {
        String bgLabel=backgroundSummary();
        int clockSize=store.number("clock_size",176);
        String clockLabel=clockSize==115?"Small":clockSize==138?"Medium":"Large";
        menu("Appearance",Arrays.asList(
                "§THEME & WALLPAPER",
                "Background: "+bgLabel,
                "Menu theme: "+themeName(),
                "Glass & surfaces: "+glassSurfacesSummary(),
                "Wallpaper tint: "+colorIntensity()+"%",
                "Focus glow: "+glowSummary(),
                "Focus motion: "+focusMotionSummary(),
                "UI sounds: "+(sfx!=null?sfx.summary():"On"),
                "§LAYOUT & BARS",
                "Tile size: "+(columns()==4?"Large":columns()==8?"Compact":"Comfortable"),
                "Clock size: "+clockLabel,
                "Clock style: "+clockStyleName(),
                "Time-of-day greeting: "+onOff(store.flag("show_greeting",true)),
                "Quick glance widget: "+onOff(store.flag("show_glance",true)),
                "Weather & forecast: "+(latestWeather!=null?(latestWeather.tempDisplay()+" · "+latestWeather.city):"Open"),
                "Bottom dock bar: "+onOff(store.flag("show_dock",true)),
                "Always show app names: "+onOff(store.flag("labels",false)),
                "High contrast: "+onOff(store.flag("contrast")),
                "Separate TV utilities: "+onOff(store.flag("group_utilities",true)),
                "§IDLE SCREENSAVER",
                "Idle screensaver: "+screensaverSummary(),
                "Preview screensaver now"),
                Arrays.asList(
                        null,
                        this::backgroundMenu,
                        ()->choice("Menu theme",new String[]{"Warm glass · Translucent warm dark tint","Cool slate · Crisp deep blue-grey","From wallpaper · Sampled from your background"},
                                "wallpaper".equals(store.value("theme","warm"))?2:"cool".equals(store.value("theme","warm"))?1:0,
                                index->{store.set("theme",new String[]{"warm","cool","wallpaper"}[index]);render();appearanceMenu();},this::appearanceMenu),
                        ()->glassSurfacesMenu(this::appearanceMenu),
                        this::colorIntensityDialog,
                        ()->focusGlowMenu(this::appearanceMenu),
                        ()->focusMotionMenu(this::appearanceMenu),
                        ()->soundMenu(this::appearanceMenu),
                        null,
                        ()->choice("Tile size",new String[]{"Compact · 8 columns in All Apps","Comfortable · 6 columns in All Apps","Large · 4 columns in All Apps"},
                                columns()==8?0:columns()==6?1:2,index->{store.setNumber("columns",new int[]{8,6,4}[index]);render();appearanceMenu();},this::appearanceMenu),
                        ()->choice("Clock size",new String[]{"Small","Medium","Large"},clockSize==115?0:clockSize==138?1:2,
                                index->{store.setNumber("clock_size",new int[]{115,138,176}[index]);render();appearanceMenu();},this::appearanceMenu),
                        ()->clockStyleChoice(this::appearanceMenu),
                        ()->{store.setFlag("show_greeting",!store.flag("show_greeting",true));render();appearanceMenu();},
                        ()->{store.setFlag("show_glance",!store.flag("show_glance",true));render();appearanceMenu();},
                        this::glanceDetailsBlade,
                        ()->{
                            boolean next=!store.flag("show_dock",true);
                            store.setFlag("show_dock",next);
                            if(!next)toast("Quick actions move to the top");
                            render();appearanceMenu();
                        },
                        ()->{store.setFlag("labels",!store.flag("labels",false));render();appearanceMenu();},
                        ()->{store.setFlag("contrast",!store.flag("contrast"));render();appearanceMenu();},
                        ()->{store.setFlag("group_utilities",!store.flag("group_utilities",true));syncFavorites();render();appearanceMenu();},
                        null,
                        ()->screensaverMenu(this::appearanceMenu),
                        ()->{closeBlade();showIdleClock();}),this::settingsMenu);
    }
    private void glassSurfacesMenu(Runnable back) {
        String st=uiSurfaceStyle();
        int frost=glassFrostDepth();
        int sheenIdx=glassSheenMode();
        menu("Glass & surfaces",Arrays.asList(
                "§GLASS MATERIAL",
                "Surface style: "+uiSurfaceStyleName(),
                "Frosted refraction: "+onOff(glassRefractionEnabled()),
                "Glass frost: "+frost+"% · "+glassFrostName(frost),
                "Specular sheen: "+glassSheenName(),
                "§WIDGET LENS",
                "Dock spring lens: "+onOff(store.flag("dock_spring_lens",true)),
                "§RESET",
                "Restore glass defaults"),
                Arrays.asList(
                        null,
                        ()->choice("Surface style",new String[]{
                                "Liquid glass · Specular lens reflections & beveled rims",
                                "Matte translucent · Soft frosted tint without specular glare",
                                "Solid dark · Opaque high-contrast panels"},
                                "matte".equals(st)?1:"solid".equals(st)?2:0,
                                idx->{
                                    store.set("ui_surface_style",new String[]{"glass","matte","solid"}[idx]);
                                    render();glassSurfacesMenu(back);
                                },()->glassSurfacesMenu(back)),
                        ()->{
                            store.setFlag("glass_refraction",!glassRefractionEnabled());
                            render();glassSurfacesMenu(back);
                        },
                        ()->glassFrostSliderDialog(back),
                        ()->choice("Specular sheen",new String[]{
                                "Subtle · Delicate top highlight",
                                "Balanced · Natural optical glass reflection",
                                "Bright · High-gloss crystal catchlight"},
                                sheenIdx,
                                idx->{
                                    store.setNumber("glass_sheen",idx);
                                    render();glassSurfacesMenu(back);
                                },()->glassSurfacesMenu(back)),
                        null,
                        ()->{
                            store.setFlag("dock_spring_lens",!store.flag("dock_spring_lens",true));
                            render();glassSurfacesMenu(back);
                        },
                        null,
                        ()->{
                            store.set("ui_surface_style","glass");
                            store.setFlag("glass_refraction",true);
                            store.setNumber("glass_frost",64);
                            store.setNumber("glass_sheen",1);
                            store.setFlag("dock_spring_lens",true);
                            render();toast("Glass surfaces restored to defaults");glassSurfacesMenu(back);
                        }),back);
    }
    private void glassFrostSliderDialog(Runnable back) {
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);
        TextView value=text("",22,foreground());value.setTypeface(medium);box.addView(value);
        TextView hint=text("Left / Right adjusts frosted tint depth across widgets and this blade live.",18,muted());
        LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(-1,-2);hp.topMargin=px(4);box.addView(hint,hp);

        LinearLayout card=new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(px(20),px(18),px(20),px(18));
        card.setBackground(glassPanelDrawable(card,20f,false));
        TextView ct=text("Liquid Glass Surface",22,foreground());ct.setTypeface(medium);card.addView(ct);
        TextView cs=text("Wallpaper colors refract softly underneath while text stays crisp.",17,muted());
        LinearLayout.LayoutParams csp=new LinearLayout.LayoutParams(-1,-2);csp.topMargin=px(4);card.addView(cs,csp);
        TextView pillSample=text("Focused Glass Lens",19,foreground());
        pillSample.setTypeface(medium);
        pillSample.setGravity(Gravity.CENTER);
        pillSample.setBackground(glassControlDrawable(pillSample,true,14f,accent(),false,false));
        LinearLayout.LayoutParams psp=new LinearLayout.LayoutParams(-1,px(48));psp.topMargin=px(14);
        card.addView(pillSample,psp);
        LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2);cp.topMargin=px(16);cp.bottomMargin=px(16);
        box.addView(card,cp);

        SeekBar slider=new SeekBar(this);
        slider.setFocusableInTouchMode(true);
        slider.setMax(67);
        slider.setProgress(glassFrostDepth()-25);
        slider.setKeyProgressIncrement(4);
        slider.setProgressTintList(android.content.res.ColorStateList.valueOf(accent()));
        slider.setThumbTintList(android.content.res.ColorStateList.valueOf(accent()));
        box.addView(slider,new LinearLayout.LayoutParams(-1,px(60)));

        Runnable update=()->{
            int f=slider.getProgress()+25;
            overrideFrostPct=f;
            value.setText("Glass frost · "+f+"% ("+glassFrostName(f)+")");
            card.invalidate();
            pillSample.invalidate();
            refreshGlassSurfaces();
        };
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar bar,int progress,boolean user){update.run();}
            public void onStartTrackingTouch(SeekBar bar){}
            public void onStopTrackingTouch(SeekBar bar){}
        });
        update.run();

        showBladePanel("Glass frost",box,"Save",()->{
            int f=slider.getProgress()+25;
            overrideFrostPct=-1;
            if("solid".equals(uiSurfaceStyle())) store.set("ui_surface_style","glass");
            store.setNumber("glass_frost",f);
            render();glassSurfacesMenu(back);
        },()->{ overrideFrostPct=-1; refreshGlassSurfaces(); glassSurfacesMenu(back); },slider);
    }
    private void appGlowMenu(String pkg) {
        String cur=store.value("glow_app."+pkg,"default");
        String[] opts={"Default · Follow global Focus glow setting","Electric cyan","Royal blue","Neon violet","Crimson red","Warm amber","Emerald green","Pure white","Off for this app"};
        String[] vals={"default","hue:190","hue:225","hue:275","hue:355","hue:28","hue:145","white","off"};
        int sel=0;
        for(int i=0;i<vals.length;i++) if(vals[i].equals(cur)) { sel=i; break; }
        choice(name(pkg)+" · Glow",opts,sel,idx->{
            store.set("glow_app."+pkg,vals[idx]);render();appMenu(pkg);
        },()->appMenu(pkg));
    }
    private void focusGlowMenu(Runnable back) {
        int rad=glowRadiusSetting();
        String mode=store.value("glow_color_mode","auto");
        int customHue=store.number("glow_custom_hue",190);
        menu("Focus glow",Arrays.asList(
                "§GLOW PARAMETERS",
                "Focus glow: "+onOff(store.flag("ambient_glow",true)),
                "Intensity: "+glowIntensity()+"%",
                "Radius: "+rad+"px · "+glowRadiusName(rad),
                "§GLOW COLOR",
                "Color mode: "+glowColorModeName(),
                "Custom color: "+glowHueName(customHue)+" ("+customHue+"°)",
                "§RESET",
                "Restore glow defaults"),
                Arrays.asList(
                        null,
                        ()->{store.setFlag("ambient_glow",!store.flag("ambient_glow",true));render();focusGlowMenu(back);},
                        ()->glowSliderDialog(true,back),
                        ()->glowSliderDialog(false,back),
                        null,
                        ()->choice("Glow color mode",new String[]{
                                "App icon · Automatically matches each app's brand color",
                                "Wallpaper / Theme · Uses your menu accent color",
                                "Pure white · Crisp icy glass halo",
                                "Custom color · Use your chosen preset or hue"},
                                "accent".equals(mode)?1:"white".equals(mode)?2:"custom".equals(mode)?3:0,
                                idx->{
                                    String picked=new String[]{"auto","accent","white","custom"}[idx];
                                    store.set("glow_color_mode",picked);render();
                                    if("custom".equals(picked))glowCustomColorMenu(back); else focusGlowMenu(back);
                                },()->focusGlowMenu(back)),
                        ()->glowCustomColorMenu(back),
                        null,
                        ()->{
                            store.setFlag("ambient_glow",true);
                            store.setNumber("glow_intensity",80);
                            store.setNumber("glow_radius",56);
                            store.set("glow_color_mode","auto");
                            render();toast("Focus glow restored to defaults");focusGlowMenu(back);
                        }),back);
    }
    private void focusMotionMenu(Runnable back) {
        String style=focusMotionStyle();
        String glass=focusGlassStyle();
        int zoomIdx=focusZoomMode();
        menu("Focus motion",Arrays.asList(
                "§SPRING & GLIDE",
                "Motion style: "+focusMotionStyleName(),
                "Glide speed: "+focusSpringSpeed()+"%",
                "§LENS & DEPTH",
                "Glass lens: "+focusGlassStyleName(),
                "Tile zoom: "+focusZoomName(),
                "Inner icon parallax: "+onOff(store.flag("focus_parallax",true)),
                "Sliding app labels: "+onOff(store.flag("focus_slide_labels",true)),
                "§RELATED",
                "Focus glow: "+glowSummary(),
                "Restore motion defaults"),
                Arrays.asList(
                        null,
                        ()->choice("Motion style",new String[]{
                                "Liquid spring · CleanYourTV glide with subtle cushion",
                                "Crisp glide · Travelling focus with zero bounce",
                                "Classic \u00b7 Stationary per-tile fade",
                                "Soft lift \u00b7 Gentle rise without bounce",
                                "Magnetic \u00b7 Stretching highlight that catches up",
                                "Instant \u00b7 Immediate focus without movement"},
                                motionStyleIndex(),
                                idx->{
                                    store.set("focus_motion_style",new String[]{"liquid","crisp","classic","soft","magnetic","instant"}[idx]);
                                    render();focusMotionMenu(back);
                                },()->focusMotionMenu(back)),
                        ()->focusSpeedSliderDialog(back),
                        null,
                        ()->choice("Glass lens",new String[]{
                                "Specular glass · Translucent top sheen + luminous rim",
                                "Clean rim · Crisp glowing border only",
                                "Glow only · Pure backlight halo without a border"},
                                "rim".equals(glass)?1:"none".equals(glass)?2:0,
                                idx->{
                                    store.set("focus_glass_style",new String[]{"full","rim","none"}[idx]);
                                    render();focusMotionMenu(back);
                                },()->focusMotionMenu(back)),
                        ()->choice("Tile zoom",new String[]{
                                "Subtle · 1.06x gentle lift",
                                "Balanced · 1.12x CleanYourTV zoom",
                                "Bold · 1.17x dramatic pop"},
                                zoomIdx,
                                idx->{
                                    store.setNumber("focus_zoom_mode",idx);
                                    render();focusMotionMenu(back);
                                },()->focusMotionMenu(back)),
                        ()->{store.setFlag("focus_parallax",!store.flag("focus_parallax",true));render();focusMotionMenu(back);},
                        ()->{store.setFlag("focus_slide_labels",!store.flag("focus_slide_labels",true));render();focusMotionMenu(back);},
                        null,
                        ()->focusGlowMenu(()->focusMotionMenu(back)),
                        ()->{
                            store.set("focus_motion_style","liquid");
                            store.setNumber("focus_spring_speed",100);
                            store.set("focus_glass_style","full");
                            store.setNumber("focus_zoom_mode",1);
                            store.setFlag("focus_parallax",true);
                            store.setFlag("focus_slide_labels",true);
                            render();toast("Focus motion restored to defaults");focusMotionMenu(back);
                        }),back);
    }
    private void focusSpeedSliderDialog(Runnable back) {
        if(previewSpringStage!=null) { previewSpringStage.stop(); previewSpringStage=null; }
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);
        TextView value=text("",22,foreground());value.setTypeface(medium);box.addView(value);
        TextView hint=text("Watch the live glide bounce between icons as you adjust Left / Right.",18,muted());
        LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(-1,-2);hp.topMargin=px(4);box.addView(hint,hp);

        List<String> favs=favorites();
        final String pkgA=!favs.isEmpty()?favs.get(0):"";
        final String pkgB=favs.size()>1?favs.get(1):pkgA;
        final int colA=resolveGlowColor(pkgA)!=0?resolveGlowColor(pkgA):0xFFFF1744;
        final int colB=resolveGlowColor(pkgB)!=0?resolveGlowColor(pkgB):0xFF00E5FF;

        FrameLayout outer=new FrameLayout(this);
        outer.setClipChildren(false);outer.setClipToPadding(false);
        outer.setBackground(shapeRadius(0xFF0E131D,0x1FFFFFFF,1,18));
        final int iconSz=px(92);
        LinearLayout pair=new LinearLayout(this);
        pair.setOrientation(LinearLayout.HORIZONTAL);
        pair.setGravity(Gravity.CENTER);
        pair.setClipChildren(false);pair.setClipToPadding(false);
        pair.setPadding(px(36),px(46),px(36),px(46));
        final FrameLayout[] slots=new FrameLayout[2];
        String[] pkgs={pkgA,pkgB};
        int[] cols={colA,colB};
        for(int i=0;i<2;i++) {
            FrameLayout slot=new FrameLayout(this);
            slot.setClipToOutline(true);
            slot.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override public void getOutline(View v,android.graphics.Outline o) { o.setOval(0,0,v.getWidth(),v.getHeight()); }
            });
            slot.setBackground(glassSurface(true,false,false,cols[i]));
            if(!pkgs[i].isEmpty()) {
                ImageView iv=new ImageView(this);iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setImageDrawable(homeIconDrawable(catalog.artwork(pkgs[i],true).drawable));
                slot.addView(iv,new FrameLayout.LayoutParams(-1,-1));
            }
            LinearLayout.LayoutParams slp=new LinearLayout.LayoutParams(iconSz,iconSz);
            if(i==1) slp.leftMargin=px(66);
            pair.addView(slot,slp);
            slots[i]=slot;
        }
        final SpringStage stage=new SpringStage(pair,true);
        previewSpringStage=stage;
        outer.addView(stage.host,new FrameLayout.LayoutParams(-2,-2,Gravity.CENTER));
        LinearLayout.LayoutParams op=new LinearLayout.LayoutParams(-1,px(220));op.topMargin=px(16);op.bottomMargin=px(16);
        box.addView(outer,op);

        SeekBar slider=new SeekBar(this);
        slider.setFocusableInTouchMode(true);
        slider.setMax(100);
        slider.setProgress(focusSpringSpeed()-50);
        slider.setKeyProgressIncrement(5);
        int uiAccent=store.flag("contrast")?accent():colA;
        slider.setProgressTintList(android.content.res.ColorStateList.valueOf(uiAccent));
        slider.setThumbTintList(android.content.res.ColorStateList.valueOf(uiAccent));
        box.addView(slider,new LinearLayout.LayoutParams(-1,px(60)));

        Runnable updateLabel=()->{
            int sp=slider.getProgress()+50;
            stage.overrideSpeedPct=sp;
            String tag=sp<80?"Cinematic":sp<=115?"Balanced":"Snappy";
            value.setText("Glide speed · "+sp+"% ("+tag+")");
        };
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar bar,int progress,boolean user){updateLabel.run();}
            public void onStartTrackingTouch(SeekBar bar){}
            public void onStopTrackingTouch(SeekBar bar){}
        });
        updateLabel.run();

        final int[] ping={0};
        final Runnable[] ticker={null};
        ticker[0]=()->{
            if(destroyed || previewSpringStage!=stage || !stage.host.isAttachedToWindow()) return;
            int idx=ping[0]%2;
            int other=1-idx;
            float z=focusZoomScale(false);
            slots[idx].animate().scaleX(z).scaleY(z).translationY(focusLift()).setInterpolator("liquid".equals(focusMotionStyle())?liquidOvershoot:crispDecel).setDuration(focusDuration(220)).start();
            slots[other].animate().scaleX(1f).scaleY(1f).translationY(0f).setInterpolator(crispDecel).setDuration(170).start();
            if(springFocusActive())stage.commitTarget(slots[idx],iconSz,iconSz,idx==0?colA:colB);else stage.hideImmediate();
            ping[0]++;
            handler.postDelayed(ticker[0],1150);
        };
        outer.postDelayed(ticker[0],180);

        showBladePanel("Glide speed",box,"Save",()->{
            int sp=slider.getProgress()+50;
            store.setNumber("focus_spring_speed",sp);
            stage.stop(); previewSpringStage=null;
            render();focusMotionMenu(back);
        },()->{ stage.stop(); previewSpringStage=null; focusMotionMenu(back); },slider);
    }
    private void glowCustomColorMenu(Runnable back) {
        int curHue=store.number("glow_custom_hue",190);
        boolean isCustom="custom".equals(store.value("glow_color_mode","auto"));
        int[] presetHues={190,225,275,355,28,145,315};
        String[] presetNames={
                "Electric cyan · Cool neon blue",
                "Royal blue · Deep sapphire",
                "Neon violet · Electric purple",
                "Crimson red · Vivid ruby",
                "Warm amber · Golden orange",
                "Emerald green · Vibrant mint",
                "Magenta · Neon rose"};
        List<String> labels=new ArrayList<>(); List<Runnable> actions=new ArrayList<>();
        add(labels,actions,"§CUSTOM HUE SLIDER",null);
        add(labels,actions,"Fine-tune hue: "+curHue+"° ("+glowHueName(curHue)+")",()->glowHueSliderDialog(back));
        add(labels,actions,"§COLOR PRESETS",null);
        for(int i=0;i<presetHues.length;i++) {
            final int h=presetHues[i];
            boolean checked=isCustom && Math.abs(curHue-h)<=4;
            add(labels,actions,(checked?"✓ ":"○ ")+presetNames[i],()->{
                store.set("glow_color_mode","custom");
                store.setNumber("glow_custom_hue",h);
                render();focusGlowMenu(back);
            });
        }
        menu("Custom glow color",labels,actions,()->focusGlowMenu(back));
    }
    private FrameLayout buildGlowPreviewStage(View[] outGlow,FrameLayout[] outIcon,int sampleColor) {
        FrameLayout stage=new FrameLayout(this);
        stage.setClipChildren(false);stage.setClipToPadding(false);
        stage.setBackground(shapeRadius(0xFF0E131D,0x1FFFFFFF,1,18));
        String samplePkg=lastFocus!=null&&byPackage.containsKey(lastFocus)?lastFocus:(!favorites().isEmpty()?favorites().get(0):"");
        int iconSize=px(116);
        View glow=new View(this);
        outGlow[0]=glow;
        stage.addView(glow,new FrameLayout.LayoutParams(iconSize,iconSize,Gravity.CENTER));
        FrameLayout pic=new FrameLayout(this);
        pic.setClipToOutline(true);
        pic.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View v,android.graphics.Outline o) { o.setOval(0,0,v.getWidth(),v.getHeight()); }
        });
        pic.setBackground(glassSurface(true,false,true,sampleColor));
        if(!samplePkg.isEmpty()) {
            ImageView iv=new ImageView(this);iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageDrawable(homeIconDrawable(catalog.artwork(samplePkg,true).drawable));
            pic.addView(iv,new FrameLayout.LayoutParams(-1,-1));
        }
        outIcon[0]=pic;
        stage.addView(pic,new FrameLayout.LayoutParams(iconSize,iconSize,Gravity.CENTER));
        return stage;
    }
    private void glowSliderDialog(boolean isIntensity,Runnable back) {
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);
        TextView value=text("",22,foreground());value.setTypeface(medium);box.addView(value);
        TextView hint=text("Use Left / Right on D-pad to preview live.",18,muted());
        LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(-1,-2);hp.topMargin=px(4);box.addView(hint,hp);

        String samplePkg=lastFocus!=null&&byPackage.containsKey(lastFocus)?lastFocus:(!favorites().isEmpty()?favorites().get(0):"");
        final int previewColor=resolveGlowColor(samplePkg)!=0?resolveGlowColor(samplePkg):0xFF00E5FF;
        View[] glowHolder={null}; FrameLayout[] iconHolder={null};
        FrameLayout stage=buildGlowPreviewStage(glowHolder,iconHolder,previewColor);
        LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-1,px(250));sp.topMargin=px(16);sp.bottomMargin=px(16);
        box.addView(stage,sp);

        SeekBar slider=new SeekBar(this);
        slider.setFocusableInTouchMode(true);
        if(isIntensity) {
            slider.setMax(90); slider.setProgress(glowIntensity()-10); slider.setKeyProgressIncrement(5);
        } else {
            slider.setMax(76); slider.setProgress(glowRadiusSetting()-20); slider.setKeyProgressIncrement(4);
        }
        int uiAccent=store.flag("contrast")?accent():previewColor;
        slider.setProgressTintList(android.content.res.ColorStateList.valueOf(uiAccent));
        slider.setThumbTintList(android.content.res.ColorStateList.valueOf(uiAccent));
        box.addView(slider,new LinearLayout.LayoutParams(-1,px(60)));

        final int iconSize=px(116);
        Runnable update=()->{
            int curInt=isIntensity?(slider.getProgress()+10):glowIntensity();
            int curRad=isIntensity?glowRadiusSetting():(slider.getProgress()+20);
            if(isIntensity) value.setText("Intensity · "+curInt+"%");
            else value.setText("Radius · "+curRad+"px ("+glowRadiusName(curRad)+")");
            int spread=px(Math.round(curRad*0.85f));
            FrameLayout.LayoutParams glp=new FrameLayout.LayoutParams(iconSize+spread*2,iconSize+spread*2,Gravity.CENTER);
            glowHolder[0].setLayoutParams(glp);
            glowHolder[0].setBackground(iconGlowDrawable(previewColor,true,iconSize/2f,spread,curInt));
        };
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar bar,int progress,boolean user){update.run();}
            public void onStartTrackingTouch(SeekBar bar){}
            public void onStopTrackingTouch(SeekBar bar){}
        });
        update.run();
        showBladePanel(isIntensity?"Glow intensity":"Glow radius",box,"Save",()->{
            if(isIntensity) store.setNumber("glow_intensity",slider.getProgress()+10);
            else store.setNumber("glow_radius",slider.getProgress()+20);
            store.setFlag("ambient_glow",true);
            render();focusGlowMenu(back);
        },()->focusGlowMenu(back),slider);
    }
    private void glowHueSliderDialog(Runnable back) {
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);
        TextView value=text("",22,foreground());value.setTypeface(medium);box.addView(value);
        TextView hint=text("Sweep across all 360° colors with Left / Right.",18,muted());
        LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(-1,-2);hp.topMargin=px(4);box.addView(hint,hp);

        int startHue=store.number("glow_custom_hue",190);
        int startCol=Color.HSVToColor(new float[]{startHue,0.88f,1f});
        View[] glowHolder={null}; FrameLayout[] iconHolder={null};
        FrameLayout stage=buildGlowPreviewStage(glowHolder,iconHolder,startCol);
        LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-1,px(250));sp.topMargin=px(16);sp.bottomMargin=px(16);
        box.addView(stage,sp);

        SeekBar slider=new SeekBar(this);
        slider.setFocusableInTouchMode(true);
        slider.setMax(359); slider.setProgress(Math.max(0,Math.min(359,startHue))); slider.setKeyProgressIncrement(6);
        box.addView(slider,new LinearLayout.LayoutParams(-1,px(60)));

        final int iconSize=px(116);
        final int spread=px(Math.round(glowRadiusSetting()*0.85f));
        glowHolder[0].setLayoutParams(new FrameLayout.LayoutParams(iconSize+spread*2,iconSize+spread*2,Gravity.CENTER));
        Runnable update=()->{
            int hue=slider.getProgress();
            int col=Color.HSVToColor(new float[]{hue,0.88f,1f});
            value.setText(glowHueName(hue)+" · "+hue+"°");
            glowHolder[0].setBackground(iconGlowDrawable(col,true,iconSize/2f,spread,glowIntensity()));
            iconHolder[0].setBackground(glassSurface(true,false,true,col));
            slider.setProgressTintList(android.content.res.ColorStateList.valueOf(col));
            slider.setThumbTintList(android.content.res.ColorStateList.valueOf(col));
        };
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar bar,int progress,boolean user){update.run();}
            public void onStartTrackingTouch(SeekBar bar){}
            public void onStopTrackingTouch(SeekBar bar){}
        });
        update.run();
        showBladePanel("Custom glow hue",box,"Save",()->{
            store.set("glow_color_mode","custom");
            store.setNumber("glow_custom_hue",slider.getProgress());
            store.setFlag("ambient_glow",true);
            render();focusGlowMenu(back);
        },()->glowCustomColorMenu(back),slider);
    }
    private void colorIntensityDialog(){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);
        TextView value=text("",22,foreground());value.setTypeface(medium);box.addView(value);
        TextView hint=text("Left / Right on D-pad to adjust tint strength.",18,muted());
        LinearLayout.LayoutParams hintParams=new LinearLayout.LayoutParams(-1,-2);hintParams.topMargin=px(4);box.addView(hint,hintParams);
        LinearLayout sample=new LinearLayout(this);sample.setOrientation(LinearLayout.VERTICAL);sample.setPadding(px(22),px(20),px(22),px(20));
        TextView title=text("Wallpaper colors",23,Color.WHITE);title.setTypeface(medium);sample.addView(title);
        TextView description=text(wallpaperSeed==0?"No usable wallpaper color. Warm glass is the fallback.":"A soft tint for menus, buttons and focus.",18,muted());
        LinearLayout.LayoutParams descParams=new LinearLayout.LayoutParams(-1,-2);descParams.topMargin=px(4);sample.addView(description,descParams);
        TextView selected=text("Focused menu row",20,Color.WHITE);selected.setGravity(Gravity.CENTER_VERTICAL);selected.setPadding(px(16),0,px(16),0);
        LinearLayout.LayoutParams itemParams=new LinearLayout.LayoutParams(-1,px(52));itemParams.topMargin=px(16);sample.addView(selected,itemParams);
        LinearLayout.LayoutParams sampleParams=new LinearLayout.LayoutParams(-1,-2);sampleParams.topMargin=px(18);sampleParams.bottomMargin=px(18);box.addView(sample,sampleParams);
        SeekBar slider=new SeekBar(this);slider.setMax(100);slider.setProgress(colorIntensity());slider.setKeyProgressIncrement(5);slider.setFocusableInTouchMode(true);
        box.addView(slider,new LinearLayout.LayoutParams(-1,px(64)));
        Runnable update=()->{
            int strength=slider.getProgress(),seed=wallpaperSeed==0?0xFF9A6A52:wallpaperSeed;
            value.setText("Color intensity · "+strength+"%");
            int accent=store.flag("contrast")?0xFFFFFF00:ThemePalette.accent(seed,strength);
            sample.setBackground(shapeRadius(ThemePalette.tint(seed,0xF7161113,strength),0x22FFFFFF,1,16));
            description.setTextColor(ThemePalette.muted(seed,strength));
            selected.setBackground(shapeRadius(ThemePalette.tint(seed,0x38FFFFFF,strength),accent,2,12));
            slider.setProgressTintList(android.content.res.ColorStateList.valueOf(accent));
            slider.setThumbTintList(android.content.res.ColorStateList.valueOf(accent));
        };
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar bar,int progress,boolean user){update.run();}
            public void onStartTrackingTouch(SeekBar bar){}
            public void onStopTrackingTouch(SeekBar bar){}
        });
        update.run();
        showBladePanel("Wallpaper tint",box,"Save",()->{
            store.setNumber("color_intensity",slider.getProgress());render();appearanceMenu();
        },this::appearanceMenu,slider);
    }
    private void backgroundMenu() { backgroundMenu(this::appearanceMenu); }
    private void backgroundMenu(Runnable back) {
        String bg=store.value("background","ember");
        boolean hasPhoto=!store.value("background_image","").isEmpty();
        int savedCount=PhoneDropServer.listSavedFiles(this,store).size();
        int slideMin=store.number("wallpaper_interval_min",0);
        List<String> labels=new ArrayList<>(); List<Runnable> actions=new ArrayList<>();
        add(labels,actions,"§PHONE DROP (WI-FI QR)",null);
        add(labels,actions,"@wifi|Send from phone: Scan QR",()->phoneDropPanel(()->backgroundMenu(back)));
        add(labels,actions,"Phone gallery: "+(savedCount==0?"None saved":savedCount+" saved"),()->{
            if(savedCount==0) phoneDropPanel(()->backgroundMenu(back));
            else wallpaperGalleryMenu(()->backgroundMenu(back));
        });
        add(labels,actions,"Wallpaper slideshow: "+slideshowIntervalLabel(slideMin),()->wallpaperSlideshowMenu(()->backgroundMenu(back)));
        add(labels,actions,"§BUILT-IN STYLES",null);
        add(labels,actions,"App-color glow: "+("app_glow".equals(bg)?"Active":"Follows the selected app"),()->{store.set("background","app_glow");resetWallpaperSlideshowTimer();render();backgroundMenu(back);});
        add(labels,actions,"Dynamic sky: "+("sky".equals(bg)?"Active":"Time of day"),()->{
            store.set("background","sky");resetWallpaperSlideshowTimer();render();backgroundMenu(back);
        });
        add(labels,actions,"Gradient: "+("ember".equals(bg)||"midnight".equals(bg)||"ocean".equals(bg)||"forest".equals(bg)?bg.substring(0,1).toUpperCase(Locale.ROOT)+bg.substring(1):"Select"),
                ()->choice("Gradient",new String[]{"Ember · Warm ambient glow","Midnight · Deep indigo","Ocean · Coastal blue","Forest · Pine green"},
                        Arrays.asList("ember","midnight","ocean","forest").indexOf(store.value("background","ember")),
                        index->{store.set("background",new String[]{"ember","midnight","ocean","forest"}[index]);resetWallpaperSlideshowTimer();render();backgroundMenu(back);},()->backgroundMenu(back)));
        add(labels,actions,"Solid color: "+("solid_black".equals(bg)?"Black":"solid_slate".equals(bg)?"Slate":"Select"),
                ()->choice("Solid color",new String[]{"Pure black · OLED friendly","Deep slate"},
                        "solid_black".equals(store.value("background",""))?0:"solid_slate".equals(store.value("background",""))?1:-1,
                        index->{store.set("background",index==0?"solid_black":"solid_slate");resetWallpaperSlideshowTimer();render();backgroundMenu(back);},()->backgroundMenu(back)));
        if(hasPhoto) {
            add(labels,actions,"Photo wallpaper: "+("photo".equals(bg)?"Active":"Use photo"),()->{
                store.set("background","photo");resetWallpaperSlideshowTimer();render();backgroundMenu(back);
            });
        }
        add(labels,actions,"Import from TV storage",()->pickImage("background_image"));
        add(labels,actions,"§BACKGROUND TREATMENT",null);
        add(labels,actions,"Treatment style: "+treatmentStyleName(),()->treatmentStyleChoice(()->backgroundMenu(back)));
        add(labels,actions,"Treatment intensity: "+treatmentIntensity()+"%",()->treatmentIntensityDialog(()->backgroundMenu(back)));
        add(labels,actions,"Wallpaper tint: "+colorIntensity()+"%",this::colorIntensityDialog);
        add(labels,actions,"Restore default background",()->{store.set("background","ember");store.set("background_treatment","dim");store.setNumber("dimming",0);resetWallpaperSlideshowTimer();render();backgroundMenu(back);});
        menu("Background",labels,actions,back);
    }
    private void stopPhoneDrop() {
        if(phoneDrop!=null) {
            phoneDrop.stop();
            phoneDrop=null;
        }
    }
    private void resetWallpaperSlideshowTimer() {
        handler.removeCallbacks(wallpaperSlideshowTick);
        if(destroyed || store==null) return;
        int min=store.number("wallpaper_interval_min",0);
        if(min>0 && "photo".equals(store.value("background","ember"))
                && PhoneDropServer.listSavedFiles(this,store).size()>=2) {
            handler.postDelayed(wallpaperSlideshowTick,min*60000L);
        }
    }
    private void advanceWallpaperSlideshow() {
        if(destroyed || store==null) return;
        List<File> files=PhoneDropServer.listSavedFiles(this,store);
        if(files.size()<2 || !"photo".equals(store.value("background","ember"))) return;
        String cur=store.value("background_image","");
        int idx=0;
        for(int i=0;i<files.size();i++) {
            if(files.get(i).toURI().toString().equals(cur)) { idx=(i+1)%files.size(); break; }
        }
        store.set("background_image",files.get(idx).toURI().toString());
        render();
        resetWallpaperSlideshowTimer();
    }
    private void wallpaperSlideshowMenu(Runnable back) {
        int cur=store.number("wallpaper_interval_min",0);
        int[] mins={0,5,15,30,60,360};
        String[] opts={
                "Off · Keep your chosen wallpaper fixed",
                "Every 5 minutes · Lively photo rotation",
                "Every 15 minutes · Balanced ambient cycle",
                "Every 30 minutes · Gentle background shift",
                "Every 1 hour · Subtle hourly refresh",
                "Every 6 hours · Morning, afternoon & night"};
        int sel=0;
        for(int i=0;i<mins.length;i++) if(mins[i]==cur) { sel=i; break; }
        choice("Wallpaper slideshow",opts,sel,idx->{
            int picked=mins[idx];
            store.setNumber("wallpaper_interval_min",picked);
            List<File> files=PhoneDropServer.listSavedFiles(this,store);
            if(picked>0 && !files.isEmpty()) {
                store.set("background","photo");
                if(store.value("background_image","").isEmpty()) {
                    store.set("background_image",files.get(0).toURI().toString());
                }
            }
            resetWallpaperSlideshowTimer();
            render();
            if(picked>0 && files.size()<2) toast("Send at least 2 phone wallpapers to start rotating");
            back.run();
        },back);
    }
    private void wallpaperGalleryMenu(Runnable back) {
        List<File> files=PhoneDropServer.listSavedFiles(this,store);
        if(files.isEmpty()) { phoneDropPanel(back); return; }
        String activeBg=store.value("background","ember");
        String activeUri="photo".equals(activeBg)?store.value("background_image",""):"";
        List<String> labels=new ArrayList<>(); List<Runnable> actions=new ArrayList<>();
        add(labels,actions,"§SAVED PHONE WALLPAPERS ("+files.size()+")",null);
        for(int i=0;i<files.size();i++) {
            final File f=files.get(i);
            final String uri=f.toURI().toString();
            boolean isAct=uri.equals(activeUri);
            long kb=Math.max(1,f.length()/1024);
            String title="Wallpaper "+(i+1)+" · "+(isAct?"Live on TV ("+kb+" KB)":kb+" KB");
            add(labels,actions,(isAct?"✓ ":"○ ")+title,()->{
                store.set("background_image",uri);
                store.set("background","photo");
                resetWallpaperSlideshowTimer();
                render();
                wallpaperGalleryMenu(back);
            });
        }
        add(labels,actions,"§MANAGE COLLECTION",null);
        add(labels,actions,"@wifi|Add more from phone (QR)",()->phoneDropPanel(()->wallpaperGalleryMenu(back)));
        if(!activeUri.isEmpty()) {
            add(labels,actions,"Delete active wallpaper",()->{
                deleteWallpaperUri(activeUri);
                if(PhoneDropServer.listSavedFiles(this,store).isEmpty()) back.run();
                else wallpaperGalleryMenu(back);
            });
        }
        add(labels,actions,"Clear all phone wallpapers",()->{
            for(File f:PhoneDropServer.listSavedFiles(this,store)) {
                try { f.delete(); } catch(Exception ignored) {}
            }
            store.list("wallpaper_gallery",new ArrayList<>());
            if("photo".equals(store.value("background","ember"))) store.set("background","ember");
            store.set("background_image","");
            resetWallpaperSlideshowTimer();
            render();
            toast("Cleared saved phone wallpapers");
            back.run();
        });
        menu("Phone gallery",labels,actions,back);
    }
    private void deleteWallpaperUri(String uri) {
        if(uri==null || uri.isEmpty()) return;
        try {
            File f=new File(Uri.parse(uri).getPath());
            String wpDir=PhoneDropServer.wallpapersDir(this).getCanonicalPath()+File.separator;
            if(f.exists() && f.getCanonicalPath().startsWith(wpDir)) f.delete();
        } catch(Exception ignored) {}
        List<File> remaining=PhoneDropServer.listSavedFiles(this,store);
        if(uri.equals(store.value("background_image",""))) {
            if(!remaining.isEmpty()) {
                store.set("background_image",remaining.get(0).toURI().toString());
                store.set("background","photo");
            } else {
                store.set("background_image","");
                if("photo".equals(store.value("background","ember"))) store.set("background","ember");
            }
        }
        resetWallpaperSlideshowTimer();
        render();
    }
    private void phoneDropPanel(Runnable back) {
        stopPhoneDrop();
        LinearLayout box=new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        // Live connection status pill
        LinearLayout statusPill=new LinearLayout(this);
        statusPill.setOrientation(LinearLayout.HORIZONTAL);
        statusPill.setGravity(Gravity.CENTER_VERTICAL);
        statusPill.setPadding(px(14),px(10),px(14),px(10));
        statusPill.setBackground(shapeRadius(0x2238BDF8,0x447DD3FC,1,14));
        View dot=new View(this);
        dot.setBackground(shapeRadius(0xFF38BDF8,0,0,5));
        LinearLayout.LayoutParams dp=new LinearLayout.LayoutParams(px(10),px(10));
        dp.rightMargin=px(10);
        statusPill.addView(dot,dp);
        TextView statusTxt=text("Waiting for phone on same Wi-Fi…",17,0xFFE0F2FE);
        statusTxt.setTypeface(medium);
        statusPill.addView(statusTxt,new LinearLayout.LayoutParams(-1,-2));
        box.addView(statusPill,new LinearLayout.LayoutParams(-1,-2));

        // Live preview strip reference (updated on upload)
        final ImageView[] thumbRef={null};
        final TextView[] liveCaptionRef={null};

        PhoneDropServer server=new PhoneDropServer(this,store,new PhoneDropServer.Listener() {
            @Override public void onPhoneConnected() {
                if(destroyed || phoneDrop==null) return;
                statusPill.setBackground(shapeRadius(0x2410B981,0x5534D399,1,14));
                dot.setBackground(shapeRadius(0xFF34D399,0,0,5));
                statusTxt.setTextColor(0xFFA7F3D0);
                statusTxt.setText("Phone connected · Choose photos!");
            }
            @Override public void onWallpaperReceived(String fileUri,int sessionCount) {
                if(destroyed) return;
                store.set("background_image",fileUri);
                store.set("background","photo");
                resetWallpaperSlideshowTimer();
                render();
                statusPill.setBackground(shapeRadius(0x2810B981,0x6634D399,1,14));
                dot.setBackground(shapeRadius(0xFF34D399,0,0,5));
                statusTxt.setTextColor(0xFFD1FAE5);
                statusTxt.setText(sessionCount==1?"Live on TV · 1 wallpaper received":"Live on TV · "+sessionCount+" wallpapers received");
                if(thumbRef[0]!=null) images.show(thumbRef[0],fileUri,px(220),px(124));
                if(liveCaptionRef[0]!=null) {
                    int total=PhoneDropServer.listSavedFiles(MainActivity.this,store).size();
                    liveCaptionRef[0].setText("Applied live!\n"+total+" saved in TV gallery");
                }
            }
            @Override public void onWallpaperSelected(String fileUri) {
                if(destroyed) return;
                store.set("background_image",fileUri);
                store.set("background","photo");
                resetWallpaperSlideshowTimer();
                render();
                if(thumbRef[0]!=null) images.show(thumbRef[0],fileUri,px(220),px(124));
                if(liveCaptionRef[0]!=null) liveCaptionRef[0].setText("Switched from phone!");
            }
            @Override public void onWallpaperDeleted(String fileUri) {
                if(destroyed) return;
                deleteWallpaperUri(fileUri);
                int total=PhoneDropServer.listSavedFiles(MainActivity.this,store).size();
                if(liveCaptionRef[0]!=null) liveCaptionRef[0].setText(total+" saved in TV gallery");
            }
            @Override public void onSlideshowChanged(int minutes) {
                if(destroyed) return;
                store.setNumber("wallpaper_interval_min",minutes);
                resetWallpaperSlideshowTimer();
                if(liveCaptionRef[0]!=null) {
                    liveCaptionRef[0].setText(minutes<=0?"Slideshow: Off":"Slideshow: Every "+slideshowIntervalLabel(minutes));
                }
            }
        });

        if(!server.start()) {
            TextView err=text("Could not find an active Wi-Fi or Ethernet connection on this TV.\n\nConnect your TV to the same home network as your phone, then open Send from phone again.",19,0xEAFFFFFF);
            err.setLineSpacing(px(4),1f);
            LinearLayout.LayoutParams ep=new LinearLayout.LayoutParams(-1,-2);
            ep.topMargin=px(16);
            box.addView(err,ep);
            showBladePanel("Phone Drop",box,"Wi-Fi settings",()->{
                closeBlade();
                openSystemIntent(Settings.ACTION_WIFI_SETTINGS,Settings.ACTION_WIRELESS_SETTINGS);
            },back,null);
            return;
        }
        phoneDrop=server;

        // Crisp high-contrast QR stage
        FrameLayout qrStage=new FrameLayout(this);
        qrStage.setBackground(glassPanelDrawable(qrStage,20f,false));
        qrStage.setPadding(px(16),px(16),px(16),px(16));
        FrameLayout whiteCard=new FrameLayout(this);
        whiteCard.setBackground(shapeRadius(0xFFFFFFFF,0,0,16));
        whiteCard.setPadding(px(10),px(10),px(10),px(10));
        ImageView qrImg=new ImageView(this);
        qrImg.setScaleType(ImageView.ScaleType.FIT_CENTER);
        qrImg.setImageBitmap(QrCode.encodeBitmap(server.getUrl(),px(236),3,0xFF0B101A,0xFFFFFFFF));
        whiteCard.addView(qrImg,new FrameLayout.LayoutParams(px(236),px(236),Gravity.CENTER));
        qrStage.addView(whiteCard,new FrameLayout.LayoutParams(-2,-2,Gravity.CENTER));
        LinearLayout.LayoutParams qp=new LinearLayout.LayoutParams(-1,-2);
        qp.topMargin=px(14); qp.bottomMargin=px(14);
        box.addView(qrStage,qp);

        // Concise instructions + fallback URL
        TextView steps=text("1. Point your phone camera at the QR code\n2. Pick photos from Camera Roll, Google Photos, or Drive — zero login!",17,0xEAFFFFFF);
        steps.setLineSpacing(px(3),1f);
        box.addView(steps,new LinearLayout.LayoutParams(-1,-2));

        TextView urlPill=text(server.getUrl(),14,accent());
        urlPill.setTypeface(medium);
        urlPill.setPadding(px(12),px(8),px(12),px(8));
        urlPill.setBackground(shapeRadius(0x16FFFFFF,0x24FFFFFF,1,10));
        LinearLayout.LayoutParams up=new LinearLayout.LayoutParams(-1,-2);
        up.topMargin=px(10);
        box.addView(urlPill,up);

        // Live thumbnail strip
        LinearLayout liveRow=new LinearLayout(this);
        liveRow.setOrientation(LinearLayout.HORIZONTAL);
        liveRow.setGravity(Gravity.CENTER_VERTICAL);
        liveRow.setPadding(px(10),px(10),px(12),px(10));
        liveRow.setBackground(shapeRadius(0x14FFFFFF,0x22FFFFFF,1,14));
        ImageView thumb=new ImageView(this);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumb.setClipToOutline(true);
        thumb.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View v,android.graphics.Outline o) { o.setRoundRect(0,0,v.getWidth(),v.getHeight(),px(9)); }
        });
        thumb.setBackground(shapeRadius(0xFF141C2B,0x28FFFFFF,1,9));
        String curImg=store.value("background_image","");
        if(!curImg.isEmpty()) images.show(thumb,curImg,px(160),px(90));
        thumbRef[0]=thumb;
        LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(px(116),px(66));
        tp.rightMargin=px(12);
        liveRow.addView(thumb,tp);

        int existingCount=PhoneDropServer.listSavedFiles(this,store).size();
        TextView liveCap=text(existingCount>0
                ?"Watch behind this blade as photos apply live ("+existingCount+" saved)"
                :"Private local Wi-Fi link closes as soon as you leave this screen",15,muted());
        liveCap.setLineSpacing(px(2),1f);
        liveCaptionRef[0]=liveCap;
        liveRow.addView(liveCap,new LinearLayout.LayoutParams(0,-2,1f));

        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);
        lp.topMargin=px(12);
        box.addView(liveRow,lp);

        showBladePanel("Phone Drop",box,"Done",this::closeBlade,()->{
            stopPhoneDrop();
            if(back!=null) back.run();
        },null);
    }
    private void treatmentStyleChoice(Runnable back) {
        String[] keys={"dim","blur","vignette","scrim","studio"};
        String[] titles={
                "Classic Dim · Uniform dark veil",
                "Frosted Silk · Optical blur, keeps wallpaper color",
                "Cinema Vignette · Clear center, edge & dock shadow",
                "Horizon Scrim · Open sky, dark stage for apps",
                "Studio Glass · Hybrid soft blur & vignette"
        };
        int current=0;
        String curKey=backgroundTreatment();
        for(int i=0;i<keys.length;i++) if(keys[i].equals(curKey)) { current=i; break; }
        choice("Treatment style",titles,current,idx->{
            store.set("background_treatment",keys[idx]);
            if(store.number("dimming",0)==0) store.setNumber("dimming",40);
            render();
            back.run();
        },back);
    }
    private void treatmentIntensityDialog(Runnable back) {
        LinearLayout box=new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        final int initial=treatmentIntensity();
        TextView value=text("Intensity · "+initial+"%",22,foreground()); value.setTypeface(medium);
        box.addView(value);
        String subDesc="dim".equals(backgroundTreatment())?"Uniform darkness behind text and tiles":
                "blur".equals(backgroundTreatment())?"Optical diffusion softness":
                "vignette".equals(backgroundTreatment())?"Perimeter and dock shadow depth":
                "scrim".equals(backgroundTreatment())?"Content stage gradient depth":"Hybrid diffusion and vignette depth";
        TextView sub=text(subDesc,18,muted());
        LinearLayout.LayoutParams subParams=new LinearLayout.LayoutParams(-1,-2); subParams.topMargin=px(6); subParams.bottomMargin=px(20);
        box.addView(sub,subParams);
        SeekBar slider=new SeekBar(this); slider.setMax(100); slider.setProgress(initial);
        slider.setKeyProgressIncrement(5); slider.setFocusableInTouchMode(true);
        slider.setProgressTintList(android.content.res.ColorStateList.valueOf(accent()));
        slider.setThumbTintList(android.content.res.ColorStateList.valueOf(accent()));
        box.addView(slider,new LinearLayout.LayoutParams(-1,px(64)));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar bar,int progress,boolean user){
                value.setText("Intensity · "+progress+"%");
                if(user) {
                    overrideTreatmentIntensity=progress;
                    syncTreatmentLayers();
                }
            }
            public void onStartTrackingTouch(SeekBar bar){}
            public void onStopTrackingTouch(SeekBar bar){}
        });
        showBladePanel("Treatment intensity",box,"Save",()->{
            overrideTreatmentIntensity=-1;
            store.setNumber("dimming",slider.getProgress());
            render();
            back.run();
        },()->{
            overrideTreatmentIntensity=-1;
            syncTreatmentLayers();
            back.run();
        },slider);
    }
    private void dimmingDialog() { treatmentIntensityDialog(this::backgroundMenu); }
    private void dimmingDialog(Runnable back) { treatmentIntensityDialog(back); }
    private Typeface clockTypeface() {
        String style=store.value("clock_style","modern");
        if("editorial".equals(style)) return Typeface.SERIF;
        if("condensed".equals(style)) return Typeface.create("sans-serif-condensed",Typeface.BOLD);
        if("mono".equals(style)) return Typeface.MONOSPACE;
        if("thin".equals(style)) return Typeface.create("sans-serif-light",Typeface.NORMAL);
        return medium!=null?medium:Typeface.create("sans-serif-medium",Typeface.NORMAL);
    }
    private String clockStyleName() {
        String s=store.value("clock_style","modern");
        return "editorial".equals(s)?"Editorial Serif":
                "condensed".equals(s)?"Cinema Condensed":
                "mono".equals(s)?"Retro Monospace":
                "thin".equals(s)?"Ultra Light":"Modern Clean";
    }
    private View buildGlanceWidget() {
        LinearLayout pill=new LinearLayout(this);
        pill.setSoundEffectsEnabled(!muteSystem());
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setId(View.generateViewId());
        pill.setFocusable(true);pill.setClickable(true);pill.setFocusableInTouchMode(true);
        pill.setPadding(px(18),px(10),px(22),px(10));
        pill.setBackground(glassControlDrawable(pill,false,-1f,accent(),true,false));

        WeatherService.WeatherInfo info=latestWeather!=null?latestWeather:WeatherService.getCached(store);
        latestWeather=info;

        glanceIconView=new TextView(this);
        glanceIconView.setText(info!=null?info.emoji:"🌤️");
        glanceIconView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,px(24));
        glanceIconView.setPadding(0,0,px(10),0);
        pill.addView(glanceIconView);

        glanceTempView=text(info!=null?info.tempDisplay():"--°",22,0xF2FFFFFF);
        glanceTempView.setTypeface(medium);
        pill.addView(glanceTempView);

        glanceCondView=text(info!=null?("  ·  "+info.conditionText):"  ·  Weather",17,muted());
        pill.addView(glanceCondView);

        glanceCityView=text(info!=null&&!info.city.isEmpty()?("  ·  "+info.city):"",17,accent());
        glanceCityView.setTypeface(medium);
        pill.addView(glanceCityView);

        pill.setOnFocusChangeListener((v,has)->{
            pill.setBackground(glassControlDrawable(pill,has,-1f,accent(),true,false));
            glanceTempView.setTextColor(has?accent():0xF2FFFFFF);
            pill.animate().scaleX(has&&!instantFocus()?1.05f:1f).scaleY(has&&!instantFocus()?1.05f:1f).translationZ(has?px(12):0).setDuration(focusDuration(130)).start();
            if(has && sfx!=null) sfx.playFocus(v,screenWidth,1);
        });

        pill.setOnClickListener(v->{
            if(sfx!=null) sfx.playSelect(v,screenWidth);
            glanceDetailsBlade();
        });
        pill.setOnKeyListener((v,code,ev)->{
            if(ev.getAction()==KeyEvent.ACTION_DOWN) {
                if(code==KeyEvent.KEYCODE_DPAD_DOWN) {
                    if(!topPillViews.isEmpty()) topPillViews.get(0).requestFocus();
                    else if(focusedFavorite!=null) focusedFavorite.requestFocus();
                    else if(!appViews.isEmpty()) appViews.get(0).requestFocus();
                    else if(!dockViews.isEmpty()) dockViews.get(0).requestFocus();
                    return true;
                }
                if(code==KeyEvent.KEYCODE_DPAD_CENTER||code==KeyEvent.KEYCODE_ENTER) {
                    glanceDetailsBlade();
                    return true;
                }
            }
            return false;
        });

        return pill;
    }
    private void updateGlanceWeather(WeatherService.WeatherInfo info) {
        if(info==null) return;
        latestWeather=info;
        if(glanceIconView!=null) glanceIconView.setText(info.emoji);
        if(glanceTempView!=null) glanceTempView.setText(info.tempDisplay());
        if(glanceCondView!=null) glanceCondView.setText("  ·  "+info.conditionText);
        if(glanceCityView!=null) glanceCityView.setText(!info.city.isEmpty()?("  ·  "+info.city):"");
    }
    private void glanceDetailsBlade() {
        WeatherService.WeatherInfo info=latestWeather!=null?latestWeather:WeatherService.getCached(store);
        List<String> labels=new ArrayList<>();
        List<Runnable> actions=new ArrayList<>();

        String loc=info!=null&&!info.city.isEmpty()?info.city:"Auto-detected location";
        String curWeather=info!=null?(info.emoji+" "+info.tempDisplay()+" · "+info.conditionText):"Loading weather...";
        String range=info!=null?(info.rangeDisplay()+" · Wind "+Math.round(info.windSpeed)+" km/h"):"Connecting to Open-Meteo";

        add(labels,actions,"§LIVE WEATHER",null);
        add(labels,actions," LIVE WEATHER",null);
        add(labels,actions,"@display|"+loc+" · "+curWeather+"\n"+range,()->weatherLocationDialog(this::glanceDetailsBlade));

        if(info!=null && info.forecast!=null && !info.forecast.isEmpty()) {
            add(labels,actions," 5-DAY FORECAST",null);
            for(WeatherService.ForecastDay d:info.forecast) {
                add(labels,actions,d.dayName+" · "+d.emoji+" "+d.conditionText+": "+Math.round(d.maxTemp)+"° / "+Math.round(d.minTemp)+"°",null);
            }
        }

        add(labels,actions,"§CONTROLS & STYLES",null);
        String unit="f".equalsIgnoreCase(store.value("temp_unit","c"))?"Fahrenheit (°F)":"Celsius (°C)";
        add(labels,actions,"Temperature unit: "+unit,()->{
            boolean isC=!"f".equalsIgnoreCase(store.value("temp_unit","c"));
            store.set("temp_unit",isC?"f":"c");
            if(weatherService!=null) weatherService.fetch(store,true,this::updateGlanceWeather);
            render();
            glanceDetailsBlade();
        });
        add(labels,actions,"Clock style: "+clockStyleName(),()->clockStyleChoice(this::glanceDetailsBlade));
        add(labels,actions,"Location: "+(store.value("weather_custom_city","").isEmpty()?"Auto (IP detection)":store.value("weather_custom_city","")),()->weatherLocationDialog(this::glanceDetailsBlade));
        add(labels,actions,"Refresh weather now",()->{
            toast("Refreshing weather...");
            if(weatherService!=null) {
                weatherService.fetch(store,true,i->{
                    updateGlanceWeather(i);
                    glanceDetailsBlade();
                });
            }
        });

        menu("Quick Glance",labels,actions,this::closeBlade);
    }
    private void clockStyleChoice(Runnable back) {
        String[] keys={"modern","editorial","condensed","mono","thin"};
        String[] titles={
                "Modern Clean · Contemporary geometric (Outfit)",
                "Editorial Serif · Luxury magazine typography",
                "Cinema Condensed · Tall cinematic architectural",
                "Retro Monospace · Digital timepiece terminal aesthetic",
                "Ultra Light · Airy Scandinavian minimalism"
        };
        int current=0;
        String curKey=store.value("clock_style","modern");
        for(int i=0;i<keys.length;i++) if(keys[i].equals(curKey)) { current=i; break; }
        choice("Clock style",titles,current,idx->{
            store.set("clock_style",keys[idx]);
            render();
            back.run();
        },back);
    }
    private void weatherLocationDialog(Runnable back) {
        LinearLayout box=new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        TextView sub=text("Enter a city name (e.g. London, Tokyo, Nairobi) or leave empty for auto-detect:",18,muted());
        LinearLayout.LayoutParams subParams=new LinearLayout.LayoutParams(-1,-2); subParams.bottomMargin=px(16);
        box.addView(sub,subParams);
        EditText edit=new EditText(this);
        edit.setText(store.value("weather_custom_city",""));
        edit.setHint("e.g. London, New York, Tokyo");
        edit.setSingleLine(true);
        edit.setTextColor(foreground()); edit.setHintTextColor(muted());
        edit.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,px(22));
        edit.setPadding(px(18),px(14),px(18),px(14));
        edit.setBackground(shapeRadius(themed(0x1AFFFFFF,0xBB182435),accent(),2,12));
        box.addView(edit,new LinearLayout.LayoutParams(-1,px(60)));
        showBladePanel("Weather Location",box,"Save",()->{
            String val=edit.getText().toString().trim();
            store.set("weather_custom_city",val);
            if(weatherService!=null) weatherService.fetch(store,true,this::updateGlanceWeather);
            render();
            back.run();
        },back,edit);
    }
    private void recommendationMenu() {
        String access=Build.VERSION.SDK_INT<23||checkSelfPermission(TV_LISTINGS_PERMISSION)==PackageManager.PERMISSION_GRANTED
                ?"TV listings: Allowed":"TV listings: Request access";
        menu("Recommendations",Arrays.asList(
                "§DISPLAY",
                "All recommendations: "+onOff(store.flag("recommendations_master")),
                "Card size: "+recommendationSizeName(recommendationSize()),
                "§SOURCES & ACCESS",
                "Manage per-app feeds",
                access,
                "Refresh shared content",
                "How recommendations work"),
                Arrays.asList(
                        null,
                        ()->{store.setFlag("recommendations_master",!store.flag("recommendations_master"));render();recommendationMenu();},
                        this::recommendationSizeDialog,
                        null,
                        ()->chooseApp("App recommendations",false,this::toggleAppRecommendations,this::recommendationMenu),
                        this::requestListings,
                        ()->{recommendations.clear();updateHeader();updateRecommendationRows();toast("Shared content will be checked again");recommendationMenu();},
                        ()->info("Recommendations are optional","Each enabled app keeps its own recommendation row on Home while shared content is available. Moving focus to another app does not hide that row.\n\nThe master switch pauses all rows and the featured banner while remembering your choices. Enable individual apps from their hold-OK menu. Some apps or TVs do not share this data. No subscription details are collected.",this::recommendationMenu)),this::settingsMenu);
    }
    private void recommendationSizeDialog() {
        final int[] selected={recommendationSize()};
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);
        TextView value=text("",22,foreground());value.setTypeface(medium);box.addView(value);
        FrameLayout preview=new FrameLayout(this);
        LinearLayout.LayoutParams previewParams=new LinearLayout.LayoutParams(-1,px(280));previewParams.topMargin=px(12);
        box.addView(preview,previewParams);
        LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);
        FrameLayout picture=new FrameLayout(this);picture.setBackground(shapeRadius(Color.BLACK,0x33FFFFFF,1,14));picture.setClipToOutline(true);
        TextView placeholder=text("Preview artwork",19,muted());placeholder.setGravity(Gravity.CENTER);
        picture.addView(placeholder,new FrameLayout.LayoutParams(-1,-1));
        final int[] sampleDimensions={16,9};final Runnable[] refresh={null};
        ImageView image=new ImageView(this) {
            @Override public void setImageBitmap(android.graphics.Bitmap bitmap) {
                super.setImageBitmap(bitmap);
                if(bitmap!=null){sampleDimensions[0]=bitmap.getWidth();sampleDimensions[1]=bitmap.getHeight();if(refresh[0]!=null)refresh[0].run();}
            }
        };image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        picture.addView(image,new FrameLayout.LayoutParams(-1,-1));card.addView(picture);
        String sampleTitle="Example recommendation";
        for(AppCatalog.App app:apps) {
            RecommendationRepository.Feed feed=recommendations.cached(app.pkg);
            if(feed!=null && feed.featured()!=null) {
                RecommendationRepository.Item item=feed.featured();sampleTitle=item.title;
                placeholder.setVisibility(View.GONE);images.show(image,item.artwork,px(380),px(220));break;
            }
        }
        TextView title=text(sampleTitle,19,foreground());title.setSingleLine(true);title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setGravity(Gravity.CENTER_VERTICAL);title.setPadding(px(4),0,px(4),0);card.addView(title);
        preview.addView(card,new FrameLayout.LayoutParams(px(256),px(192),Gravity.CENTER));
        SeekBar slider=new SeekBar(this);slider.setFocusableInTouchMode(true);slider.setMax(3);slider.setKeyProgressIncrement(1);slider.setProgress(selected[0]);
        slider.setProgressTintList(android.content.res.ColorStateList.valueOf(accent()));
        slider.setThumbTintList(android.content.res.ColorStateList.valueOf(accent()));
        Runnable update=()->{
            int size=selected[0],height=recommendationWidth(size)*9/16;
            int width=artworkWidth(sampleDimensions[0],sampleDimensions[1],px(height),px(380));
            value.setText(recommendationSizeName(size)+" · "+height+"px tall");
            slider.setContentDescription("Recommendation size: "+recommendationSizeName(size));
            card.setLayoutParams(new FrameLayout.LayoutParams(width,px(height+recommendationTitleHeight(size)),Gravity.CENTER));
            picture.setLayoutParams(new LinearLayout.LayoutParams(-1,px(height)));
            title.setLayoutParams(new LinearLayout.LayoutParams(-1,px(recommendationTitleHeight(size))));
            title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,px(Math.min(23,recommendationTextSize(size))));
        };
        refresh[0]=update;
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar bar,int progress,boolean user){selected[0]=progress;update.run();}
            public void onStartTrackingTouch(SeekBar bar){}
            public void onStopTrackingTouch(SeekBar bar){}
        });
        box.addView(slider,new LinearLayout.LayoutParams(-1,px(60)));
        box.addView(text("Left / Right to preview. Save applies to all rows.",18,muted()));
        update.run();
        showBladePanel("Card size",box,"Save",()->{
            store.setNumber("recommendation_size",selected[0]);render();recommendationMenu();
        },this::recommendationMenu,slider);
    }
    private void bannerMenu() {
        String source=store.value("featured_source","");
        String status=!store.flag("featured")?"Status: Clock (banner off)":
                source.isEmpty()?"Status: Clock (choose a source)":
                !store.flag("recommendations_master")?"Status: Clock (recommendations paused)":
                !store.recommendations(source)?"Status: Clock (source feed off)":
                recommendations.cached(source)==null?"Status: Checking shared content":
                "Status: "+recommendations.cached(source).status;
        menu("Featured banner",Arrays.asList(
                "§HERO DISPLAY",
                "Featured banner: "+onOff(store.flag("featured")),
                "Source: "+(source.isEmpty()?"None":name(source)),
                "Source feed: "+(source.isEmpty()?"Select app":onOff(store.recommendations(source))),
                "Expanded height: "+(store.number("hero_height",0)==0?"Automatic":store.number("hero_height",0)+"%"),
                "§PLAYBACK & MOTION",
                "Title rotation: "+onOff(store.flag("hero_rotate")),
                "Video previews: "+onOff(store.flag("hero_video",true)),
                "Preview sound: "+onOff(store.flag("hero_sound")),
                "§FALLBACK",
                status,
                "Reset to clock"),
                Arrays.asList(
                        null,
                        ()->{store.setFlag("featured",!store.flag("featured"));render();bannerMenu();},
                        ()->chooseApp("Choose banner source",false,pkg->{store.set("featured_source",pkg);render();bannerMenu();},this::bannerMenu),
                        ()->{if(source.isEmpty())chooseApp("Choose banner source",false,pkg->{store.set("featured_source",pkg);render();bannerMenu();},this::bannerMenu);else toggleAppRecommendations(source);},
                        ()->{int height=store.number("hero_height",0);store.setNumber("hero_height",height==0?60:height==60?75:height==75?85:0);render();bannerMenu();},
                        null,
                        ()->{store.setFlag("hero_rotate",!store.flag("hero_rotate"));render();bannerMenu();},
                        ()->{store.setFlag("hero_video",!store.flag("hero_video",true));render();bannerMenu();},
                        ()->{store.setFlag("hero_sound",!store.flag("hero_sound"));render();bannerMenu();},
                        null,
                        ()->info("Clock fallback","The clock appears when no source is selected, recommendations are disabled, the app shares no accessible content, or the artwork cannot load. Your source choice is kept when you turn the banner off.",this::bannerMenu),
                        ()->{store.setFlag("featured",false);store.set("featured_source","");render();bannerMenu();}),this::settingsMenu);
        if (!source.isEmpty() && recommendations.cached(source)==null) recommendations.load(source,feed->{ /* Cache availability without changing the user's choices. */ });
    }
    private void inputMenu() {
        TvInputManager manager=(TvInputManager)getSystemService(TV_INPUT_SERVICE);
        List<String> labels=new ArrayList<>();List<Runnable> actions=new ArrayList<>();
        if(manager!=null)for(TvInputInfo input:manager.getTvInputList()) {
            if(input.getType()!=TvInputInfo.TYPE_HDMI||input.getParentId()!=null)continue;
            String key="input."+input.getId();
            add(labels,actions,inputName(input)+": "+onOff(store.flag(key,!input.isHidden(this))),()->{
                store.setFlag(key,!store.flag(key,!input.isHidden(this)));render();inputMenu();
            });
        }
        if(labels.isEmpty()){info("HDMI inputs","This device does not expose built-in HDMI inputs. The app and settings dock remain available.",this::settingsMenu);return;}
        menu("HDMI inputs",labels,actions,this::settingsMenu);
    }
    private ResolveInfo defaultHome(){
        return getPackageManager().resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),PackageManager.MATCH_DEFAULT_ONLY);
    }
    private boolean isDefaultHome(){ResolveInfo home=defaultHome();return home!=null&&getPackageName().equals(home.activityInfo.packageName);}
    private boolean holdsHomeRole(){
        if(Build.VERSION.SDK_INT<29)return false;
        RoleManager roles=getSystemService(RoleManager.class);
        return roles!=null&&roles.isRoleAvailable(RoleManager.ROLE_HOME)&&roles.isRoleHeld(RoleManager.ROLE_HOME);
    }
    private String defaultHomeName(){
        ResolveInfo home=defaultHome();
        if(home==null||"android".equals(home.activityInfo.packageName))return "Not selected";
        return getPackageManager().getApplicationLabel(home.activityInfo.applicationInfo).toString();
    }
    private void defaultLauncherMenu(){
        boolean selected=isDefaultHome();
        menu("Default launcher",Arrays.asList(
                "§HOME ROLE",
                "Current default: "+(selected?"Luma Home":defaultHomeName()),
                "Set Luma Home as default",
                "Switch to another launcher",
                "§RECOVERY",
                "Open another installed home",
                "Recovery guidance"),
                Arrays.asList(
                        null,
                        ()->info("Default home",selected?"Home currently resolves to Luma Home.":"Luma Home is running as a preview. Your existing default launcher has been retained.",this::defaultLauncherMenu),
                        ()->{choosingLuma=true;if(isDefaultHome())finishLauncherSetup();else requestDefaultHome();},
                        ()->{choosingLuma=false;launcherManager(false);},
                        null,
                        this::otherHome,
                        ()->info("Recovery","Device settings remain available in Luma Settings.\n\nYou can open another enabled launcher here at any time. Restoring an app previously disabled through ADB requires the approved computer-side undo commands. Luma Home cannot silently re-enable protected system apps.",this::defaultLauncherMenu)),this::settingsMenu);
    }
    private void finishLauncherSetup(){
        menu("Use Luma Home",Arrays.asList("Disable other launchers","Done"),
                Arrays.asList(()->launcherManager(true),this::defaultLauncherMenu),this::defaultLauncherMenu);
    }
    private void launcherManager(boolean disable){
        disablingLaunchers=disable;
        List<String> labels=new ArrayList<>();List<Runnable> actions=new ArrayList<>();
        java.util.HashSet<String> seen=new java.util.HashSet<>();
        Intent home=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        for(ResolveInfo candidate:getPackageManager().queryIntentActivities(home,PackageManager.MATCH_DISABLED_COMPONENTS)){
            String pkg=candidate.activityInfo.packageName;
            if(pkg.equals(getPackageName())||pkg.equals("android")||pkg.equals("com.android.tv.settings")||pkg.contains("setupwizard")||pkg.contains("setupwraith")||candidate.activityInfo.name.contains("Recovery")||!seen.add(pkg))continue;
            boolean enabled=candidate.activityInfo.applicationInfo.enabled;
            String label=getPackageManager().getApplicationLabel(candidate.activityInfo.applicationInfo).toString();
            if(disable&&!enabled)continue;
            add(labels,actions,(disable?"Disable ":!enabled?"Enable ":"")+label,()->{
                if(!disable&&enabled){choosingLuma=false;chooseDefaultLauncher();return;}
                try{
                    startActivityForResult(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+pkg)),MANAGE_LAUNCHER);
                    toast("Choose "+(disable?"Disable":"Enable")+" in the TV app settings, if available.");
                }catch(Exception error){toast("This TV does not allow changing that launcher.");}
            });
        }
        if(labels.isEmpty()){
            if(disable)toast("No other enabled launchers.");else toast("No other launchers found.");
            defaultLauncherMenu();return;
        }
        menu(disable?"Disable other launchers":"Switch launcher",labels,actions,this::defaultLauncherMenu);
    }
    private void requestDefaultHome(){
        if(Build.VERSION.SDK_INT>=29){
            RoleManager roles=getSystemService(RoleManager.class);
            if(roles!=null&&roles.isRoleAvailable(RoleManager.ROLE_HOME)&&!roles.isRoleHeld(RoleManager.ROLE_HOME)){
                try{startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_HOME),DEFAULT_HOME);return;}
                catch(Exception ignored){}
            }
        }
        chooseDefaultLauncher();
    }
    private void chooseDefaultLauncher(){
        for(String action:new String[]{Settings.ACTION_HOME_SETTINGS,Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS}){
            Intent intent=new Intent(action);
            if(intent.resolveActivity(getPackageManager())!=null){
                try{startActivityForResult(intent,DEFAULT_HOME);return;}catch(Exception ignored){}
            }
        }
        ResolveInfo home=defaultHome();
        String pkg=home==null||"android".equals(home.activityInfo.packageName)?getPackageName():home.activityInfo.packageName;
        Intent details=new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+pkg));
        try{
            startActivityForResult(details,DEFAULT_HOME);
            toast("If available, clear the current Home defaults, then press Home to choose a launcher.");
        }catch(Exception error){info("Default launcher unavailable","This TV does not expose a supported default-home selector. You can still open Luma Home or another launcher manually. No app has been disabled or default changed.",this::defaultLauncherMenu);}
    }
    private void setupMenu() { defaultLauncherMenu(); }
    private void otherHome() {
        List<String> labels=new ArrayList<>();List<Runnable> actions=new ArrayList<>();
        for(ResolveInfo info:getPackageManager().queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),0)) {
            if(info.activityInfo.packageName.equals(getPackageName())||info.activityInfo.packageName.equals("com.android.tv.settings"))continue;
            Intent intent=new Intent(Intent.ACTION_MAIN).setClassName(info.activityInfo.packageName,info.activityInfo.name);
            add(labels,actions,getPackageManager().getApplicationLabel(info.activityInfo.applicationInfo).toString(),()->startSafely(intent));
        }
        if(labels.isEmpty())info("Other home screens","No other enabled home screen is available. Use the saved computer-side recovery commands if you previously disabled one.",this::defaultLauncherMenu);
        else menu("Open another home",labels,actions,this::defaultLauncherMenu);
    }
    private void aboutMenu() {
        menu("About Luma Home",Arrays.asList("Version: "+versionName(),"Your privacy","Open-source license"),Arrays.asList(
                ()->info("Luma Home","A personal Android TV home screen. Home apps, visual choices, recommendation switches and banner source are stored locally on this TV.",this::aboutMenu),
                ()->info("Local preferences","Luma Home has no analytics or account sign-in. Optional recent history records only apps launched here and can be cleared. Imported images are copied locally. Shared artwork is fetched only for enabled content; streaming apps retain their own accounts and subscriptions.",this::aboutMenu),
                ()->{try(java.io.InputStream stream=getAssets().open("LICENSE")){java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();byte[] b=new byte[2048];int n;while((n=stream.read(b))!=-1)bytes.write(b,0,n);info("MIT license",bytes.toString("UTF-8"),this::aboutMenu);}catch(Exception e){toast("License could not be opened");}}),this::settingsMenu);
    }
    private void pickImage(String target) {
        Intent pick=new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
        pick.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        List<ResolveInfo> handlers=getPackageManager().queryIntentActivities(pick,0);
        List<String> labels=new ArrayList<>();List<Runnable> actions=new ArrayList<>();
        for(ResolveInfo handler:handlers) {
            if(handler.activityInfo.packageName.contains("frameworkpackagestubs"))continue;
            add(labels,actions,handler.loadLabel(getPackageManager()).toString(),()->{
                imageTarget=target;
                Intent explicit=new Intent(pick).setClassName(handler.activityInfo.packageName,handler.activityInfo.name);
                try{startActivityForResult(explicit,PICK_IMAGE);}catch(Exception e){toast("The file picker could not be opened");}
            });
        }
        if(actions.isEmpty()){info("Choose a photo","This TV has no working image picker. You can use the built-in backgrounds, or install a TV file picker through your normal approved process. No storage permission is required by Luma Home.",this::backgroundMenu);return;}
        if(actions.size()==1)actions.get(0).run();else menu("Choose image picker",labels,actions,this::backgroundMenu);
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request==MANAGE_LAUNCHER){launcherManager(disablingLaunchers);return;}
        if(request==DEFAULT_HOME){
            if(choosingLuma&&(isDefaultHome()||holdsHomeRole()))finishLauncherSetup();
            else defaultLauncherMenu();
            return;
        }
        if(request!=PICK_IMAGE||result!=RESULT_OK||data==null||data.getData()==null)return;
        final String target=imageTarget;
        if(target.isEmpty())return;
        toast("Importing image…");
        images.importImage(data.getData(),target.replaceAll("[^A-Za-z0-9._-]","_"),target.equals("background_image")?1920:640,
                target.equals("background_image")?1080:360,(path,error)->{
                    if(error!=null){info("Image import",error,this::backgroundMenu);return;}
                    String previous=store.value(target,"");
                    store.set(target,path);if(target.equals("background_image"))store.set("background","photo");
                    if(!previous.isEmpty()) {
                        try{
                            File old=new File(Uri.parse(previous).getPath());
                            String wpDir=PhoneDropServer.wallpapersDir(this).getCanonicalPath()+File.separator;
                            if(old.getCanonicalPath().startsWith(getFilesDir().getCanonicalPath()+File.separator)
                                    && !old.getCanonicalPath().startsWith(wpDir)) old.delete();
                        }catch(Exception ignored){}
                    }
                    imageTarget="";render();toast("Image saved on this TV");
                });
    }
    private void requestListings() {
        if(Build.VERSION.SDK_INT>=23&&checkSelfPermission(TV_LISTINGS_PERMISSION)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{TV_LISTINGS_PERMISSION},TV_LISTINGS);
        else info("TV listings","Access is allowed. Apps still need to publish content that this TV makes available to Luma Home.",this::recommendationMenu);
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results) {
        super.onRequestPermissionsResult(request,permissions,results);
        if(request==TV_LISTINGS){recommendations.clear();render();recommendationMenu();}
    }
    private void chooseApp(String title,boolean excludeFavorites,AppChosen chosen,Runnable back) {
        List<String> labels=new ArrayList<>();List<Runnable> actions=new ArrayList<>();
        for(AppCatalog.App app:apps) {
            if(excludeFavorites&&favorites().contains(app.pkg))continue;
            add(labels,actions,name(app.pkg),()->chosen.pick(app.pkg));
        }
        if(labels.isEmpty()){info(title,"Every available app is already on Home.",back);return;}
        menu(title,labels,actions,back);
    }
    private interface AppChosen {void pick(String pkg);}
    private interface Choice {void pick(int index);}
    private void choice(String title,String[] labels,int selected,Choice action,Runnable back) {
        List<String> names=new ArrayList<>();List<Runnable> actions=new ArrayList<>();
        for(int i=0;i<labels.length;i++){final int index=i;add(names,actions,(i==selected?"✓ ":"○ ")+labels[i],()->action.pick(index));}
        menu(title,names,actions,back);
    }
    private static void add(List<String> labels,List<Runnable> actions,String label,Runnable action){labels.add(label);actions.add(action);}
    private int[] iconOps(String key) {
        switch (key) {
            case "palette": return LineIcon.PALETTE;
            case "banner": return LineIcon.BANNER;
            case "sparkles": return LineIcon.SPARKLES;
            case "clock": return LineIcon.CLOCK;
            case "hdmi": return LineIcon.HDMI;
            case "home": return LineIcon.HOME;
            case "settings": return LineIcon.SETTINGS;
            case "info": return LineIcon.INFO;
            case "wifi": return LineIcon.WIFI;
            case "display": return LineIcon.DISPLAY;
            case "sound": return LineIcon.SOUND;
            default: return null;
        }
    }
    private void resetIdleTimer() {
        handler.removeCallbacks(idleClockTrigger);
        if(destroyed || store==null) return;
        int min=store.number("idle_clock_minutes",5);
        if(min>0 && hasWindowFocus() && (idleClockDialog==null || !idleClockDialog.isShowing())) {
            handler.postDelayed(idleClockTrigger,min*60000L);
        }
    }
    private String screensaverStyle() { return store.value("screensaver_style","cinema"); }
    private String screensaverMotion() { return store.value("screensaver_motion","cinematic"); }
    private String screensaverClockPos() { return store.value("screensaver_clock_pos","bottom_left"); }
    private int screensaverSlideSec() { return Math.max(5,Math.min(120,store.number("screensaver_slide_sec",15))); }
    private int screensaverDimming() { return Math.max(0,Math.min(75,store.number("screensaver_dimming",15))); }
    private String screensaverSummary() {
        int min=store.number("idle_clock_minutes",5);
        if(min<=0) return "Off";
        String st="minimal".equals(screensaverStyle())?"OLED clock":"Cinema frame";
        return st+" · "+min+" min";
    }
    private String screensaverClockPosName(String pos) {
        if("bottom_right".equals(pos)) return "Bottom right";
        if("center".equals(pos)) return "Center";
        if("hidden".equals(pos)) return "Hidden";
        return "Bottom left";
    }
    private String screensaverMotionName(String m) {
        if("subtle".equals(m)) return "Subtle";
        if("off".equals(m)) return "Off";
        return "Cinematic";
    }
    private List<String> screensaverPhotoUris() {
        List<String> uris=new ArrayList<>();
        for(File f:PhoneDropServer.listSavedFiles(this,store)) {
            String u=f.toURI().toString();
            if(!uris.contains(u)) uris.add(u);
        }
        String customBg=store.value("background_image","");
        if(!customBg.isEmpty() && !uris.contains(customBg)) {
            try {
                File cf=new File(Uri.parse(customBg).getPath());
                if(cf.exists() && cf.isFile()) uris.add(0,customBg);
            } catch(Exception ignored) {}
        }
        if(uris.size()>1 && store.flag("screensaver_shuffle",true)) {
            Collections.shuffle(uris);
        }
        return uris;
    }
    private void soundMenu(Runnable back) {
        boolean on=sfx!=null && sfx.enabled();
        String curPack=sfx!=null?sfx.pack():"glass";
        int vol=sfx!=null?sfx.volumePct():65;
        boolean spatial=store.flag("ui_sounds_spatial",true);
        boolean pitch=store.flag("ui_sounds_pitch",true);
        boolean detents=store.flag("ui_sounds_detents",true);
        boolean muteSys=store.flag("ui_sounds_mute_system",true);

        menu("UI sounds",Arrays.asList(
                "§SOUND PACK",
                "UI sounds: "+onOff(on),
                "Sound profile: "+(sfx!=null?sfx.packName():"Liquid glass"),
                "Sound volume: "+vol+"%",
                "§ACOUSTIC DETAILS",
                "Spatial stereo pan: "+onOff(spatial),
                "Melodic glide pitch: "+onOff(pitch),
                "Slider & switch detents: "+onOff(detents),
                "Mute stock TV click: "+onOff(muteSys),
                "§RESET",
                "Restore sound defaults"),
                Arrays.asList(
                        null,
                        ()->{
                            boolean next=!on;
                            store.setFlag("ui_sounds_enabled",next);
                            syncSystemSoundMuting();
                            if(next && sfx!=null) sfx.preview(sfx.pack(),UiSoundEngine.roleSelect(),-1);
                            soundMenu(back);
                        },
                        ()->choice("Sound profile",new String[]{
                                "Liquid glass · Crisp frosted crystal taps",
                                "Warm wood · Organic rosewood marimba mallet",
                                "Velvet pulse · Deep tactile cinema thock",
                                "Dewdrop · Zen water-drop harmonics"},
                                "wood".equals(curPack)?1:"pulse".equals(curPack)?2:"dew".equals(curPack)?3:0,
                                idx->{
                                    String picked=UiSoundEngine.PACK_IDS[idx];
                                    store.set("ui_sounds_pack",picked);
                                    store.setFlag("ui_sounds_enabled",true);
                                    syncSystemSoundMuting();
                                    if(sfx!=null) sfx.preview(picked,UiSoundEngine.roleSelect(),-1);
                                    soundMenu(back);
                                },()->soundMenu(back)),
                        ()->soundVolumeDialog(back),
                        null,
                        ()->{ store.setFlag("ui_sounds_spatial",!spatial); soundMenu(back); },
                        ()->{ store.setFlag("ui_sounds_pitch",!pitch); soundMenu(back); },
                        ()->{ store.setFlag("ui_sounds_detents",!detents); soundMenu(back); },
                        ()->{
                            store.setFlag("ui_sounds_mute_system",!muteSys);
                            syncSystemSoundMuting();
                            soundMenu(back);
                        },
                        null,
                        ()->{
                            store.setFlag("ui_sounds_enabled",true);
                            store.set("ui_sounds_pack","glass");
                            store.setNumber("ui_sounds_volume",65);
                            store.setFlag("ui_sounds_spatial",true);
                            store.setFlag("ui_sounds_pitch",true);
                            store.setFlag("ui_sounds_detents",true);
                            store.setFlag("ui_sounds_mute_system",true);
                            syncSystemSoundMuting();
                            if(sfx!=null) sfx.preview("glass",UiSoundEngine.roleSelect(),65);
                            toast("UI sounds restored to defaults");
                            soundMenu(back);
                        }),back);
    }
    private void soundVolumeDialog(Runnable back) {
        LinearLayout box=new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        TextView value=text("",22,foreground()); value.setTypeface(medium); box.addView(value);
        TextView sub=text("Use Left / Right to adjust loudness, or audition each sound below.",18,muted());
        LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-1,-2); sp.topMargin=px(4); box.addView(sub,sp);

        final SeekBar slider=new SeekBar(this);
        slider.setMax(90);
        slider.setProgress((sfx!=null?sfx.volumePct():65)-10);
        slider.setKeyProgressIncrement(5);
        slider.setFocusableInTouchMode(true);
        slider.setProgressTintList(android.content.res.ColorStateList.valueOf(accent()));
        slider.setThumbTintList(android.content.res.ColorStateList.valueOf(accent()));
        LinearLayout.LayoutParams slp=new LinearLayout.LayoutParams(-1,px(60));
        slp.topMargin=px(14); slp.bottomMargin=px(14);
        box.addView(slider,slp);

        // Live Acoustic Audition Card (2x2 tactile sample pads)
        LinearLayout card=new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(px(16),px(16),px(16),px(16));
        card.setBackground(glassPanelDrawable(card,20f,false));
        String pName=sfx!=null?sfx.packName():"Liquid glass";
        TextView cardTitle=text("Audition · "+pName,19,foreground());
        cardTitle.setTypeface(medium);
        card.addView(cardTitle);
        TextView cardSub=text("Press OK on any pad to hear it at this volume",15,muted());
        LinearLayout.LayoutParams csp=new LinearLayout.LayoutParams(-1,-2); csp.topMargin=px(2); csp.bottomMargin=px(12);
        card.addView(cardSub,csp);

        String[] padNames={"Focus Tap","Select Bloom","Menu Glide","Back Settle"};
        int[] padRoles={UiSoundEngine.roleFocus(),UiSoundEngine.roleSelect(),UiSoundEngine.roleBlade(),UiSoundEngine.roleBack()};
        LinearLayout gridRow1=new LinearLayout(this); gridRow1.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout gridRow2=new LinearLayout(this); gridRow2.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams r2p=new LinearLayout.LayoutParams(-1,-2); r2p.topMargin=px(8);

        for(int i=0;i<4;i++) {
            final int role=padRoles[i];
            Button pad=button(padNames[i],()->{
                if(sfx!=null) sfx.preview(sfx.pack(),role,slider.getProgress()+10);
            });
            pad.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,px(18));
            LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(0,px(48),1f);
            if(i%2==1) pp.leftMargin=px(8);
            (i<2?gridRow1:gridRow2).addView(pad,pp);
        }
        card.addView(gridRow1,new LinearLayout.LayoutParams(-1,-2));
        card.addView(gridRow2,r2p);
        box.addView(card,new LinearLayout.LayoutParams(-1,-2));

        Runnable update=()->{
            int v=slider.getProgress()+10;
            String tag=v<=30?"Whisper subtle":v<=70?"Balanced tactile":"Crisp & prominent";
            value.setText("Sound volume · "+v+"% ("+tag+")");
        };
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar,int progress,boolean user) {
                update.run();
                if(user && sfx!=null) sfx.preview(sfx.pack(),UiSoundEngine.roleFocus(),progress+10);
            }
            public void onStartTrackingTouch(SeekBar bar) {}
            public void onStopTrackingTouch(SeekBar bar) {}
        });
        update.run();

        showBladePanel("Sound volume",box,"Save",()->{
            int v=slider.getProgress()+10;
            store.setNumber("ui_sounds_volume",v);
            store.setFlag("ui_sounds_enabled",true);
            syncSystemSoundMuting();
            soundMenu(back);
        },()->soundMenu(back),slider);
    }
    private void screensaverMenu(Runnable back) {
        int idleMin=store.number("idle_clock_minutes",5);
        String style=screensaverStyle();
        String motion=screensaverMotion();
        String clockPos=screensaverClockPos();
        int slideSec=screensaverSlideSec();
        int dim=screensaverDimming();
        int photoCount=PhoneDropServer.listSavedFiles(this,store).size();
        boolean shuffle=store.flag("screensaver_shuffle",true);
        boolean keepAwake=store.flag("screensaver_keep_awake",true);

        List<String> labels=new ArrayList<>(); List<Runnable> actions=new ArrayList<>();
        add(labels,actions,"§PREVIEW",null);
        add(labels,actions,"@display|Preview screensaver now",()->{ closeBlade(); showIdleClock(); });
        add(labels,actions,"§BEHAVIOR",null);
        add(labels,actions,"Start after: "+(idleMin<=0?"Off":idleMin+" min"),()->{
            int[] mins={0,2,5,10,15,30};
            String[] opts={"Off","2 minutes","5 minutes","10 minutes","15 minutes","30 minutes"};
            int sel=2;
            for(int i=0;i<mins.length;i++) if(mins[i]==idleMin) { sel=i; break; }
            choice("Start screensaver after",opts,sel,idx->{
                store.setNumber("idle_clock_minutes",mins[idx]);
                resetIdleTimer();
                screensaverMenu(back);
            },()->screensaverMenu(back));
        });
        add(labels,actions,"Style: "+("minimal".equals(style)?"Minimal OLED clock":"Cinema photo frame"),()->choice(
                "Screensaver style",
                new String[]{
                        "Cinema photo frame · Ken Burns motion + Liquid Glass clock",
                        "Minimal OLED clock · Pitch-dark drifting timepiece"},
                "minimal".equals(style)?1:0,
                idx->{
                    store.set("screensaver_style",idx==1?"minimal":"cinema");
                    screensaverMenu(back);
                },()->screensaverMenu(back)));
        add(labels,actions,"Keep TV awake: "+onOff(keepAwake),()->{
            store.setFlag("screensaver_keep_awake",!keepAwake);
            screensaverMenu(back);
        });
        add(labels,actions,"§CINEMA PHOTO FRAME",null);
        add(labels,actions,"Photo duration: "+slideSec+" sec",()->{
            int[] secs={8,15,30,60};
            String[] opts={
                    "8 seconds · Lively gallery pace",
                    "15 seconds · Balanced cinema showcase",
                    "30 seconds · Relaxed living-room frame",
                    "60 seconds · Slow ambient portrait"};
            int sel=1;
            for(int i=0;i<secs.length;i++) if(secs[i]==slideSec) { sel=i; break; }
            choice("Photo duration",opts,sel,idx->{
                store.setNumber("screensaver_slide_sec",secs[idx]);
                screensaverMenu(back);
            },()->screensaverMenu(back));
        });
        add(labels,actions,"Ken Burns motion: "+screensaverMotionName(motion),()->choice(
                "Ken Burns motion",
                new String[]{
                        "Cinematic · Smooth 60fps zoom & pan across photos",
                        "Subtle · Gentle slow camera drift",
                        "Off · Static photos with smooth crossfades"},
                "subtle".equals(motion)?1:"off".equals(motion)?2:0,
                idx->{
                    store.set("screensaver_motion",new String[]{"cinematic","subtle","off"}[idx]);
                    screensaverMenu(back);
                },()->screensaverMenu(back)));
        add(labels,actions,"Photo order: "+(shuffle?"Shuffle":"Newest first"),()->{
            store.setFlag("screensaver_shuffle",!shuffle);
            screensaverMenu(back);
        });
        add(labels,actions,"@wifi|Frame photos: "+(photoCount==0?"Scan QR to add":photoCount+" saved"),()->{
            if(photoCount==0) phoneDropPanel(()->screensaverMenu(back));
            else wallpaperGalleryMenu(()->screensaverMenu(back));
        });
        add(labels,actions,"§CLOCK & AMBIENCE",null);
        add(labels,actions,"Glass clock: "+screensaverClockPosName(clockPos),()->choice(
                "Glass clock position",
                new String[]{
                        "Bottom left · Classic cinema gallery plaque",
                        "Bottom right · Right-corner frosted plaque",
                        "Center · Floating centerpiece clock",
                        "Hidden · Pure unobstructed photo frame"},
                "bottom_right".equals(clockPos)?1:"center".equals(clockPos)?2:"hidden".equals(clockPos)?3:0,
                idx->{
                    store.set("screensaver_clock_pos",new String[]{"bottom_left","bottom_right","center","hidden"}[idx]);
                    screensaverMenu(back);
                },()->screensaverMenu(back)));
        add(labels,actions,"Night dimming: "+dim+"%",()->screensaverDimmingDialog(back));
        add(labels,actions,"Restore screensaver defaults",()->{
            store.setNumber("idle_clock_minutes",5);
            store.set("screensaver_style","cinema");
            store.setFlag("screensaver_keep_awake",true);
            store.setNumber("screensaver_slide_sec",15);
            store.set("screensaver_motion","cinematic");
            store.setFlag("screensaver_shuffle",true);
            store.set("screensaver_clock_pos","bottom_left");
            store.setNumber("screensaver_dimming",15);
            resetIdleTimer();
            toast("Screensaver restored to defaults");
            screensaverMenu(back);
        });
        menu("Idle screensaver",labels,actions,back);
    }
    private void screensaverDimmingDialog(Runnable back) {
        LinearLayout box=new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        TextView value=text("",22,foreground()); value.setTypeface(medium); box.addView(value);
        TextView sub=text("Use Left / Right to soften photo brightness for evening viewing.",18,muted());
        LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-1,-2); sp.topMargin=px(4); box.addView(sub,sp);

        FrameLayout preview=new FrameLayout(this);
        preview.setClipToOutline(true);
        preview.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View v,android.graphics.Outline o) { o.setRoundRect(0,0,v.getWidth(),v.getHeight(),px(18)); }
        });
        preview.setBackground(buildSkyBackground());
        List<File> saved=PhoneDropServer.listSavedFiles(this,store);
        if(!saved.isEmpty()) {
            ImageView pvImg=new ImageView(this);
            pvImg.setScaleType(ImageView.ScaleType.CENTER_CROP);
            preview.addView(pvImg,new FrameLayout.LayoutParams(-1,-1));
            images.show(pvImg,saved.get(0).toURI().toString(),px(400),px(225));
        }
        View veil=new View(this);
        preview.addView(veil,new FrameLayout.LayoutParams(-1,-1));

        LinearLayout miniClock=new LinearLayout(this);
        miniClock.setOrientation(LinearLayout.VERTICAL);
        miniClock.setPadding(px(14),px(10),px(16),px(10));
        miniClock.setBackground(shapeRadius(0x660E1522,0x55FFFFFF,1,14));
        TextClock mcTime=new TextClock(this);
        mcTime.setFormat12Hour("h:mm"); mcTime.setFormat24Hour("H:mm");
        mcTime.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,px(30));
        mcTime.setTextColor(0xF2FFFFFF); mcTime.setTypeface(medium); mcTime.setIncludeFontPadding(false);
        miniClock.addView(mcTime);
        TextView mcSub=text("Cinema Frame",13,0xCCFFFFFF);
        miniClock.addView(mcSub);
        FrameLayout.LayoutParams mcp=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.LEFT);
        mcp.leftMargin=px(14); mcp.bottomMargin=px(14);
        preview.addView(miniClock,mcp);

        LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(-1,px(205));
        pp.topMargin=px(16); pp.bottomMargin=px(16);
        box.addView(preview,pp);

        SeekBar slider=new SeekBar(this);
        slider.setMax(75); slider.setProgress(screensaverDimming());
        slider.setKeyProgressIncrement(5); slider.setFocusableInTouchMode(true);
        slider.setProgressTintList(android.content.res.ColorStateList.valueOf(accent()));
        slider.setThumbTintList(android.content.res.ColorStateList.valueOf(accent()));
        box.addView(slider,new LinearLayout.LayoutParams(-1,px(60)));

        Runnable update=()->{
            int d=slider.getProgress();
            String tag=d==0?"Full vivid brightness":d<=25?"Balanced cinema glow":d<=50?"Cozy evening dim":"Late-night dark";
            value.setText("Night dimming · "+d+"% ("+tag+")");
            veil.setBackgroundColor(Color.argb(Math.round(d*2.55f),0,0,0));
        };
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar,int progress,boolean user) { update.run(); }
            public void onStartTrackingTouch(SeekBar bar) {}
            public void onStopTrackingTouch(SeekBar bar) {}
        });
        update.run();
        showBladePanel("Night dimming",box,"Save",()->{
            store.setNumber("screensaver_dimming",slider.getProgress());
            screensaverMenu(back);
        },()->screensaverMenu(back),slider);
    }
    private void dismissIdleClock() {
        if(idleClockDialog!=null) {
            Dialog d=idleClockDialog; idleClockDialog=null;
            try { if(d.isShowing()) d.dismiss(); } catch(Exception ignored) {}
        }
        resetIdleTimer();
    }
    private void showIdleClock() {
        if(destroyed || (idleClockDialog!=null && idleClockDialog.isShowing())) return;
        final boolean minimal="minimal".equals(screensaverStyle());
        final Dialog d=new Dialog(this,android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        if(store.flag("screensaver_keep_awake",true) && d.getWindow()!=null) {
            d.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        final long openedAt=android.os.SystemClock.uptimeMillis();
        final boolean[] sawKeyDown={false};

        if(minimal) {
            FrameLayout stage=new FrameLayout(this);
            GradientDrawable bg=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0xFF04060C,0xFF080E1C,0xFF030408});
            stage.setBackground(bg);
            LinearLayout box=new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER);
            TextView greet=text(greetingText(),18,accent());
            greet.setTypeface(medium); greet.setLetterSpacing(0.18f);
            box.addView(greet,new LinearLayout.LayoutParams(-2,-2));
            TextClock time=new TextClock(this);
            time.setFormat12Hour("h:mm"); time.setFormat24Hour("H:mm");
            time.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,px(210));
            time.setTextColor(0xEEFFFFFF);
            time.setTypeface(medium);
            time.setIncludeFontPadding(false);
            box.addView(time,new LinearLayout.LayoutParams(-2,-2));
            TextClock date=new TextClock(this);
            date.setFormat12Hour("EEEE, d MMMM"); date.setFormat24Hour("EEEE, d MMMM");
            date.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,px(28));
            date.setTextColor(0x99FFFFFF);
            date.setTypeface(medium);
            box.addView(date,new LinearLayout.LayoutParams(-2,-2));
            TextView hint=text("Press any button to wake",16,0x55FFFFFF);
            LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(-2,-2); hp.topMargin=px(32);
            box.addView(hint,hp);
            stage.addView(box,new FrameLayout.LayoutParams(-2,-2,Gravity.CENTER));
            final Runnable[] drift={null};
            drift[0]=()->{
                if(idleClockDialog!=d || !d.isShowing()) return;
                float dx=(float)(Math.sin(System.currentTimeMillis()/18000.0)*px(90));
                float dy=(float)(Math.cos(System.currentTimeMillis()/23000.0)*px(55));
                box.animate().translationX(dx).translationY(dy).setDuration(4000).start();
                handler.postDelayed(drift[0],4000);
            };
            d.setContentView(stage);
            d.setOnKeyListener((dlg,key,ev)->{
                if(ev.getAction()==KeyEvent.ACTION_DOWN) sawKeyDown[0]=true;
                else if(ev.getAction()==KeyEvent.ACTION_UP && (sawKeyDown[0] || android.os.SystemClock.uptimeMillis()-openedAt>450)) dismissIdleClock();
                return true;
            });
            d.setOnDismissListener(dlg->{
                if(idleClockDialog==d) idleClockDialog=null;
                resetIdleTimer();
            });
            idleClockDialog=d;
            d.show();
            if(d.getWindow()!=null && d.getWindow().getDecorView()!=null) applyViewSoundPolicy(d.getWindow().getDecorView());
            box.setAlpha(0f);
            box.animate().alpha(1f).setDuration(450).start();
            handler.postDelayed(drift[0],2000);
            return;
        }

        // Cinema Photo Frame Screensaver (60fps Ken Burns + Live Frosted Liquid Glass Clock)
        final List<String> photos=screensaverPhotoUris();
        final String motionMode=screensaverMotion();
        final String clockPos=screensaverClockPos();
        final long slideMs=screensaverSlideSec()*1000L;
        final long crossfadeMs=1400L;
        final int dimPct=screensaverDimming();
        final int dimAlpha=Math.round(dimPct*2.55f);

        final FrameLayout stage=new FrameLayout(this);
        stage.setBackgroundColor(0xFF060911);
        stage.setClipChildren(true);

        // Live frosted refraction state updated per slide
        final android.graphics.Bitmap ssFrostBmp=android.graphics.Bitmap.createBitmap(240,135,android.graphics.Bitmap.Config.ARGB_8888);
        final android.graphics.BitmapShader[] ssShader={null};
        final int[] ssAccent={accent()};
        final LinearLayout[] glassClockRef={null};
        final TextView[] eyebrowRef={null};

        final Runnable buildFallbackShader=()->{
            try {
                Drawable sky=buildSkyBackground();
                android.graphics.Canvas cv=new android.graphics.Canvas(ssFrostBmp);
                cv.drawColor(0xFF0B101A);
                sky.setBounds(0,0,240,135);
                sky.draw(cv);
                if(dimAlpha>0) cv.drawColor(Color.argb(dimAlpha,0,0,0));
                blurBitmapInPlace(ssFrostBmp,7);
                ssShader[0]=new android.graphics.BitmapShader(ssFrostBmp,
                        android.graphics.Shader.TileMode.CLAMP,android.graphics.Shader.TileMode.CLAMP);
                if(glassClockRef[0]!=null) glassClockRef[0].invalidate();
            } catch(Exception|OutOfMemoryError ignored) {}
        };

        // 1. Photo Deck (holds double-buffered Ken Burns layers)
        final FrameLayout photoDeck=new FrameLayout(this);
        photoDeck.setClipChildren(true);
        stage.addView(photoDeck,new FrameLayout.LayoutParams(-1,-1));

        final android.view.animation.LinearInterpolator linearInterp=new android.view.animation.LinearInterpolator();
        final int[] curPhotoIdx={-1};
        final int[] moveCounter={0};
        final int[] activeLayerIdx={1};
        final FrameLayout[] wraps=new FrameLayout[2];
        final ImageView[] layers=new ImageView[2];
        final Runnable[] autoTick={null};

        final java.util.function.BiConsumer<View,Boolean> startKenBurns=(targetView,firstSlide)->{
            targetView.animate().cancel();
            if("off".equals(motionMode)) {
                targetView.setScaleX(1f); targetView.setScaleY(1f);
                targetView.setTranslationX(0f); targetView.setTranslationY(0f);
                return;
            }
            float amp="subtle".equals(motionMode)?0.5f:1.0f;
            float sMin=1f+0.06f*amp;
            float sMax=1f+0.15f*amp;
            float dx=px(26f*amp);
            float dy=px(15f*amp);
            int mv=(moveCounter[0]++)%4;
            float s0, s1, tx0, tx1, ty0, ty1;
            switch(mv) {
                case 0: s0=sMin; s1=sMax; tx0=-dx; tx1=dx; ty0=dy; ty1=-dy; break;
                case 1: s0=sMax; s1=sMin; tx0=dx; tx1=-dx; ty0=-dy; ty1=dy; break;
                case 2: s0=(sMin+sMax)*0.5f; s1=sMax; tx0=-dx*1.12f; tx1=dx*1.12f; ty0=-dy*0.4f; ty1=dy*0.4f; break;
                default: s0=sMin; s1=sMax*0.99f; tx0=dx*0.7f; tx1=-dx*0.7f; ty0=dy; ty1=-dy; break;
            }
            targetView.setScaleX(s0); targetView.setScaleY(s0);
            targetView.setTranslationX(tx0); targetView.setTranslationY(ty0);
            long moveTotal=photos.size()>1?(slideMs+crossfadeMs+400L):Math.max(22000L,slideMs*2);
            targetView.animate()
                    .scaleX(s1).scaleY(s1)
                    .translationX(tx1).translationY(ty1)
                    .setInterpolator(linearInterp)
                    .setDuration(moveTotal)
                    .start();
        };

        if(photos.isEmpty()) {
            View aurora=new View(this);
            aurora.setBackground(buildSkyBackground());
            aurora.setLayerType(View.LAYER_TYPE_HARDWARE,null);
            photoDeck.addView(aurora,new FrameLayout.LayoutParams(-1,-1));
            buildFallbackShader.run();
            startKenBurns.accept(aurora,true);
        } else {
            for(int i=0;i<2;i++) {
                final int slot=i;
                final FrameLayout wrap=new FrameLayout(this);
                wrap.setClipChildren(true);
                wrap.setAlpha(0f);
                wraps[i]=wrap;
                ImageView iv=new ImageView(this) {
                    @Override public void setImageBitmap(android.graphics.Bitmap bmp) {
                        super.setImageBitmap(bmp);
                        if(bmp==null || idleClockDialog!=d || !d.isShowing()) return;
                        try {
                            android.graphics.Canvas cv=new android.graphics.Canvas(ssFrostBmp);
                            cv.drawColor(0xFF0B101A);
                            int pw=Math.max(1,bmp.getWidth()), ph=Math.max(1,bmp.getHeight());
                            float sc=Math.max(240f/pw,135f/ph);
                            float dw=pw*sc, dh=ph*sc;
                            cv.drawBitmap(bmp,null,new android.graphics.RectF((240f-dw)/2f,(135f-dh)/2f,(240f+dw)/2f,(135f+dh)/2f),
                                    new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));
                            int[] pxSample=new int[48*27];
                            android.graphics.Bitmap tiny=android.graphics.Bitmap.createScaledBitmap(ssFrostBmp,48,27,true);
                            tiny.getPixels(pxSample,0,48,0,0,48,27);
                            if(tiny!=ssFrostBmp) tiny.recycle();
                            int seed=ThemePalette.seed(pxSample);
                            if(seed!=0) ssAccent[0]=ThemePalette.accent(seed,72);
                            if(dimAlpha>0) cv.drawColor(Color.argb(dimAlpha,0,0,0));
                            blurBitmapInPlace(ssFrostBmp,7);
                            ssShader[0]=new android.graphics.BitmapShader(ssFrostBmp,
                                    android.graphics.Shader.TileMode.CLAMP,android.graphics.Shader.TileMode.CLAMP);
                            if(eyebrowRef[0]!=null) eyebrowRef[0].setTextColor(ssAccent[0]);
                            if(glassClockRef[0]!=null) glassClockRef[0].invalidate();
                        } catch(Exception|OutOfMemoryError ignored) {}

                        boolean first=(moveCounter[0]==0);
                        FrameLayout otherWrap=wraps[1-slot];
                        wrap.animate().cancel();
                        wrap.bringToFront();
                        wrap.setAlpha(0f);
                        startKenBurns.accept(this,first);
                        wrap.animate().alpha(1f)
                                .setInterpolator(crispDecel)
                                .setDuration(first?650L:crossfadeMs)
                                .withEndAction(()->{
                                    if(otherWrap!=null) otherWrap.setAlpha(0f);
                                }).start();
                    }
                };
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setLayerType(View.LAYER_TYPE_HARDWARE,null);
                layers[i]=iv;
                wrap.addView(iv,new FrameLayout.LayoutParams(-1,-1));
                photoDeck.addView(wrap,new FrameLayout.LayoutParams(-1,-1));
            }
        }

        // Wait: even cleaner decoupled alpha + Ken Burns wrapper! Let's check below.
        // 2. Subtle Cinema Vignette + Night Dimming Veil
        View vignette=new View(this);
        GradientDrawable vig=new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x28000000,0x00000000,0x58000000});
        vignette.setBackground(vig);
        stage.addView(vignette,new FrameLayout.LayoutParams(-1,-1));

        if(dimAlpha>0) {
            View dimVeil=new View(this);
            dimVeil.setBackgroundColor(Color.argb(dimAlpha,0,0,0));
            stage.addView(dimVeil,new FrameLayout.LayoutParams(-1,-1));
        }

        // 3. Floating Liquid Glass Clock Plaque
        final boolean centerClock="center".equals(clockPos);
        if(!"hidden".equals(clockPos)) {
            final LinearLayout glassCard=new LinearLayout(this);
            glassClockRef[0]=glassCard;
            glassCard.setOrientation(LinearLayout.VERTICAL);
            glassCard.setGravity(centerClock?Gravity.CENTER:Gravity.LEFT);
            int padH=px(centerClock?44:32), padV=px(centerClock?30:22);
            glassCard.setPadding(padH,padV,padH,padV);
            glassCard.setBackground(new Drawable() {
                private final android.graphics.Paint p=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                private final android.graphics.RectF r=new android.graphics.RectF();
                private final android.graphics.Matrix mat=new android.graphics.Matrix();
                private final int[] loc=new int[2];
                @Override public void draw(android.graphics.Canvas c) {
                    r.set(getBounds());
                    if(r.width()<=0 || r.height()<=0) return;
                    float rad=px(28);
                    float sh=sheenMultiplier();
                    if(ssShader[0]!=null && screenWidth>0 && screenHeight>0) {
                        glassCard.getLocationOnScreen(loc);
                        mat.reset();
                        mat.setScale((float)screenWidth/240f,(float)screenHeight/135f);
                        mat.postTranslate(-loc[0],-loc[1]);
                        ssShader[0].setLocalMatrix(mat);
                        p.setStyle(android.graphics.Paint.Style.FILL);
                        p.setShader(ssShader[0]);
                        c.drawRoundRect(r,rad,rad,p);
                        p.setShader(null);
                    }
                    int frost=glassFrostDepth();
                    int tintAlpha=Math.min(205,Math.max(105,Math.round(frost*1.85f)));
                    p.setStyle(android.graphics.Paint.Style.FILL);
                    p.setColor((tintAlpha<<24)|0x0C111B);
                    c.drawRoundRect(r,rad,rad,p);

                    c.save();
                    c.clipRect(r.left,r.top,r.right,r.top+r.height()*0.46f);
                    p.setShader(new android.graphics.LinearGradient(0,r.top,0,r.top+r.height()*0.46f,
                            Color.argb(Math.min(130,Math.round(54*sh)),255,255,255),0x00FFFFFF,
                            android.graphics.Shader.TileMode.CLAMP));
                    c.drawRoundRect(r,rad,rad,p);
                    c.restore();
                    p.setShader(null);

                    float sw=Math.max(1f,px(1.6f));
                    p.setStyle(android.graphics.Paint.Style.STROKE);
                    p.setStrokeWidth(sw);
                    int ac=(ssAccent[0]&0x00FFFFFF)|0x66000000;
                    p.setShader(new android.graphics.LinearGradient(r.left,r.top,r.right,r.bottom,
                            new int[]{Color.argb(Math.min(220,Math.round(125*sh)),255,255,255),0x2EFFFFFF,ac},
                            new float[]{0f,0.52f,1f},android.graphics.Shader.TileMode.CLAMP));
                    float inset=sw/2f;
                    r.inset(inset,inset);
                    c.drawRoundRect(r,rad-inset,rad-inset,p);
                    p.setShader(null);
                }
                @Override public void setAlpha(int a) {}
                @Override public void setColorFilter(android.graphics.ColorFilter cf) {}
                @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
            });

            TextView eyebrow=text(greetingText(),15,ssAccent[0]);
            eyebrow.setTypeface(medium);
            eyebrow.setLetterSpacing(0.16f);
            eyebrowRef[0]=eyebrow;
            glassCard.addView(eyebrow,new LinearLayout.LayoutParams(-2,-2));

            TextClock time=new TextClock(this);
            time.setFormat12Hour("h:mm"); time.setFormat24Hour("H:mm");
            time.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,px(centerClock?152:98));
            time.setTextColor(0xF6FFFFFF);
            time.setTypeface(medium);
            time.setIncludeFontPadding(false);
            glassCard.addView(time,new LinearLayout.LayoutParams(-2,-2));

            TextClock date=new TextClock(this);
            date.setFormat12Hour("EEEE, d MMMM"); date.setFormat24Hour("EEEE, d MMMM");
            date.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,px(centerClock?26:21));
            date.setTextColor(0xCCFFFFFF);
            date.setTypeface(medium);
            glassCard.addView(date,new LinearLayout.LayoutParams(-2,-2));

            int grav=centerClock?Gravity.CENTER:("bottom_right".equals(clockPos)?(Gravity.BOTTOM|Gravity.RIGHT):(Gravity.BOTTOM|Gravity.LEFT));
            FrameLayout.LayoutParams cp=new FrameLayout.LayoutParams(-2,-2,grav);
            if(!centerClock) {
                cp.leftMargin=px(64); cp.rightMargin=px(64); cp.bottomMargin=px(56);
            }
            stage.addView(glassCard,cp);

            glassCard.setAlpha(0f);
            glassCard.setTranslationY(px(14));
            glassCard.animate().alpha(1f).translationY(0f).setDuration(550).setInterpolator(crispDecel).start();

            // Gentle OLED pixel-protection micro-drift + glass refraction refresh
            final Runnable[] clockDrift={null};
            clockDrift[0]=()->{
                if(idleClockDialog!=d || !d.isShowing()) return;
                float range=centerClock?px(36):px(8);
                float dx=(float)(Math.sin(System.currentTimeMillis()/19000.0)*range);
                float dy=(float)(Math.cos(System.currentTimeMillis()/24000.0)*(range*0.7f));
                glassCard.animate().translationX(dx).translationY(dy).setDuration(4200)
                        .setUpdateListener(a->glassCard.invalidate()).start();
                handler.postDelayed(clockDrift[0],4200);
            };
            handler.postDelayed(clockDrift[0],2500);
        }

        // Subtle wake / browse hint pill that fades away cleanly after 4.5s
        String hintMsg=photos.size()>1?"Left / Right to change photo  ·  Any button to wake":
                photos.isEmpty()?"Drop photos via QR to showcase your gallery  ·  Any button to wake":
                "Press any button to wake";
        TextView wakeHint=text(hintMsg,15,0xCCFFFFFF);
        wakeHint.setTypeface(medium);
        wakeHint.setPadding(px(18),px(8),px(18),px(8));
        wakeHint.setBackground(shapeRadius(0x550B101A,0x28FFFFFF,1,99));
        FrameLayout.LayoutParams whp=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.RIGHT);
        whp.topMargin=px(44); whp.rightMargin=px(56);
        stage.addView(wakeHint,whp);
        wakeHint.postDelayed(()->{
            if(idleClockDialog==d && d.isShowing()) wakeHint.animate().alpha(0f).setDuration(900).start();
        },4500);

        // Slide advancement helper
        final java.util.function.IntConsumer advanceSlide=delta->{
            if(photos.isEmpty() || idleClockDialog!=d || !d.isShowing()) return;
            if(autoTick[0]!=null) handler.removeCallbacks(autoTick[0]);
            curPhotoIdx[0]=(curPhotoIdx[0]+delta+photos.size())%photos.size();
            activeLayerIdx[0]=1-activeLayerIdx[0];
            ImageView nextView=layers[activeLayerIdx[0]];
            String uri=photos.get(curPhotoIdx[0]);
            if(eyebrowRef[0]!=null) {
                eyebrowRef[0].setText(photos.size()>1
                        ?(greetingText()+"  ·  "+(curPhotoIdx[0]+1)+" / "+photos.size())
                        :greetingText());
            }
            images.show(nextView,uri,Math.min(screenWidth,1920),Math.min(screenHeight,1080));
            if(photos.size()>1 && autoTick[0]!=null) {
                handler.postDelayed(autoTick[0],slideMs);
            }
        };
        autoTick[0]=()->advanceSlide.accept(1);

        d.setContentView(stage);
        d.setOnKeyListener((dlg,key,ev)->{
            if(ev.getAction()==KeyEvent.ACTION_DOWN) {
                sawKeyDown[0]=true;
                if(photos.size()>1 && key==KeyEvent.KEYCODE_DPAD_RIGHT) { if(sfx!=null)sfx.playFocus(null,screenWidth,1); advanceSlide.accept(1); return true; }
                if(photos.size()>1 && key==KeyEvent.KEYCODE_DPAD_LEFT) { if(sfx!=null)sfx.playFocus(null,screenWidth,-1); advanceSlide.accept(-1); return true; }
                return true;
            }
            if(ev.getAction()==KeyEvent.ACTION_UP) {
                if(photos.size()>1 && (key==KeyEvent.KEYCODE_DPAD_RIGHT || key==KeyEvent.KEYCODE_DPAD_LEFT)) return true;
                if(sawKeyDown[0] || android.os.SystemClock.uptimeMillis()-openedAt>450) dismissIdleClock();
            }
            return true;
        });
        d.setOnDismissListener(dlg->{
            if(autoTick[0]!=null) handler.removeCallbacks(autoTick[0]);
            for(ImageView iv:layers) if(iv!=null) iv.animate().cancel();
            if(idleClockDialog==d) idleClockDialog=null;
            resetIdleTimer();
        });
        idleClockDialog=d;
        d.show();
        if(d.getWindow()!=null && d.getWindow().getDecorView()!=null) applyViewSoundPolicy(d.getWindow().getDecorView());
        if(!photos.isEmpty()) {
            curPhotoIdx[0]=-1;
            advanceSlide.accept(1);
        }
    }
    private void closeBlade() {
        overrideFrostPct=-1;
        overrideTreatmentStyle=null;
        overrideTreatmentIntensity=-1;
        syncTreatmentLayers();
        activeBladeBox=null;
        stopPhoneDrop();
        if(previewSpringStage!=null) { previewSpringStage.stop(); previewSpringStage=null; }
        if (activeBlade!=null) {
            Dialog closing=activeBlade; activeBlade=null; bladeHost=null; bladeBack=null; lastBladeTitle="";
            try { if (closing.isShowing()) closing.dismiss(); } catch (Exception ignored) {}
        }
    }
    private LinearLayout createBladeContainer(String title,Runnable back) {
        LinearLayout box=new LinearLayout(this);
        activeBladeBox=box;
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(px(28),px(38),px(28),px(28));
        box.setBackground(glassPanelDrawable(box,0f,true));
        if (back!=null) {
            TextView crumb=text("‹  Back",17,accent());
            crumb.setTypeface(medium);
            crumb.setPadding(px(6),0,px(6),px(4));
            box.addView(crumb,new LinearLayout.LayoutParams(-1,-2));
        }
        TextView heading=text(title,28,foreground());
        heading.setTypeface(medium);
        heading.setMaxLines(1);
        heading.setEllipsize(android.text.TextUtils.TruncateAt.END);
        heading.setPadding(px(6),0,px(6),0);
        LinearLayout.LayoutParams headParams=new LinearLayout.LayoutParams(-1,-2);
        headParams.topMargin=px(12);headParams.bottomMargin=px(24);
        box.addView(heading,headParams);
        View divider=new View(this);
        divider.setBackgroundColor(0x18FFFFFF);
        LinearLayout.LayoutParams divParams=new LinearLayout.LayoutParams(-1,Math.max(1,px(1)));
        divParams.leftMargin=px(6); divParams.rightMargin=px(6); divParams.bottomMargin=px(20);
        box.addView(divider,divParams);
        return box;
    }
    private void presentBladeView(String title,View panel,Runnable back,View initialFocus,boolean animate) {
        if(!"Phone Drop".equals(title)) stopPhoneDrop();
        bladeGeneration++;
        bladeBack=back;
        if (activeBlade==null || !activeBlade.isShowing() || bladeHost==null) {
            if(sfx!=null) sfx.playBladeOpen();
            Dialog dialog=new Dialog(this,android.R.style.Theme_Material_NoActionBar);
            FrameLayout host=new FrameLayout(this) {
                @Override public void playSoundEffect(int soundConstant) {
                    if(muteSystem()) return;
                    super.playSoundEffect(soundConstant);
                }
            };
            host.setSoundEffectsEnabled(!muteSystem());
            host.getViewTreeObserver().addOnGlobalFocusChangeListener((oldF,newF)->{
                if(destroyed || sfx==null || oldF==null || newF==null || oldF==newF) return;
                if(android.os.SystemClock.uptimeMillis()-lastUserNavMs<280L) {
                    sfx.playFocus(newF,screenWidth,lastUserNavDir);
                }
            });
            dialog.setContentView(host);
            dialog.setOnKeyListener((d,key,event)->{
                noteNavKey(key,event);
                if(muteSystem() && dialog.getWindow()!=null) {
                    View bDecor=dialog.getWindow().getDecorView();
                    if(bDecor!=null && bDecor.isSoundEffectsEnabled()) bDecor.setSoundEffectsEnabled(false);
                }
                if(event.getAction()==KeyEvent.ACTION_DOWN
                        && (key==KeyEvent.KEYCODE_DPAD_LEFT || key==KeyEvent.KEYCODE_DPAD_RIGHT)
                        && dialog.getCurrentFocus() instanceof SeekBar
                        && !"Sound volume".equals(lastBladeTitle)) {
                    SeekBar sb=(SeekBar)dialog.getCurrentFocus();
                    if((key==KeyEvent.KEYCODE_DPAD_LEFT && sb.getProgress()>0)
                            || (key==KeyEvent.KEYCODE_DPAD_RIGHT && sb.getProgress()<sb.getMax())) {
                        int pct=sb.getMax()>0?(sb.getProgress()*100/sb.getMax()):50;
                        if(sfx!=null) sfx.playDetent(0.76f,pct);
                    }
                }
                if(suppressCenterUp&&(key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_ENTER)) {
                    if(event.getAction()==KeyEvent.ACTION_UP)suppressCenterUp=false;
                    return true;
                }
                if(key==KeyEvent.KEYCODE_BACK) {
                    if(event.getAction()==KeyEvent.ACTION_UP && ! event.isCanceled()) {
                        if(sfx!=null) sfx.playBack();
                        if(bladeBack!=null){Runnable r=bladeBack;bladeBack=null;r.run();}
                        else closeBlade();
                    }
                    return true;
                }
                return false;
            });
            dialog.setOnDismissListener(d->{
                if(activeBlade==dialog){activeBlade=null;bladeHost=null;bladeBack=null;lastBladeTitle="";}
            });
            Window window=dialog.getWindow();
            if(window!=null){
                window.setBackgroundDrawableResource(android.R.color.transparent);
                window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                window.setDimAmount("solid".equals(uiSurfaceStyle())?.40f:.24f);
            }
            activeBlade=dialog;
            bladeHost=host;
            bladeHost.addView(panel,new FrameLayout.LayoutParams(-1,-1));
            dialog.show();
            if(window!=null){
                window.setLayout(Math.min(screenWidth-px(96),px(460)),WindowManager.LayoutParams.MATCH_PARENT);
                window.setGravity(Gravity.RIGHT|Gravity.TOP);
                if(window.getDecorView()!=null) applyViewSoundPolicy(window.getDecorView());
            }
            panel.setTranslationX(px(28)); panel.setAlpha(0f);
            panel.animate().translationX(0).alpha(1f).setDuration(150).start();
        } else {
            bladeHost.removeAllViews();
            bladeHost.addView(panel,new FrameLayout.LayoutParams(-1,-1));
            if(activeBlade!=null && activeBlade.getWindow()!=null && activeBlade.getWindow().getDecorView()!=null) {
                applyViewSoundPolicy(activeBlade.getWindow().getDecorView());
            }
            if(animate) {
                panel.setTranslationX(px(14)); panel.setAlpha(0.55f);
                panel.animate().translationX(0).alpha(1f).setDuration(110).start();
            }
        }
        applyViewSoundPolicy(panel);
        lastBladeTitle=title;
        if(initialFocus!=null) initialFocus.post(initialFocus::requestFocus);
    }
    private View buildMenuRow(String raw,boolean selected) {
        return buildMenuRow(raw,selected,true);
    }
    private View buildMenuRow(String raw,boolean selected,boolean actionable) {
        String text=raw==null?"":raw;
        if(text.startsWith("§")) {
            TextView sec=text(text.substring(1),13,store.flag("contrast")?accent():muted());
            sec.setTypeface(medium);
            sec.setLetterSpacing(0.08f);
            sec.setPadding(px(12),px(28),px(12),px(12));
            return sec;
        }
        int[] icon=null;
        if(text.startsWith("@")) {
            int pipe=text.indexOf('|');
            if(pipe>1) { icon=iconOps(text.substring(1,pipe)); text=text.substring(pipe+1); }
        }
        int radio=0;
        if(text.startsWith("✓ ")) { radio=1; text=text.substring(2); }
        else if(text.startsWith("○ ")) { radio=2; text=text.substring(2); }

        boolean external=text.endsWith(" ↗");
        if(external) text=text.substring(0,text.length()-2);

        String primary=text, secondary="", rightValue=null;
        if(text.contains("\n")) {
            int nl=text.indexOf("\n");
            secondary=text.substring(nl+1);
            text=text.substring(0,nl);
            primary=text;
        }
        int toggle=0;
        if(text.endsWith(": On")) { toggle=1; primary=text.substring(0,text.length()-4); }
        else if(text.endsWith(": Off")) { toggle=2; primary=text.substring(0,text.length()-5); }
        else if(text.endsWith(": On · paused")) { toggle=1; primary=text.substring(0,text.length()-13); secondary="Paused in Settings"; }
        else if(radio>0 && text.contains(" · ")) {
            int dot=text.indexOf(" · ");
            primary=text.substring(0,dot); secondary=text.substring(dot+3);
        } else if(text.contains(": ")) {
            int colon=text.indexOf(": ");
            primary=text.substring(0,colon); rightValue=text.substring(colon+2);
        }

        LinearLayout row=new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(px(14),px(18),px(14),px(18));
        row.setMinimumHeight(px(72));
        row.setBackground(glassControlDrawable(row,selected,13f,accent(),false,false));

        if(icon!=null) {
            ImageView iv=new ImageView(this);
            iv.setImageDrawable(new LineIcon(icon,selected?accent():0xD0FFFFFF));
            LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(px(22),px(22));
            ip.rightMargin=px(14);
            row.addView(iv,ip);
        }

        LinearLayout leftCol=new LinearLayout(this);
        leftCol.setOrientation(LinearLayout.VERTICAL);
        TextView titleView=text(primary,24,selected?foreground():0xEAFFFFFF);
        if(selected) titleView.setTypeface(medium);
        titleView.setMaxLines(2);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        leftCol.addView(titleView);
        if(!secondary.isEmpty()) {
            TextView subView=text(secondary,16,muted());
            subView.setMaxLines(1);
            subView.setEllipsize(android.text.TextUtils.TruncateAt.END);
            leftCol.addView(subView);
        }
        row.addView(leftCol,new LinearLayout.LayoutParams(0,-2,1f));
        row.setSoundEffectsEnabled(!muteSystem());

        if(toggle>0) {
            boolean on=toggle==1;
            FrameLayout track=new FrameLayout(this);
            track.setBackground(shapeRadius(on?accent():0x28FFFFFF,on?0:0x44FFFFFF,on?0:1,12));
            View thumb=new View(this);
            thumb.setBackground(shapeRadius(on?0xFF10141A:0xE0FFFFFF,0,0,9));
            FrameLayout.LayoutParams tp=new FrameLayout.LayoutParams(px(16),px(16),Gravity.CENTER_VERTICAL|(on?Gravity.RIGHT:Gravity.LEFT));
            tp.leftMargin=px(3); tp.rightMargin=px(3);
            track.addView(thumb,tp);
            LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(px(40),px(22));
            sp.leftMargin=px(10);
            row.addView(track,sp);
        } else if(radio>0) {
            boolean checked=radio==1;
            FrameLayout ring=new FrameLayout(this);
            ring.setBackground(shapeRadius(checked?accent():0x00000000,checked?accent():muted(),2,10));
            if(checked) {
                View dot=new View(this);
                dot.setBackground(shapeRadius(0xFF10141A,0,0,4));
                ring.addView(dot,new FrameLayout.LayoutParams(px(8),px(8),Gravity.CENTER));
            }
            LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(px(20),px(20));
            rp.leftMargin=px(10);
            row.addView(ring,rp);
        } else if(rightValue!=null) {
            TextView val=text(rightValue+(actionable?"  ›":""),17,selected?accent():muted());
            val.setSingleLine(true);
            val.setEllipsize(android.text.TextUtils.TruncateAt.END);
            val.setMaxWidth(px(165));
            LinearLayout.LayoutParams vp=new LinearLayout.LayoutParams(-2,-2);
            vp.leftMargin=px(8);
            row.addView(val,vp);
        } else if(actionable) {
            TextView chev=text(external?"↗":"›",19,selected?accent():muted());
            LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-2,-2);
            cp.leftMargin=px(8);
            row.addView(chev,cp);
        }
        return row;
    }
    private void menu(String title,List<String> labels,List<Runnable> actions,Runnable back) {
        boolean sameMenu=title.equals(lastBladeTitle) && activeBlade!=null && activeBlade.isShowing();
        LinearLayout box=createBladeContainer(title,back);
        ListView list=new ListView(this) {
            @Override public void playSoundEffect(int soundConstant) {
                if(muteSystem()) return;
                super.playSoundEffect(soundConstant);
            }
        };
        list.setSoundEffectsEnabled(!muteSystem());
        list.setDivider(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        list.setDividerHeight(px(5));
        list.setVerticalScrollBarEnabled(false);
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        final ArrayAdapter<String> adapter=new ArrayAdapter<String>(this,0,labels) {
            @Override public boolean areAllItemsEnabled() { return false; }
            @Override public boolean isEnabled(int position) {
                return position>=0 && position<actions.size() && actions.get(position)!=null && !labels.get(position).startsWith("§");
            }
            @Override public View getView(int position,View convert,ViewGroup parent) {
                boolean selected=list.isFocused() && list.getSelectedItemPosition()==position;
                boolean actionable=isEnabled(position);
                return buildMenuRow(labels.get(position),selected,actionable);
            }
        };
        list.setAdapter(adapter);
        final int[] prevSoundSel={-1};
        list.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id) {
                if(prevSoundSel[0]>=0 && pos!=prevSoundSel[0] && sfx!=null
                        && android.os.SystemClock.uptimeMillis()-lastUserNavMs<280L) {
                    sfx.playFocus(v!=null?v:list,screenWidth,pos>prevSoundSel[0]?1:-1);
                }
                prevSoundSel[0]=pos;
                lastBladeSelection=pos; adapter.notifyDataSetChanged();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p) { adapter.notifyDataSetChanged(); }
        });
        list.setOnFocusChangeListener((v,has)->adapter.notifyDataSetChanged());
        list.setOnItemClickListener((parent,view,position,id)->{
            if(position<0||position>=actions.size()||actions.get(position)==null)return;
            String rawLbl=labels.get(position);
            if(sfx!=null && rawLbl!=null && !rawLbl.startsWith("UI sounds: ") && !rawLbl.startsWith("✓ ") && !rawLbl.startsWith("○ ")) {
                if(rawLbl.endsWith(": On") || rawLbl.endsWith(": Off") || rawLbl.endsWith(": On · paused")) {
                    sfx.playDetent(0.76f,rawLbl.endsWith(": Off")?85:25);
                } else {
                    sfx.playSelectAtPan(0.76f);
                }
            } else if(sfx!=null && rawLbl!=null && (rawLbl.startsWith("✓ ") || rawLbl.startsWith("○ ")) && !"Sound profile".equals(title)) {
                sfx.playSelectAtPan(0.76f);
            }
            lastBladeSelection=position;
            int before=bladeGeneration;
            actions.get(position).run();
            if(bladeGeneration==before) closeBlade();
        });
        box.addView(list,new LinearLayout.LayoutParams(-1,0,1));

        int firstEnabled=0;
        for(int i=0;i<labels.size();i++) if(adapter.isEnabled(i)) { firstEnabled=i; break; }
        int targetIndex=(sameMenu && lastBladeSelection>=0 && lastBladeSelection<labels.size() && adapter.isEnabled(lastBladeSelection))
                ?lastBladeSelection:firstEnabled;
        lastBladeSelection=targetIndex;
        presentBladeView(title,box,back,list,!sameMenu);
        list.setSelection(targetIndex);
    }
    private void showBladePanel(String title,View body,String positiveLabel,Runnable onPositive,Runnable back,View focusTarget) {
        LinearLayout box=createBladeContainer(title,back);
        ScrollView scroll=scroll();
        LinearLayout content=new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(px(6),px(8),px(6),px(12));
        content.addView(body,new LinearLayout.LayoutParams(-1,-2));
        scroll.addView(content,new ScrollView.LayoutParams(-1,-2));
        box.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));

        LinearLayout footer=new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setPadding(px(4),px(12),px(4),0);
        Button save=button(positiveLabel,()->{
            int before=bladeGeneration;
            if(onPositive!=null)onPositive.run();
            if(bladeGeneration==before)closeBlade();
        });
        footer.addView(save,new LinearLayout.LayoutParams(0,px(52),1f));
        if(back!=null && onPositive!=null && !"Done".equals(positiveLabel)) {
            Button cancel=button("Cancel",back);
            LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(0,px(52),1f);
            cp.leftMargin=px(10);
            footer.addView(cancel,cp);
        }
        box.addView(footer,new LinearLayout.LayoutParams(-1,-2));
        if(focusTarget!=null) {
            if(focusTarget.getId()==View.NO_ID) focusTarget.setId(View.generateViewId());
            focusTarget.setNextFocusDownId(save.getId());
            save.setNextFocusUpId(focusTarget.getId());
        }
        presentBladeView(title,box,back,focusTarget!=null?focusTarget:save,true);
    }
    private void info(String title,String message) { info(title,message,bladeBack); }
    private void info(String title,String message,Runnable back) {
        TextView body=text(message,20,0xEAFFFFFF);
        body.setLineSpacing(px(5),1f);
        body.setTextIsSelectable(false);
        showBladePanel(title,body,"Done",back,back,null);
    }
    private GradientDrawable shapeRadius(int color,int stroke,int width,int radius) {
        GradientDrawable drawable=new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(px(radius));
        if(width>0) drawable.setStroke(px(width),stroke);
        return drawable;
    }
    private String onOff(boolean value){return value?"On":"Off";}
    private void launch(String pkg) {
        if(sfx!=null) sfx.playSelect(views.get(pkg),screenWidth);
        Intent intent=catalog.launch(pkg);
        if(intent==null){toast("This app is no longer available");dirty=true;return;}
        try{startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));store.recent(pkg);}
        catch(Exception e){toast("This app could not be opened");}
    }
    private void openTitle(RecommendationRepository.Item item) {
        if(sfx!=null) sfx.playSelect(getCurrentFocus(),screenWidth);
        Intent intent=item.intent();
        if(intent==null){toast("This title has no supported app link");return;}
        try{startActivity(intent);store.recent(item.source);}
        catch(Exception e){toast("This app could not open that title. Open the app instead.");}
    }
    private void startSafely(Intent intent) {
        try{startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}catch(Exception e){toast("This action is not available on this TV");}
    }
    private List<TvInputInfo> hdmiInputs() {
        List<TvInputInfo> result=new ArrayList<>();TvInputManager manager=(TvInputManager)getSystemService(TV_INPUT_SERVICE);
        if(manager==null)return result;
        for(TvInputInfo input:manager.getTvInputList())if(input.getType()==TvInputInfo.TYPE_HDMI&&input.getParentId()==null
                &&store.flag("input."+input.getId(),!input.isHidden(this)))result.add(input);
        Collections.sort(result,(a,b)->a.getId().compareTo(b.getId()));
        return result.size()>4?new ArrayList<>(result.subList(0,4)):result;
    }
    private String inputName(TvInputInfo input) {
        CharSequence custom=input.loadCustomLabel(this);if(custom!=null&&custom.length()>0)return custom.toString();
        CharSequence original=input.loadLabel(this);return original==null?"HDMI":original.toString();
    }
    private void openInput(String id) {
        Intent view=new Intent(Intent.ACTION_VIEW,TvContract.buildChannelUriForPassthroughInput(id));
        String[][] players={{"com.tcl.tv","com.tcl.tv.TVActivity"},{"com.android.tv","com.android.tv.MainActivity"},{"com.droidlogic.tv","com.droidlogic.tv.TvActivity"},{"com.droidlogic.android.tv","com.android.tv.TvActivity"}};
        for(String[] player:players) {
            Intent explicit=new Intent(view).setClassName(player[0],player[1]);
            if(explicit.resolveActivity(getPackageManager())==null)continue;
            try{startActivity(explicit);return;}catch(Exception ignored){}
        }
        startSafely(view);
    }
    private TextView text(String value,int size,int color) {
        TextView view=new TextView(this);view.setTypeface(regular);view.setText(value);view.setTextColor(color);
        view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,px(size));return view;
    }
    private Button button(String label,Runnable action) {
        Button button=new Button(this);
        button.setSoundEffectsEnabled(!muteSystem());
        button.setTypeface(medium);button.setText(label);button.setAllCaps(false);button.setTextColor(foreground());
        button.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,px(23));button.setPadding(px(12),0,px(12),0);
        button.setBackground(glassControlDrawable(button,false,16f,accent(),false,false));button.setId(View.generateViewId());
        button.setFocusableInTouchMode(true);
        button.setOnFocusChangeListener((v,has)->{
            button.setBackground(glassControlDrawable(button,has,16f,accent(),false,false));
            button.animate().scaleX(has&&!instantFocus()?1.03f:1f).scaleY(has&&!instantFocus()?1.03f:1f).setDuration(focusDuration(130)).start();
        });
        button.setOnClickListener(v->{
            boolean auditionPad="Focus Tap".equals(label)||"Select Bloom".equals(label)||"Menu Glide".equals(label)||"Back Settle".equals(label);
            if(sfx!=null && !auditionPad) {
                if("Cancel".equals(label)) sfx.playBack();
                else sfx.playSelect(button,screenWidth);
            }
            action.run();
        });return button;
    }
    private GradientDrawable shape(int color,int stroke,int width) {
        GradientDrawable drawable=new GradientDrawable();drawable.setColor(color);drawable.setCornerRadius(px(22));
        if(width>0)drawable.setStroke(px(width),stroke);return drawable;
    }
    private Drawable homeIconDrawable(Drawable original) {
        if(original==null)return null;
        try {
            int w=Math.max(1,original.getIntrinsicWidth()),h=Math.max(1,original.getIntrinsicHeight());
            float scale=Math.min(1f,512f/Math.max(w,h));w=Math.max(1,Math.round(w*scale));h=Math.max(1,Math.round(h*scale));
            android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(w,h,android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Rect bounds=new android.graphics.Rect(original.getBounds());
            try {original.setBounds(0,0,w,h);original.draw(new android.graphics.Canvas(bitmap));}
            finally {original.setBounds(bounds);}
            int left=w,top=h,right=-1,bottom=-1;
            int[] pixels=new int[w*h];bitmap.getPixels(pixels,0,w,0,0,w,h);
            for(int y=0;y<h;y++)for(int x=0;x<w;x++)if((pixels[y*w+x]>>>24)>16) {
                left=Math.min(left,x);right=Math.max(right,x);top=Math.min(top,y);bottom=Math.max(bottom,y);
            }
            if(right>=left&&bottom>=top)bitmap=android.graphics.Bitmap.createBitmap(bitmap,left,top,right-left+1,bottom-top+1);
            return new android.graphics.drawable.BitmapDrawable(getResources(),bitmap);
        } catch(Exception|OutOfMemoryError error) {return original;}
    }
    private Drawable fitDrawable(Drawable original,int width,int height) {
        try {
            android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(width,height,android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas=new android.graphics.Canvas(bitmap);
            int w=Math.max(1,original.getIntrinsicWidth()),h=Math.max(1,original.getIntrinsicHeight());
            float ratio=Math.max((float)width/w,(float)height/h);
            int dw=Math.round(w*ratio),dh=Math.round(h*ratio);
            android.graphics.Rect bounds=new android.graphics.Rect(original.getBounds());
            original.setBounds((width-dw)/2,(height-dh)/2,(width+dw)/2,(height+dh)/2);
            original.draw(canvas);original.setBounds(bounds);
            return new android.graphics.drawable.BitmapDrawable(getResources(),bitmap);
        } catch(Exception|OutOfMemoryError error) {return new android.graphics.drawable.ColorDrawable(0xFF26374C);}
    }
    private ScrollView scroll() {
        ScrollView view=new ScrollView(this) {
            @Override public boolean executeKeyEvent(KeyEvent event) {
                int key=event.getKeyCode();
                if(this==homeScroll && (key==KeyEvent.KEYCODE_DPAD_UP||key==KeyEvent.KEYCODE_DPAD_DOWN)) {
                    // A vertical press changes sections or stays put; it never pans behind stationary focus.
                    if(event.getAction()==KeyEvent.ACTION_DOWN) {
                        View focused=findFocus();
                        int direction=key==KeyEvent.KEYCODE_DPAD_UP?View.FOCUS_UP:View.FOCUS_DOWN;
                        View next=focused!=null?focused.focusSearch(direction):null;
                        View ancestor=next;
                        while(ancestor!=null && ancestor!=this)
                            ancestor=ancestor.getParent() instanceof View?(View)ancestor.getParent():null;
                        if(next!=null && next!=focused && ancestor==this)next.requestFocus(direction);
                        else if(direction==View.FOCUS_UP) {
                            if(glanceWidget!=null && glanceWidget.getVisibility()==View.VISIBLE) glanceWidget.requestFocus();
                            else if(!topPillViews.isEmpty()) topPillViews.get(0).requestFocus();
                        }
                    }
                    return true;
                }
                return super.executeKeyEvent(event);
            }
            @Override public void requestChildFocus(View child,View focused) {
                super.requestChildFocus(child,focused);
                if(this==homeScroll)post(()->{if(this==homeScroll)positionHomeSection(focused);});
                else if(this==drawerScroll)post(()->{if(this==drawerScroll)centerDrawerChild(focused);});
            }
            @Override protected int computeScrollDeltaToGetChildRectOnScreen(android.graphics.Rect rect) {
                return (this==homeScroll||this==drawerScroll)?0:super.computeScrollDeltaToGetChildRectOnScreen(rect);
            }
            @Override public boolean requestChildRectangleOnScreen(View child,android.graphics.Rect rect,boolean immediate) {
                return (this==homeScroll||this==drawerScroll)?false:super.requestChildRectangleOnScreen(child,rect,immediate);
            }
            @Override public void playSoundEffect(int soundConstant) {
                if(muteSystem()) return;
                super.playSoundEffect(soundConstant);
            }
            @Override protected void onScrollChanged(int l,int t,int oldl,int oldt) {
                super.onScrollChanged(l,t,oldl,oldt);
                if(t!=oldt && glassRefractionEnabled()) refreshGlassSurfaces();
            }
        };
        view.setSoundEffectsEnabled(!muteSystem());
        view.setFillViewport(false);view.setClipToPadding(false);
        view.setClipChildren(false);
        view.setVerticalFadingEdgeEnabled(false);
        view.setVerticalScrollBarEnabled(false);view.setOverScrollMode(View.OVER_SCROLL_NEVER);return view;
    }
    private FrameLayout.LayoutParams at(int x,int y,int width,int height) {
        FrameLayout.LayoutParams params=new FrameLayout.LayoutParams(width,height);params.leftMargin=x;params.topMargin=y;return params;
    }
    private void hideKeyboard(){View focus=getCurrentFocus();if(focus!=null)((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(focus.getWindowToken(),0);}
    private void toast(String message){Toast.makeText(this,message,Toast.LENGTH_LONG).show();}
}
