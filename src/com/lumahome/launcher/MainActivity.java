package com.lumahome.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
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
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewConfiguration;
import android.view.Window;
import android.view.WindowManager;
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
    private static final int PICK_IMAGE=40, TV_LISTINGS=41;
    private static final String TV_LISTINGS_PERMISSION="android.permission.READ_TV_LISTINGS";
    private LauncherStore store;
    private AppCatalog catalog;
    private ImageLoader images;
    private RecommendationRepository recommendations;
    private final Handler handler=new Handler();
    private List<AppCatalog.App> apps=new ArrayList<>();
    private final Map<String,AppCatalog.App> byPackage=new HashMap<>();
    private final Map<String,View> views=new HashMap<>();
    private final List<View> appViews=new ArrayList<>(), dockViews=new ArrayList<>(), contentViews=new ArrayList<>();
    private final List<List<View>> appRows=new ArrayList<>();
    private final List<List<View>> contentRows=new ArrayList<>();
    private FrameLayout root, content, header;
    private LinearLayout contentRail, drawerPage, homePage, recentSection;
    private ScrollView drawerScroll, homeScroll;
    private View clockPanel, focusedFavorite;
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
    private String lastFocus="", moving="", imageTarget="", search="";
    private int screenWidth, screenHeight, contentHeight, serial;
    private float scale;
    private final Typeface medium=Typeface.create("sans-serif-medium",Typeface.NORMAL);

    private final BroadcastReceiver packageChanges=new BroadcastReceiver() {
        @Override public void onReceive(Context context,Intent intent) { dirty=true; }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        store=new LauncherStore(this); catalog=new AppCatalog(this);
        images=new ImageLoader(this); recommendations=new RecommendationRepository(this);
        if (state!=null) { imageTarget=state.getString("imageTarget",""); drawer=state.getBoolean("drawer"); }
        refreshApps(); applyIntent(getIntent()); initializeFavorites();
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
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent); applyIntent(intent);
        drawer=false; search=""; moving=""; refreshApps(); render();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("imageTarget",imageTarget); state.putBoolean("drawer",drawer);
        super.onSaveInstanceState(state);
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        handler.removeCallbacks(heroRotation);
        if(focused&&heroPanel!=null&&store.flag("hero_rotate"))handler.postDelayed(heroRotation,10000);
    }
    @Override protected void onPause() {
        stopHeroVideo();handler.removeCallbacks(heroRotation);super.onPause();
    }
    @Override protected void onDestroy() {
        destroyed=true;paletteWorker.shutdownNow(); handler.removeCallbacksAndMessages(null);
        unregisterReceiver(packageChanges); images.close(); recommendations.close(); super.onDestroy();
    }
    @Override public void onBackPressed() {
        if(heroVideo!=null){stopHeroVideo();return;}
        if(!drawer&&header!=null&&header.hasFocus()&&!appViews.isEmpty()){heroWantsFocus=false;appViews.get(0).requestFocus();return;}
        if (!moving.isEmpty()) { moving=""; render(); return; }
        if (drawer) { hideKeyboard(); drawer=false; search=""; lastFocus=ALL; render(); }
    }
    private void refreshApps() {
        apps=catalog.all(); byPackage.clear();
        for (AppCatalog.App app:apps) byPackage.put(app.pkg,app);
        Collections.sort(apps,(a,b)->name(a.pkg).compareToIgnoreCase(name(b.pkg)));
    }
    private void initializeFavorites() {
        if (store.flag("favorites_initialized")) return;
        List<String> favorites=new ArrayList<>();
        for (String pkg:Arrays.asList("com.google.android.youtube.tv","com.example.onyx",
                "com.streamflixreborn.streamflix","flix.com.vision","com.esaba.downloader",
                "com.alphainventor.filemanager")) if (byPackage.containsKey(pkg)) favorites.add(pkg);
        for (String pkg:Apps.LIST) if (favorites.size()<6 && byPackage.containsKey(pkg) && !favorites.contains(pkg)) favorites.add(pkg);
        for (AppCatalog.App app:apps) if (favorites.size()<6 && !favorites.contains(app.pkg)) favorites.add(app.pkg);
        store.list("favorites",favorites); store.setFlag("favorites_initialized",true);
    }
    private void applyIntent(Intent intent) {
        if (intent==null || !intent.hasExtra("apps")) return;
        String stamp=intent.getStringExtra("stamp"), raw=intent.getStringExtra("apps");
        if (stamp!=null && stamp.equals(store.value("intent_stamp",""))) return;
        if (stamp!=null) store.set("intent_stamp",stamp);
        if (raw==null || raw.equals("auto")) { store.setFlag("favorites_initialized",false); initializeFavorites(); return; }
        List<String> favorites=new ArrayList<>();
        for (String pkg:raw.split("[,\\s]+")) if (byPackage.containsKey(pkg) && !favorites.contains(pkg)) favorites.add(pkg);
        store.list("favorites",favorites); store.setFlag("favorites_initialized",true);
    }
    private List<String> favorites() {
        List<String> result=new ArrayList<>();
        for (String pkg:store.list("favorites")) if (byPackage.containsKey(pkg) && !result.contains(pkg)) result.add(pkg);
        return result;
    }
    private String name(String pkg) {
        AppCatalog.App app=byPackage.get(pkg);
        return store.value("name."+pkg,app==null?pkg:app.originalName);
    }
    private int px(float value) { return Math.max(1,Math.round(value*scale)); }
    private int columns() { return store.number("columns",6); }
    private int innerWidth() { return screenWidth-px(192); }
    private int drawerWidth() { return screenWidth-px(96); }
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
        else wallpaperSeed="forest".equals(style)?0xFF276849:"ocean".equals(style)?0xFF286A91:"midnight".equals(style)?0xFF465B9A:"ember".equals(style)?0xFF9A6A52:0;
    }
    private void extractWallpaperColors(android.graphics.Bitmap bitmap){
        final String key=paletteKey();
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

    private void render() {
        if (destroyed) return;
        stopHeroVideo();handler.removeCallbacks(heroRotation);heroPanel=null;heroPrimary=null;
        refreshWallpaperPalette();
        final String desiredFocus=lastFocus;
        serial++; views.clear(); appViews.clear();appRows.clear(); dockViews.clear(); contentViews.clear();contentRows.clear();
        focusedFavorite=null; drawerScroll=null;homeScroll=null;homePage=null;drawerPage=null;recentSection=null;
        screenWidth=getResources().getDisplayMetrics().widthPixels;
        screenHeight=getResources().getDisplayMetrics().heightPixels;
        scale=Math.min(screenWidth/1920f,screenHeight/1080f);
        root=new FrameLayout(this); root.setBackgroundColor(0xFF0B101A);
        applyBackground();
        contentHeight=screenHeight;
        content=new FrameLayout(this);
        content.setClipChildren(false);
        root.setClipChildren(false);
        root.addView(content,at(0,0,screenWidth,screenHeight));
        if (drawer) renderDrawer(); else renderHome();
        buildDock();
        setContentView(root);
        wireNavigation();
        View restore=views.get(desiredFocus);
        if (restore==null) restore=!appViews.isEmpty()?appViews.get(0):dockViews.get(0);
        final View focus=restore;
        root.post(()->{ if (!destroyed && focus.isAttachedToWindow()) focus.requestFocus(); });
    }
    private void applyBackground() {
        String style=store.value("background","ember");
        int[] colors;
        switch (style) {
            case "ocean": colors=new int[]{0xFF102D40,0xFF07121F}; break;
            case "forest": colors=new int[]{0xFF17382E,0xFF080F16}; break;
            case "midnight": colors=new int[]{0xFF202C49,0xFF090D18}; break;
            case "solid_black": colors=new int[]{0xFF000000,0xFF000000}; break;
            case "solid_slate": colors=new int[]{0xFF18202B,0xFF18202B}; break;
            default: colors=new int[]{0xFF33211D,0xFF140E16}; break;
        }
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,colors));
        if("ember".equals(style)) {
            GradientDrawable base=new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{0xFF15100F,0xFF0A0809,0xFF050405});
            GradientDrawable warm=new GradientDrawable();warm.setGradientType(GradientDrawable.RADIAL_GRADIENT);warm.setGradientCenter(.08f,-.12f);warm.setGradientRadius(px(1250));warm.setColors(new int[]{0x36FF8048,0x00FF8048});
            GradientDrawable plum=new GradientDrawable();plum.setGradientType(GradientDrawable.RADIAL_GRADIENT);plum.setGradientCenter(1f,1.1f);plum.setGradientRadius(px(1000));plum.setColors(new int[]{0x2EAA3C8C,0x00AA3C8C});
            root.setBackground(new android.graphics.drawable.LayerDrawable(new Drawable[]{base,warm,plum}));
        }
        if ("photo".equals(style)) {
            String path=store.value("background_image","");
            if (!path.isEmpty()) {
                ImageView image=new ImageView(this){
                    @Override public void setImageBitmap(android.graphics.Bitmap bitmap){super.setImageBitmap(bitmap);if(bitmap!=null&&!destroyed)extractWallpaperColors(bitmap);}
                };image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                root.addView(image,new FrameLayout.LayoutParams(-1,-1));
                images.show(image,path,Math.min(screenWidth,1920),Math.min(screenHeight,1080));
            }
        }
        View shade=new View(this);
        int dim=store.flag("contrast")?Math.max(55,store.number("dimming",0)):store.number("dimming",0);
        shade.setBackgroundColor(Color.argb(Math.round(dim*2.55f),0,0,0));
        root.addView(shade,new FrameLayout.LayoutParams(-1,-1));
    }
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
        TextView title=text("Home",34,foreground()); title.setTypeface(medium);
        heading.addView(title,at(0,0,px(500),px(52)));
        TextView hint=text(moving.isEmpty()?"Hold OK to personalize · All Apps keeps everything accessible":"Move with arrows · OK or Back to finish",21,muted());
        hint.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);
        heading.addView(hint,at(px(480),px(5),innerWidth()-px(480),px(44)));
        LinearLayout.LayoutParams headingParams=new LinearLayout.LayoutParams(-1,px(64));headingParams.topMargin=px(10);
        homePage.addView(heading,headingParams);
        List<String> favorite=favorites();
        HorizontalScrollView appsScroll=new HorizontalScrollView(this);appsScroll.setHorizontalScrollBarEnabled(false);
        appsScroll.setClipChildren(false);appsScroll.setClipToPadding(false);appsScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout appsRow=new LinearLayout(this);appsRow.setClipChildren(false);appsRow.setClipToPadding(false);
        appsRow.setPadding(px(32),px(12),px(32),px(14));
        List<View> navigationRow=new ArrayList<>();appRows.add(navigationRow);
        int iconSize=px(columns()==4?176:columns()==8?112:144);
        for(int i=0;i<favorite.size();i++) {
            View tile=appTile(favorite.get(i),iconSize,iconSize,false);
            LinearLayout.LayoutParams tileParams=new LinearLayout.LayoutParams(iconSize,iconSize+px(58));
            if(i+1<favorite.size())tileParams.rightMargin=px(36);
            appsRow.addView(tile,tileParams);appViews.add(tile);navigationRow.add(tile);
        }
        appsScroll.addView(appsRow);
        LinearLayout.LayoutParams appsParams=new LinearLayout.LayoutParams(screenWidth,-2);
        appsParams.leftMargin=-px(96);appsParams.rightMargin=-px(96);
        homePage.addView(appsScroll,appsParams);
        if (favorite.isEmpty()) {
            TextView empty=text("Your home, your choices.\nOpen All Apps and hold OK to add an app to Home.",28,muted());
            empty.setGravity(Gravity.CENTER);homePage.addView(empty,new LinearLayout.LayoutParams(-1,px(190)));
        }
        contentRail=new LinearLayout(this); contentRail.setOrientation(LinearLayout.VERTICAL);
        contentRail.setClipChildren(false);contentRail.setClipToPadding(false);
        LinearLayout.LayoutParams railParams=new LinearLayout.LayoutParams(screenWidth,-2);
        railParams.leftMargin=-px(96);railParams.rightMargin=-px(96);railParams.topMargin=px(12);
        homePage.addView(contentRail,railParams);
        root.post(this::updateRecommendationRows);
    }
    private void updateHeader() {
        if (header==null || drawer) return;
        stopHeroVideo();handler.removeCallbacks(heroRotation);heroPanel=null;heroPrimary=null;
        header.removeAllViews();header.getLayoutParams().height=px(330);header.requestLayout();
        LinearLayout clock=new LinearLayout(this); clock.setOrientation(LinearLayout.VERTICAL);clock.setPadding(px(96),px(36),px(96),0);
        TextClock date=new TextClock(this); date.setFormat12Hour("EEEE, d MMMM"); date.setFormat24Hour("EEEE, d MMMM");
        date.setTextColor(muted()); date.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,px(27));
        TextClock time=new TextClock(this); time.setFormat12Hour("h:mm"); time.setFormat24Hour("H:mm");
        int size=store.number("clock_size",176);
        time.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,px(size)); time.setTextColor(foreground());
        time.setTypeface(Typeface.create("sans-serif-light",Typeface.NORMAL)); time.setIncludeFontPadding(false);
        clock.addView(date); clock.addView(time); header.addView(clock,new FrameLayout.LayoutParams(-1,-1)); clockPanel=clock;
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
    private void renderDrawer() {
        ScrollView page=scroll();
        drawerScroll=page;
        page.setClipChildren(false);
        content.addView(page,new FrameLayout.LayoutParams(-1,-1));
        drawerPage=new LinearLayout(this); drawerPage.setOrientation(LinearLayout.VERTICAL);
        drawerPage.setPadding(px(48),px(92),px(48),px(96));drawerPage.setClipChildren(false);drawerPage.setClipToPadding(false);
        page.addView(drawerPage,new ScrollView.LayoutParams(-1,-2));
        FrameLayout top=new FrameLayout(this);
        LinearLayout clock=new LinearLayout(this);clock.setOrientation(LinearLayout.VERTICAL);
        TextClock date=new TextClock(this);date.setFormat12Hour("EEEE, d MMMM");date.setFormat24Hour("EEEE, d MMMM");date.setTextColor(muted());date.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,px(30));
        TextClock time=new TextClock(this);time.setFormat12Hour("h:mm");time.setFormat24Hour("H:mm");time.setTextColor(foreground());time.setIncludeFontPadding(false);time.setTypeface(Typeface.create("sans-serif-light",Typeface.NORMAL));time.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,px(store.number("clock_size",176)));
        clock.addView(date);clock.addView(time);top.addView(clock,new FrameLayout.LayoutParams(-1,-1));
        Button back=button("Back to Home",()->{ hideKeyboard(); drawer=false; search=""; lastFocus=ALL; render(); });
        top.addView(back,at(drawerWidth()-px(330),0,px(330),px(66)));
        drawerPage.addView(top,new LinearLayout.LayoutParams(-1,px(266)));
        EditText filter=new EditText(this); filter.setId(View.generateViewId()); filter.setSingleLine(true);
        filter.setTextColor(foreground()); filter.setHintTextColor(muted()); filter.setHint("Search all apps");
        filter.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,px(28));
        filter.setPadding(px(24),0,px(24),0); filter.setBackground(shape(themed(0x18FFFFFF,0xBB182435),themed(0x33FFFFFF,0xFF40516A),1));
        filter.setText(search); filter.setSelectAllOnFocus(true);
        filter.setOnClickListener(v->((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(filter,InputMethodManager.SHOW_IMPLICIT));
        LinearLayout.LayoutParams searchParams=new LinearLayout.LayoutParams(-1,px(64));searchParams.topMargin=px(16);searchParams.bottomMargin=px(36);
        drawerPage.addView(filter,searchParams);
        FrameLayout grid=new FrameLayout(this);
        grid.setClipChildren(false);grid.setClipToPadding(false);
        drawerPage.addView(grid,new LinearLayout.LayoutParams(-1,-2));
        filter.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s,int start,int count,int after) {}
            public void onTextChanged(CharSequence s,int start,int before,int count) { search=s.toString(); populateDrawer(grid,filter); }
            public void afterTextChanged(Editable e) {}
        });
        views.put("search",filter); populateDrawer(grid,filter);
    }
    private void populateDrawer(FrameLayout grid,EditText searchView) {
        List<String> visible=new ArrayList<>(); String query=search.toLowerCase(Locale.ROOT).trim();
        for (AppCatalog.App app:apps) if (name(app.pkg).toLowerCase(Locale.ROOT).contains(query) || app.originalName.toLowerCase(Locale.ROOT).contains(query)) visible.add(app.pkg);
        appViews.clear();appRows.clear();grid.removeAllViews();
        LinearLayout groups=new LinearLayout(this);groups.setOrientation(LinearLayout.VERTICAL);
        groups.setClipChildren(false);groups.setClipToPadding(false);
        List<String> regular=new ArrayList<>(),utilities=new ArrayList<>();
        for(String pkg:visible) {
            if(store.flag("group_utilities",true)&&utility(pkg))utilities.add(pkg);else regular.add(pkg);
        }
        if(!regular.isEmpty())groups.addView(gridRows(regular,true),new LinearLayout.LayoutParams(-1,-2));
        if(!utilities.isEmpty()) {
            TextView label=text("TV utilities",26,muted());
            LinearLayout.LayoutParams heading=new LinearLayout.LayoutParams(-1,px(46));heading.topMargin=regular.isEmpty()?0:px(18);
            groups.addView(label,heading);groups.addView(gridRows(utilities,true),new LinearLayout.LayoutParams(-1,-2));
        }
        grid.addView(groups,new FrameLayout.LayoutParams(-1,-2));
        for (int i=0;i<Math.min(columns(),appViews.size());i++) appViews.get(i).setNextFocusUpId(searchView.getId());
        wireNavigation();
        if (visible.isEmpty()) {
            TextView empty=text("No apps match your search.",28,muted()); empty.setGravity(Gravity.CENTER);
            grid.removeAllViews(); grid.addView(empty,new FrameLayout.LayoutParams(-1,px(150)));
        }
    }
    private LinearLayout gridRows(List<String> packages,boolean allApps) {
        LinearLayout rows=new LinearLayout(this); rows.setOrientation(LinearLayout.VERTICAL); rows.setClipChildren(false);rows.setClipToPadding(false);rows.setPadding(0,px(12),0,px(14));
        int width=allApps?drawerWidth():innerWidth(), count=columns(), gap=px(36);
        int tileWidth=(width-gap*(count-1))/count, tileHeight=tileWidth*9/16;
        LinearLayout row=null;
        List<View> navigationRow=null;
        for (int i=0;i<packages.size();i++) {
            if (i%count==0) {
                row=new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);row.setClipChildren(false);row.setClipToPadding(false);
                LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,tileHeight+px(58)); lp.bottomMargin=px(21); rows.addView(row,lp);
                navigationRow=new ArrayList<>();appRows.add(navigationRow);
            }
            View tile=appTile(packages.get(i),tileWidth,tileHeight,allApps);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(tileWidth,tileHeight+px(58));
            if (i%count!=count-1) lp.rightMargin=gap;
            row.addView(tile,lp); appViews.add(tile);navigationRow.add(tile);
        }
        return rows;
    }
    private View appTile(String pkg,int width,int height,boolean allApps) {
        LinearLayout tile=new LinearLayout(this); tile.setOrientation(LinearLayout.VERTICAL);
        tile.setId(View.generateViewId()); tile.setTag(pkg); tile.setFocusable(true); tile.setClickable(true);
        tile.setFocusableInTouchMode(true);
        tile.setClipChildren(false);tile.setClipToPadding(false);
        FrameLayout picture=new FrameLayout(this);
        picture.setClipToOutline(true);
        ImageView image=new ImageView(this); image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        String artwork=store.value("art."+pkg,"");
        AppCatalog.Artwork appArt=catalog.artwork(pkg,allApps?store.flag("icon."+pkg):!store.flag("home_banner."+pkg));
        final boolean bannerStyle=!artwork.isEmpty()||appArt.banner;
        GradientDrawable tileSurface=shape(bannerStyle?0xFF1E1A1C:0x13FFFFFF,0,0);
        if(!allApps)tileSurface.setShape(GradientDrawable.OVAL);
        picture.setBackground(tileSurface);
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
        } else {
            LinearLayout plain=new LinearLayout(this);plain.setOrientation(LinearLayout.VERTICAL);plain.setGravity(Gravity.CENTER);
            if(appArt.drawable!=null)image.setImageDrawable(fitDrawable(appArt.drawable,px(64),px(64)));
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);plain.addView(image,new LinearLayout.LayoutParams(px(64),px(64)));
            TextView inside=text(name(pkg),24,foreground());inside.setGravity(Gravity.CENTER);inside.setSingleLine(true);inside.setEllipsize(android.text.TextUtils.TruncateAt.END);inside.setPadding(px(12),0,px(12),0);
            LinearLayout.LayoutParams labelParams=new LinearLayout.LayoutParams(-1,px(40));labelParams.topMargin=px(8);plain.addView(inside,labelParams);
            picture.addView(plain,new FrameLayout.LayoutParams(-1,-1));
        }
        tile.addView(picture,new LinearLayout.LayoutParams(-1,height));
        String label=name(pkg);
        TextView caption=text(label,24,foreground()); caption.setSingleLine(true);
        caption.setEllipsize(android.text.TextUtils.TruncateAt.END); caption.setGravity(Gravity.CENTER);
        tile.addView(caption,new LinearLayout.LayoutParams(-1,px(48)));
        caption.setVisibility((!allApps||bannerStyle)&&store.flag("labels",false)?View.VISIBLE:View.INVISIBLE);
        tile.setContentDescription(name(pkg)+(allApps?"":" home app"));
        tile.setOnFocusChangeListener((v,has)->{
            GradientDrawable surface=shape(bannerStyle?0xFF1E1A1C:has?0x38FFFFFF:0x13FFFFFF,has&&store.flag("contrast")?accent():0,has&&store.flag("contrast")?3:0);
            if(!allApps)surface.setShape(GradientDrawable.OVAL);
            picture.setBackground(surface);
            picture.animate().scaleX(has?1.1f:1f).scaleY(has?1.1f:1f).translationZ(has?px(26):0).setDuration(140).start();
            caption.setVisibility((!allApps||bannerStyle)&&(has||store.flag("labels",false))?View.VISIBLE:View.INVISIBLE);
            if (has) {
                lastFocus=pkg;
                if (!drawer) {heroWantsFocus=false;resizeHero(false);focusedFavorite=tile;wireNavigation();revealHorizontalFocus(tile);}
            }
        });
        tile.setOnClickListener(v->launch(pkg));
        tile.setOnLongClickListener(v->{ appMenu(pkg); return true; });
        final boolean[] held={false};
        tile.setOnKeyListener((v,code,event)->{
            boolean down=event.getAction()==KeyEvent.ACTION_DOWN;
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
        views.put(pkg,tile); return tile;
    }
    private void buildDock() {
        LinearLayout dock=new LinearLayout(this); dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setPadding(px(12),px(12),px(12),px(12)); dock.setBackground(shape(themed(0xB3161113,0xDB101A28),themed(0x22FFFFFF,0x6647556D),1));
        LinearLayout.LayoutParams place=new LinearLayout.LayoutParams(-1,px(132));place.topMargin=px(28);place.bottomMargin=px(24);
        (drawer?drawerPage:homePage).addView(dock,place);
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
        tile.setId(View.generateViewId()); tile.setFocusable(true); tile.setClickable(true); tile.setContentDescription(label);
        tile.setFocusableInTouchMode(true);
        if (symbol!=null) { ImageView image=new ImageView(this); image.setImageDrawable(new LineIcon(symbol)); tile.addView(image,new LinearLayout.LayoutParams(px(30),px(30))); }
        TextView text=text(label,24,foreground()); text.setSingleLine(true); text.setEllipsize(android.text.TextUtils.TruncateAt.END); text.setGravity(Gravity.CENTER);
        tile.addView(text,new LinearLayout.LayoutParams(-1,px(44)));
        tile.setBackground(shape(0x00000000,0,0));
        tile.setOnFocusChangeListener((v,has)->{
            tile.setBackground(shape(has?themed(0x38FFFFFF,0xFF2A405B):0x00000000,has?themed(0x66FFFFFF,accent()):0,has?1:0));
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
        tile.setOnClickListener(v->action.run()); views.put(key,tile); dockViews.add(tile);
    }
    private void wireNavigation() {
        List<List<View>> rows=new ArrayList<>();contentViews.clear();
        for(List<View> row:contentRows)if(!row.isEmpty()){rows.add(row);contentViews.addAll(row);}
        for(int r=0;r<appRows.size();r++) {
            List<View> row=appRows.get(r);
            for(int c=0;c<row.size();c++) {
                View v=row.get(c);
                v.setNextFocusLeftId(row.get(Math.max(0,c-1)).getId());
                v.setNextFocusRightId(row.get(Math.min(row.size()-1,c+1)).getId());
                if(r==0&&!drawer&&heroPrimary!=null)v.setNextFocusUpId(heroPrimary.getId());
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
        final int current=serial;
        final int size=recommendationSize(),cardWidth=recommendationWidth(size),artHeight=cardWidth*9/16;
        List<String> sources=new ArrayList<>(favorites());
        for(AppCatalog.App app:apps)if(!sources.contains(app.pkg))sources.add(app.pkg);
        if(store.flag("recommendations_master"))for(String pkg:sources) {
            if(!store.recommendations(pkg))continue;
            LinearLayout section=new LinearLayout(this);section.setOrientation(LinearLayout.VERTICAL);
            section.setClipChildren(false);section.setClipToPadding(false);section.setVisibility(View.GONE);
            LinearLayout.LayoutParams sectionParams=new LinearLayout.LayoutParams(-1,-2);
            sectionParams.topMargin=px(24);sectionParams.bottomMargin=px(48);
            contentRail.addView(section,sectionParams);
            List<View> rowViews=new ArrayList<>();contentRows.add(rowViews);
            recommendations.load(pkg,feed->{
                if(destroyed||drawer||current!=serial||section.getParent()!=contentRail)return;
                if(!HomePolicy.recommendations(store.flag("recommendations_master"),store.recommendations(pkg),!feed.items.isEmpty()))return;
                TextView label=text("From "+name(pkg),22,muted());label.setPadding(px(96),0,px(96),0);
                section.addView(label,new LinearLayout.LayoutParams(-1,px(44)));
                HorizontalScrollView scroll=new HorizontalScrollView(this);scroll.setHorizontalScrollBarEnabled(false);
                scroll.setClipChildren(false);scroll.setClipToPadding(false);scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
                LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);row.setClipToPadding(false);row.setPadding(px(32),px(8),px(32),px(8));
            for (RecommendationRepository.Item item:feed.items) {
                LinearLayout card=new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setId(View.generateViewId()); card.setFocusable(true); card.setClickable(true);
                card.setFocusableInTouchMode(true);card.setClipChildren(false);card.setClipToPadding(false);
                FrameLayout picture=new FrameLayout(this);picture.setClipToOutline(true);
                picture.setBackground(shape(Color.BLACK,0,0));
                card.addView(picture,new LinearLayout.LayoutParams(-1,px(artHeight)));
                final int[] naturalWidth={px(cardWidth)};
                if (!item.artwork.isEmpty()) {
                    ImageView image=new ImageView(this) {
                        @Override public void setImageBitmap(android.graphics.Bitmap bitmap) {
                            super.setImageBitmap(bitmap);
                            if(bitmap==null||destroyed||current!=serial||drawer)return;
                            naturalWidth[0]=artworkWidth(bitmap.getWidth(),bitmap.getHeight(),px(artHeight),screenWidth-px(96));
                            android.view.ViewGroup.LayoutParams params=card.getLayoutParams();
                            if(params!=null && params.width!=naturalWidth[0]) {params.width=naturalWidth[0];card.setLayoutParams(params);}
                        }
                    };image.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    picture.addView(image,new FrameLayout.LayoutParams(-1,-1));
                    images.show(image,item.artwork,screenWidth-px(96),px(artHeight));
                }
                TextView title=text(item.title,recommendationTextSize(size),foreground()); title.setMaxLines(1); title.setEllipsize(android.text.TextUtils.TruncateAt.END);
                title.setGravity(Gravity.CENTER_VERTICAL);title.setPadding(px(4),px(4),px(4),0);
                card.addView(title,new LinearLayout.LayoutParams(-1,px(recommendationTitleHeight(size))));
                card.setContentDescription(item.title+" · "+name(pkg)); card.setOnClickListener(v->openTitle(item));
                card.setOnFocusChangeListener((v,has)->{
                    focusRecommendationPicture(picture,has);
                    title.setTextColor(has?foreground():muted());
                    if(has)revealHorizontalFocus(card);
                });
                card.addOnLayoutChangeListener((v,left,top,right,bottom,oldLeft,oldTop,oldRight,oldBottom)->{
                    if(v.hasFocus()&&(left!=oldLeft||right!=oldRight)){focusRecommendationPicture(picture,true);revealHorizontalFocus(v);}
                });
                card.setOnKeyListener((v,key,event)->{
                    if (key==KeyEvent.KEYCODE_BACK) { if(event.getAction()==KeyEvent.ACTION_UP){View source=views.get(pkg);if(source!=null)source.requestFocus();else if(focusedFavorite!=null)focusedFavorite.requestFocus();} return true; } return false;
                });
                LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(naturalWidth[0],px(artHeight+recommendationTitleHeight(size)));
                if(rowViews.size()+1<feed.items.size())lp.rightMargin=px(24);
                row.addView(card,lp); rowViews.add(card);
            }
                scroll.addView(row);section.addView(scroll);section.setVisibility(View.VISIBLE);wireNavigation();
            });
        }
        showRecentRow();wireNavigation();
    }
    private int artworkWidth(int width,int height,int targetHeight,int limit) {
        if(width<=0||height<=0)return Math.min(limit,targetHeight*16/9);
        return Math.max(px(64),Math.min(limit,(int)Math.round((double)width*targetHeight/height)));
    }
    private void focusRecommendationPicture(FrameLayout picture,boolean focused) {
        // Foreground is drawn after the artwork, keeping the complete ring visible.
        picture.setForeground(focused?shape(Color.TRANSPARENT,accent(),2):null);
        picture.setForegroundGravity(Gravity.FILL);
        float lift=focused?Math.min(1.035f,1f+(float)px(24)/Math.max(1,picture.getWidth())):1f;
        picture.animate().scaleX(lift).scaleY(lift).translationZ(focused?px(12):0).setDuration(140).start();
    }
    private void revealHorizontalFocus(View view) {
        // Reveal room for the focus lift, not only the unscaled tile bounds.
        view.post(()->{
            if(!view.hasFocus()||!view.isAttachedToWindow())return;
            android.graphics.Rect area=new android.graphics.Rect(-px(24),0,view.getWidth()+px(24),view.getHeight());
            view.requestRectangleOnScreen(area,false);
        });
    }
    private void showRecentRow() {
        if(recentSection!=null && recentSection.getParent()==homePage)homePage.removeView(recentSection);
        recentSection=null;
        if(homePage==null || drawer || !store.flag("recents"))return;
        List<String> recent=new ArrayList<>();
        for(String pkg:store.list("recent_apps"))if(byPackage.containsKey(pkg))recent.add(pkg);
        if(recent.isEmpty())return;
        recentSection=new LinearLayout(this);recentSection.setOrientation(LinearLayout.VERTICAL);
        recentSection.setPadding(px(12),px(12),px(12),px(12));
        recentSection.setBackground(shape(themed(0x0DFFFFFF,0x55101A28),0,0));
        int count=hdmiInputs().size()+2;
        LinearLayout row=null;List<View> rowViews=null;
        for(int i=0;i<recent.size();i++) {
            if(i%count==0) {
                row=new LinearLayout(this);rowViews=new ArrayList<>();contentRows.add(rowViews);
                LinearLayout.LayoutParams rowParams=new LinearLayout.LayoutParams(-1,px(108));
                if(i>0)rowParams.topMargin=px(12);
                recentSection.addView(row,rowParams);
            }
            String pkg=recent.get(i);
            LinearLayout tile=new LinearLayout(this);tile.setOrientation(LinearLayout.VERTICAL);tile.setGravity(Gravity.CENTER);
            tile.setId(View.generateViewId());tile.setFocusable(true);tile.setFocusableInTouchMode(true);tile.setClickable(true);
            tile.setContentDescription(name(pkg)+" shortcut");
            ImageView icon=new ImageView(this);icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            icon.setImageDrawable(catalog.artwork(pkg,true).drawable);tile.addView(icon,new LinearLayout.LayoutParams(px(48),px(48)));
            TextView label=text(name(pkg),21,foreground());label.setSingleLine(true);label.setGravity(Gravity.CENTER);
            label.setEllipsize(android.text.TextUtils.TruncateAt.END);tile.addView(label,new LinearLayout.LayoutParams(-1,px(36)));
            tile.setBackground(shape(0,0,0));
            tile.setOnFocusChangeListener((v,has)->tile.setBackground(shape(has?themed(0x38FFFFFF,0xFF2A405B):0,has?accent():0,has?1:0)));
            tile.setOnClickListener(v->launch(pkg));
            LinearLayout.LayoutParams cell=new LinearLayout.LayoutParams(0,-1,1);cell.leftMargin=px(5);cell.rightMargin=px(5);
            row.addView(tile,cell);rowViews.add(tile);
            if(i+1==recent.size())for(int c=(i%count)+1;c<count;c++) {
                View blank=new View(this);LinearLayout.LayoutParams empty=new LinearLayout.LayoutParams(0,-1,1);
                empty.leftMargin=px(5);empty.rightMargin=px(5);row.addView(blank,empty);
            }
        }
        LinearLayout.LayoutParams place=new LinearLayout.LayoutParams(-1,-2);place.topMargin=px(48);
        homePage.addView(recentSection,Math.max(0,homePage.getChildCount()-1),place);
    }

    private void appMenu(String pkg) {
        List<String> labels=new ArrayList<>(); List<Runnable> actions=new ArrayList<>();
        List<String> favorite=favorites(); boolean pinned=favorite.contains(pkg);
        add(labels,actions,pinned?"Move app":"Add to Home",()->{
            if (pinned) { drawer=false; moving=pkg; lastFocus=pkg; render(); }
            else { favorite.add(pkg); store.list("favorites",favorite); render(); toast("Added to Home"); }
        });
        if (pinned) {
            add(labels,actions,"Replace app",()->chooseApp("Replace "+name(pkg),true,chosen->{
                List<String> latest=favorites(); int index=latest.indexOf(pkg);
                if(index>=0) { latest.set(index,chosen); store.list("favorites",latest); lastFocus=chosen; render(); }
            },()->appMenu(pkg)));
            add(labels,actions,"Remove from Home",()->{ favorite.remove(pkg); store.list("favorites",favorite); lastFocus=ALL; render(); toast("Shortcut removed. The app is still in All Apps."); });
        }
        add(labels,actions,"Rename tile",()->rename(pkg));
        add(labels,actions,"Artwork",()->artworkMenu(pkg));
        add(labels,actions,"Section: "+(utility(pkg)?"TV utilities":"Apps"),()->{
            store.setFlag("utility."+pkg,!utility(pkg));render();appMenu(pkg);
        });
        RecommendationRepository.Feed known=recommendations.cached(pkg);
        String status=store.recommendations(pkg)?"Recommendations: On":known!=null&&known.items.isEmpty()
                ?"Recommendations unavailable":"Recommendations: Off";
        if (!store.flag("recommendations_master") && store.recommendations(pkg)) status+=" · paused";
        add(labels,actions,status,()->toggleAppRecommendations(pkg));
        add(labels,actions,"Use for featured banner",()->{
            store.set("featured_source",pkg); store.setFlag("featured",true); render(); bannerMenu();
        });
        menu(name(pkg),labels,actions,null);
    }
    private void moveFavorite(String pkg,int delta) {
        List<String> list=favorites(); int from=list.indexOf(pkg), to=from+delta;
        if (from<0||to<0||to>=list.size()) return;
        list.remove(from); list.add(to,pkg); store.list("favorites",list); lastFocus=pkg; render();
    }
    private void rename(String pkg) {
        EditText edit=new EditText(this); edit.setText(name(pkg)); edit.setSingleLine(true);
        edit.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(40)});
        edit.setSelectAllOnFocus(true);
        AlertDialog dialog=new AlertDialog.Builder(this,android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Tile name").setView(edit)
                .setPositiveButton("Save",(d,w)->{
                    String value=edit.getText().toString().trim();
                    store.set("name."+pkg,value.isEmpty()?byPackage.get(pkg).originalName:value);
                    refreshApps(); render();
                })
                .setNeutralButton("Restore original",(d,w)->{ store.set("name."+pkg,byPackage.get(pkg).originalName); refreshApps(); render(); })
                .setNegativeButton("Cancel",null).create();
        dialog.show(); dialog.getWindow().setLayout(px(740),-2);
    }
    private void artworkMenu(String pkg) {
        menu(name(pkg)+" · Artwork",Arrays.asList("App banner","App icon (Home default)","Choose a photo"),
                Arrays.asList(()->{store.set("art."+pkg,"");store.setFlag("icon."+pkg,false);store.setFlag("home_banner."+pkg,true);render();},
                        ()->{store.set("art."+pkg,"");store.setFlag("icon."+pkg,true);store.setFlag("home_banner."+pkg,false);render();},
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
                        Arrays.asList(()->info("About shared content","Apps must publish accessible recommendations. An account or subscription alone does not provide this launcher with a feed."),
                                ()->{recommendations.clear();toggleAppRecommendations(pkg);},this::recommendationMenu),()->appMenu(pkg));
            } else {
                store.recommendations(pkg,true); render(); appMenu(pkg);
                if (!store.flag("recommendations_master")) toast("Preference saved. Recommendations are paused in Settings.");
            }
        });
    }
    private void settingsMenu() {
        List<String> labels=new ArrayList<>(); List<Runnable> actions=new ArrayList<>();
        add(labels,actions,"Appearance",this::appearanceMenu);
        add(labels,actions,"Recommendations",this::recommendationMenu);
        add(labels,actions,"Featured banner",this::bannerMenu);
        add(labels,actions,"Recently opened: "+onOff(store.flag("recents")),()->{
            store.setFlag("recents",!store.flag("recents"));render();settingsMenu();
        });
        add(labels,actions,"Clear recently opened history",()->{
            store.list("recent_apps",new ArrayList<>());render();toast("Local recent history cleared");settingsMenu();
        });
        add(labels,actions,"Input shortcuts",this::inputMenu);
        add(labels,actions,"Device settings",()->startSafely(new Intent(Settings.ACTION_SETTINGS)));
        add(labels,actions,"Setup and recovery",this::setupMenu);
        add(labels,actions,"About Luma Home",this::aboutMenu);
        menu("Luma settings",labels,actions,null);
    }
    private void appearanceMenu() {
        menu("Appearance",Arrays.asList(
                "Background","Menu theme: "+themeName(),
                "Wallpaper color intensity: "+colorIntensity()+"%",
                "Tile size: "+(columns()==4?"Large":columns()==8?"Compact":"Comfortable"),
                "Clock size","Always show app names: "+onOff(store.flag("labels",false)),
                "High contrast: "+onOff(store.flag("contrast")),
                "Separate TV utilities: "+onOff(store.flag("group_utilities",true))),
                Arrays.asList(this::backgroundMenu,
                        ()->choice("Menu theme",new String[]{"Warm glass","Cool slate","From wallpaper"},"wallpaper".equals(store.value("theme","warm"))?2:"cool".equals(store.value("theme","warm"))?1:0,
                                index->{store.set("theme",new String[]{"warm","cool","wallpaper"}[index]);render();appearanceMenu();},this::appearanceMenu),
                        this::colorIntensityDialog,
                        ()->choice("Tile size",new String[]{"Compact · All Apps: 8 columns","Comfortable · All Apps: 6 columns","Large · All Apps: 4 columns"},
                                columns()==8?0:columns()==6?1:2,index->{store.setNumber("columns",new int[]{8,6,4}[index]);render();appearanceMenu();},this::appearanceMenu),
                        ()->choice("Clock size",new String[]{"Small","Medium","Large"},store.number("clock_size",176)==115?0:store.number("clock_size",176)==138?1:2,
                                index->{store.setNumber("clock_size",new int[]{115,138,176}[index]);render();appearanceMenu();},this::appearanceMenu),
                        ()->{store.setFlag("labels",!store.flag("labels",false));render();appearanceMenu();},
                        ()->{store.setFlag("contrast",!store.flag("contrast"));render();appearanceMenu();},
                        ()->{store.setFlag("group_utilities",!store.flag("group_utilities",true));render();appearanceMenu();}),this::settingsMenu);
    }
    private void colorIntensityDialog(){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(px(36),px(24),px(36),px(24));
        TextView value=text("",25,foreground());box.addView(value);
        LinearLayout sample=new LinearLayout(this);sample.setOrientation(LinearLayout.VERTICAL);sample.setPadding(px(28),px(24),px(28),px(24));
        TextView title=text("Wallpaper colors",28,Color.WHITE);sample.addView(title);
        TextView description=text(wallpaperSeed==0?"No usable wallpaper color. Warm glass is the fallback.":"A soft tint for menus, buttons and focus.",21,muted());sample.addView(description);
        TextView selected=text("Selected item",24,Color.WHITE);selected.setPadding(px(16),px(14),px(16),px(14));LinearLayout.LayoutParams itemParams=new LinearLayout.LayoutParams(-1,px(64));itemParams.topMargin=px(22);sample.addView(selected,itemParams);
        LinearLayout.LayoutParams sampleParams=new LinearLayout.LayoutParams(-1,px(240));sampleParams.topMargin=px(20);box.addView(sample,sampleParams);
        SeekBar slider=new SeekBar(this);slider.setMax(100);slider.setProgress(colorIntensity());slider.setKeyProgressIncrement(5);slider.setFocusableInTouchMode(true);box.addView(slider,new LinearLayout.LayoutParams(px(620),px(80)));
        Runnable update=()->{
            int strength=slider.getProgress(),seed=wallpaperSeed==0?0xFF9A6A52:wallpaperSeed;
            value.setText("Color intensity: "+strength+"%");
            int accent=store.flag("contrast")?0xFFFFFF00:ThemePalette.accent(seed,strength);
            sample.setBackground(shape(ThemePalette.tint(seed,0xF7161113,strength),0,0));
            description.setTextColor(ThemePalette.muted(seed,strength));selected.setBackground(shape(ThemePalette.tint(seed,0x38FFFFFF,strength),accent,2));
            slider.setProgressTintList(android.content.res.ColorStateList.valueOf(accent));slider.setThumbTintList(android.content.res.ColorStateList.valueOf(accent));
        };
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar bar,int progress,boolean user){update.run();}public void onStartTrackingTouch(SeekBar bar){}public void onStopTrackingTouch(SeekBar bar){}});update.run();
        AlertDialog dialog=new AlertDialog.Builder(this,android.R.style.Theme_Material_Dialog_Alert).setTitle("Wallpaper color intensity").setView(box)
                .setPositiveButton("Save",(d,which)->{store.setNumber("color_intensity",slider.getProgress());render();appearanceMenu();})
                .setNegativeButton("Cancel",(d,which)->appearanceMenu()).create();
        dialog.setOnCancelListener(d->appearanceMenu());dialog.show();dialog.getWindow().setLayout(px(760),-2);dialog.getWindow().setBackgroundDrawable(shape(themed(0xFF161113,0xFF111C2B),0,0));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(accent());dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(accent());slider.post(slider::requestFocus);
    }
    private void backgroundMenu() {
        menu("Background",Arrays.asList("Gradient","Solid color","Choose a photo","Darken background: "+store.number("dimming",0)+"%","Restore default background"),
                Arrays.asList(
                        ()->choice("Gradient",new String[]{"Ember","Midnight","Ocean","Forest"},
                                Arrays.asList("ember","midnight","ocean","forest").indexOf(store.value("background","ember")),
                                index->{store.set("background",new String[]{"ember","midnight","ocean","forest"}[index]);render();backgroundMenu();},this::backgroundMenu),
                        ()->choice("Solid color",new String[]{"Black","Slate"},
                                "solid_black".equals(store.value("background",""))?0:1,
                                index->{store.set("background",index==0?"solid_black":"solid_slate");render();backgroundMenu();},this::backgroundMenu),
                        ()->pickImage("background_image"),this::dimmingDialog,
                        ()->{store.set("background","ember");store.setNumber("dimming",0);render();backgroundMenu();}),this::appearanceMenu);
    }
    private void dimmingDialog() {
        LinearLayout box=new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(px(36),px(24),px(36),px(24));
        TextView value=text(store.number("dimming",0)+"% · Left / Right to adjust",26,foreground());
        box.addView(value);
        SeekBar slider=new SeekBar(this); slider.setMax(80);slider.setProgress(store.number("dimming",0));
        slider.setKeyProgressIncrement(5);box.addView(slider,new LinearLayout.LayoutParams(px(620),px(72)));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar bar,int progress,boolean user){value.setText(progress+"% · Left / Right to adjust");}
            public void onStartTrackingTouch(SeekBar bar){}
            public void onStopTrackingTouch(SeekBar bar){}
        });
        AlertDialog d=new AlertDialog.Builder(this,android.R.style.Theme_Material_Dialog_Alert).setTitle("Darken background")
                .setView(box).setPositiveButton("Save",(dialog,which)->{store.setNumber("dimming",slider.getProgress());render();backgroundMenu();})
                .setNegativeButton("Cancel",(dialog,which)->backgroundMenu()).create();
        d.show();d.getWindow().setLayout(px(760),-2);d.getWindow().setBackgroundDrawable(shape(themed(0xFF161113,0xFF111C2B),0,0));d.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(accent());d.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(accent());slider.setProgressTintList(android.content.res.ColorStateList.valueOf(accent()));slider.setThumbTintList(android.content.res.ColorStateList.valueOf(accent()));slider.requestFocus();
    }
    private void recommendationMenu() {
        String access=Build.VERSION.SDK_INT<23||checkSelfPermission(TV_LISTINGS_PERMISSION)==PackageManager.PERMISSION_GRANTED
                ?"TV listings access: Allowed":"Allow TV listings access";
        menu("Recommendations",Arrays.asList(
                "All recommendations: "+onOff(store.flag("recommendations_master")),
                "Card size: "+recommendationSizeName(recommendationSize()),
                "Choose app controls",access,"Refresh shared content","How recommendations work"),
                Arrays.asList(
                        ()->{store.setFlag("recommendations_master",!store.flag("recommendations_master"));render();recommendationMenu();},
                        this::recommendationSizeDialog,
                        ()->chooseApp("App recommendations",false,this::toggleAppRecommendations,this::recommendationMenu),
                        this::requestListings,
                        ()->{recommendations.clear();updateHeader();updateRecommendationRows();toast("Shared content will be checked again");recommendationMenu();},
                        ()->info("Recommendations are optional","Each enabled app keeps its own recommendation row on Home while shared content is available. Moving focus to another app does not hide that row.\n\nThe master switch pauses all rows and the featured banner while remembering your choices. Enable individual apps from their hold-OK menu. Some apps or TVs do not share this data. No subscription details are collected.")),this::settingsMenu);
    }
    private void recommendationSizeDialog() {
        final int[] selected={recommendationSize()};
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(px(32),px(12),px(32),px(12));
        TextView value=text("",25,foreground());box.addView(value,new LinearLayout.LayoutParams(-1,px(48)));
        FrameLayout preview=new FrameLayout(this);box.addView(preview,new LinearLayout.LayoutParams(-1,px(340)));
        LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);
        FrameLayout picture=new FrameLayout(this);picture.setBackground(shape(Color.BLACK,0,0));picture.setClipToOutline(true);
        TextView placeholder=text("Preview artwork",22,muted());placeholder.setGravity(Gravity.CENTER);
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
                placeholder.setVisibility(View.GONE);images.show(image,item.artwork,px(700),px(234));break;
            }
        }
        TextView title=text(sampleTitle,21,foreground());title.setSingleLine(true);title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setGravity(Gravity.CENTER_VERTICAL);title.setPadding(px(4),0,px(4),0);card.addView(title);
        preview.addView(card,new FrameLayout.LayoutParams(px(256),px(192),Gravity.CENTER));
        SeekBar slider=new SeekBar(this);slider.setFocusableInTouchMode(true);slider.setMax(3);slider.setKeyProgressIncrement(1);slider.setProgress(selected[0]);
        slider.setProgressTintList(android.content.res.ColorStateList.valueOf(accent()));
        slider.setThumbTintList(android.content.res.ColorStateList.valueOf(accent()));
        Runnable update=()->{
            int size=selected[0],height=recommendationWidth(size)*9/16;
            int width=artworkWidth(sampleDimensions[0],sampleDimensions[1],px(height),px(700));
            value.setText(recommendationSizeName(size)+"  -  height "+height);
            slider.setContentDescription("Recommendation size: "+recommendationSizeName(size));
            card.setLayoutParams(new FrameLayout.LayoutParams(width,px(height+recommendationTitleHeight(size)),Gravity.CENTER));
            picture.setLayoutParams(new LinearLayout.LayoutParams(-1,px(height)));
            title.setLayoutParams(new LinearLayout.LayoutParams(-1,px(recommendationTitleHeight(size))));
            title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,px(recommendationTextSize(size)));
        };
        refresh[0]=update;
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar bar,int progress,boolean user){selected[0]=progress;update.run();}
            public void onStartTrackingTouch(SeekBar bar){}
            public void onStopTrackingTouch(SeekBar bar){}
        });
        box.addView(slider,new LinearLayout.LayoutParams(-1,px(64)));
        box.addView(text("Left / Right to preview. Width follows each image. Save applies to all rows.",20,muted()));
        AlertDialog dialog=new AlertDialog.Builder(this,android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Recommendation size").setView(box)
                .setPositiveButton("Save",(d,w)->{store.setNumber("recommendation_size",selected[0]);render();recommendationMenu();})
                .setNegativeButton("Cancel",(d,w)->recommendationMenu()).create();
        dialog.setOnCancelListener(d->recommendationMenu());dialog.show();
        dialog.getWindow().setBackgroundDrawable(shape(themed(0xFF161113,0xFF111C2B),0,0));
        dialog.getWindow().setLayout(Math.min(screenWidth-px(96),px(820)),-2);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(accent());
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(accent());
        update.run();slider.post(slider::requestFocus);
    }
    private void bannerMenu() {
        String source=store.value("featured_source","");
        String status=!store.flag("featured")?"Clock · banner is off":
                source.isEmpty()?"Clock · choose a source app":
                !store.flag("recommendations_master")?"Clock · all recommendations are off":
                !store.recommendations(source)?"Clock · source recommendations are off":
                recommendations.cached(source)==null?"Clock until usable shared content is available":
                recommendations.cached(source).status;
        menu("Featured banner",Arrays.asList(
                "Featured banner: "+onOff(store.flag("featured")),
                "Source: "+(source.isEmpty()?"None · show clock":name(source)),
                "Source recommendations: "+(source.isEmpty()?"Choose an app":onOff(store.recommendations(source))),
                "Expanded height: "+(store.number("hero_height",0)==0?"Automatic":store.number("hero_height",0)+"%"),
                "Automatic title rotation: "+onOff(store.flag("hero_rotate")),
                "Video previews: "+onOff(store.flag("hero_video",true)),
                "Preview sound: "+onOff(store.flag("hero_sound")),
                status,"Reset to clock"),
                Arrays.asList(
                        ()->{store.setFlag("featured",!store.flag("featured"));render();bannerMenu();},
                        ()->chooseApp("Choose banner source",false,pkg->{store.set("featured_source",pkg);render();bannerMenu();},this::bannerMenu),
                        ()->{if(source.isEmpty())chooseApp("Choose banner source",false,pkg->{store.set("featured_source",pkg);render();bannerMenu();},this::bannerMenu);else toggleAppRecommendations(source);},
                        ()->{int height=store.number("hero_height",0);store.setNumber("hero_height",height==0?60:height==60?75:height==75?85:0);render();bannerMenu();},
                        ()->{store.setFlag("hero_rotate",!store.flag("hero_rotate"));render();bannerMenu();},
                        ()->{store.setFlag("hero_video",!store.flag("hero_video",true));render();bannerMenu();},
                        ()->{store.setFlag("hero_sound",!store.flag("hero_sound"));render();bannerMenu();},
                        ()->info("Clock fallback","The clock appears when no source is selected, recommendations are disabled, the app shares no accessible content, or the artwork cannot load. Your source choice is kept when you turn the banner off."),
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
        if(labels.isEmpty()){info("Input shortcuts","This device does not expose built-in HDMI inputs. The app and settings dock remain available.");return;}
        menu("Input shortcuts · visibility only",labels,actions,this::settingsMenu);
    }
    private void setupMenu() {
        Intent home=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo result=getPackageManager().resolveActivity(home,PackageManager.MATCH_DEFAULT_ONLY);
        boolean selected=result!=null&&getPackageName().equals(result.activityInfo.packageName);
        menu("Setup and recovery",Arrays.asList(selected?"Luma Home is the default":"Default home: another launcher","Choose default home","Open another installed home","Recovery guidance"),
                Arrays.asList(
                        ()->info("Default home",selected?"Home currently resolves to Luma Home.":"Luma Home is running as a preview. Your existing default has been retained."),
                        ()->{Intent settings=new Intent(Settings.ACTION_HOME_SETTINGS);if(settings.resolveActivity(getPackageManager())!=null)startSafely(settings);else info("Default home","This TV has no standard home-app chooser. A separately approved computer-assisted step may be needed. Luma Home never disables another app automatically.");},
                        this::otherHome,
                        ()->info("Recovery","Device settings remain available in Luma settings.\n\nYou can open another enabled launcher here. Restoring an app previously disabled through ADB requires the approved computer-side undo commands. Luma Home cannot silently re-enable protected system apps.")),this::settingsMenu);
    }
    private void otherHome() {
        List<String> labels=new ArrayList<>();List<Runnable> actions=new ArrayList<>();
        for(ResolveInfo info:getPackageManager().queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),0)) {
            if(info.activityInfo.packageName.equals(getPackageName())||info.activityInfo.packageName.equals("com.android.tv.settings"))continue;
            Intent intent=new Intent(Intent.ACTION_MAIN).setClassName(info.activityInfo.packageName,info.activityInfo.name);
            add(labels,actions,info.loadLabel(getPackageManager()).toString(),()->startSafely(intent));
        }
        if(labels.isEmpty())info("Other home screens","No other enabled home screen is available. Use the saved computer-side recovery commands if you previously disabled one.");
        else menu("Open another home",labels,actions,this::setupMenu);
    }
    private void aboutMenu() {
        menu("Luma Home",Arrays.asList("Version 0.2.0","Your privacy","Open-source license"),Arrays.asList(
                ()->info("Luma Home","A personal Android TV home screen. Home apps, visual choices, recommendation switches and banner source are stored on this TV."),
                ()->info("Local preferences","Luma Home has no analytics or account sign-in. Optional recent history records only apps launched here and can be cleared. Imported images are copied locally. Shared artwork is fetched only for enabled content; streaming apps retain their own accounts and subscriptions."),
                ()->{try(java.io.InputStream stream=getAssets().open("LICENSE")){java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();byte[] b=new byte[2048];int n;while((n=stream.read(b))!=-1)bytes.write(b,0,n);info("MIT license",bytes.toString("UTF-8"));}catch(Exception e){toast("License could not be opened");}}),this::settingsMenu);
    }
    private void pickImage(String target) {
        Intent pick=new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
        pick.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        List<ResolveInfo> handlers=getPackageManager().queryIntentActivities(pick,0);
        List<String> labels=new ArrayList<>();List<Runnable> actions=new ArrayList<>();
        for(ResolveInfo handler:handlers) {
            // Some TV builds advertise a stub that cannot actually browse files.
            if(handler.activityInfo.packageName.contains("frameworkpackagestubs"))continue;
            add(labels,actions,handler.loadLabel(getPackageManager()).toString(),()->{
                imageTarget=target;
                Intent explicit=new Intent(pick).setClassName(handler.activityInfo.packageName,handler.activityInfo.name);
                try{startActivityForResult(explicit,PICK_IMAGE);}catch(Exception e){toast("The file picker could not be opened");}
            });
        }
        if(actions.isEmpty()){info("Choose a photo","This TV has no working image picker. You can use the built-in backgrounds, or install a TV file picker through your normal approved process. No storage permission is required by Luma Home.");return;}
        if(actions.size()==1)actions.get(0).run();else menu("Choose image picker",labels,actions,null);
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request!=PICK_IMAGE||result!=RESULT_OK||data==null||data.getData()==null)return;
        final String target=imageTarget;
        if(target.isEmpty())return;
        toast("Importing image…");
        images.importImage(data.getData(),target.replaceAll("[^A-Za-z0-9._-]","_"),target.equals("background_image")?1920:640,
                target.equals("background_image")?1080:360,(path,error)->{
                    if(error!=null){info("Image import",error);return;}
                    String previous=store.value(target,"");
                    store.set(target,path);if(target.equals("background_image"))store.set("background","photo");
                    if(!previous.isEmpty()) {
                        try{File old=new File(Uri.parse(previous).getPath());if(old.getCanonicalPath().startsWith(getFilesDir().getCanonicalPath()+File.separator))old.delete();}catch(Exception ignored){}
                    }
                    imageTarget="";render();toast("Image saved on this TV");
                });
    }
    private void requestListings() {
        if(Build.VERSION.SDK_INT>=23&&checkSelfPermission(TV_LISTINGS_PERMISSION)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{TV_LISTINGS_PERMISSION},TV_LISTINGS);
        else info("TV listings","Access is allowed. Apps still need to publish content that this TV makes available to Luma Home.");
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
        if(labels.isEmpty()){info(title,"Every available app is already on Home.");return;}
        menu(title,labels,actions,back);
    }
    private interface AppChosen {void pick(String pkg);}
    private interface Choice {void pick(int index);}
    private void choice(String title,String[] labels,int selected,Choice action,Runnable back) {
        List<String> names=new ArrayList<>();List<Runnable> actions=new ArrayList<>();
        for(int i=0;i<labels.length;i++){final int index=i;add(names,actions,(i==selected?"✓ ":"")+labels[i],()->action.pick(index));}
        menu(title,names,actions,back);
    }
    private static void add(List<String> labels,List<Runnable> actions,String label,Runnable action){labels.add(label);actions.add(action);}
    private void menu(String title,List<String> labels,List<Runnable> actions,Runnable back) {
        Dialog dialog=new Dialog(this,android.R.style.Theme_Material_NoActionBar);
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(px(40),px(48),px(40),px(32));box.setBackground(shape(themed(0xF7161113,0xFF111C2B),themed(0x22FFFFFF,0xFF435675),1));
        TextView heading=text(title,34,foreground());heading.setTypeface(medium);heading.setMaxLines(2);
        box.addView(heading,new LinearLayout.LayoutParams(-1,-2));
        TextView hint=text("Back to return",19,muted());LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(-1,px(42));hp.bottomMargin=px(20);box.addView(hint,hp);
        ListView list=new ListView(this);list.setDivider(null);list.setVerticalScrollBarEnabled(false);list.setSelector(shape(themed(0x38FFFFFF,0xFF294360),wallpaperTheme()?accent():themed(0x00FFFFFF,accent()),wallpaperTheme()||"cool".equals(store.value("theme","warm"))?2:0));
        list.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_list_item_1,labels){
            @Override public View getView(int position,View convert,ViewGroup parent){
                TextView row=(TextView)super.getView(position,convert,parent);row.setTextColor(foreground());
                row.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,px(25));row.setPadding(px(22),px(15),px(18),px(15));row.setMinHeight(px(74));row.setMaxLines(3);return row;
            }
        });
        list.setOnItemClickListener((parent,view,position,id)->{dialog.dismiss();actions.get(position).run();});
        box.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        dialog.setContentView(box);dialog.setOnCancelListener(d->{if(back!=null)back.run();});
        dialog.setOnKeyListener((d,key,event)->{
            if(suppressCenterUp&&(key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_ENTER)) {
                if(event.getAction()==KeyEvent.ACTION_UP)suppressCenterUp=false;
                return true;
            }
            return false;
        });
        Window window=dialog.getWindow();if(window!=null){window.setBackgroundDrawableResource(android.R.color.transparent);window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);window.setDimAmount(.45f);}
        dialog.show();window.setLayout(Math.min(screenWidth-px(96),px(820)),screenHeight-px(96));window.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);
        list.requestFocus();list.setSelection(0);
    }
    private void info(String title,String message) {
        ScrollView scroll=new ScrollView(this);scroll.setPadding(px(28),px(20),px(28),px(20));
        TextView body=text(message,25,foreground());body.setTextIsSelectable(false);scroll.addView(body);
        AlertDialog dialog=new AlertDialog.Builder(this,android.R.style.Theme_Material_Dialog_Alert).setTitle(title).setView(scroll).setPositiveButton("OK",null).create();
        dialog.show();dialog.getWindow().setLayout(Math.min(screenWidth-px(96),px(900)),-2);dialog.getWindow().setBackgroundDrawable(shape(themed(0xFF161113,0xFF111C2B),0,0));dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(accent());
    }
    private String onOff(boolean value){return value?"On":"Off";}
    private void launch(String pkg) {
        Intent intent=catalog.launch(pkg);
        if(intent==null){toast("This app is no longer available");dirty=true;return;}
        try{startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));store.recent(pkg);}
        catch(Exception e){toast("This app could not be opened");}
    }
    private void openTitle(RecommendationRepository.Item item) {
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
        TextView view=new TextView(this);view.setText(value);view.setTextColor(color);
        view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,px(size));return view;
    }
    private Button button(String label,Runnable action) {
        Button button=new Button(this);button.setText(label);button.setAllCaps(false);button.setTextColor(foreground());
        button.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,px(23));button.setPadding(px(12),0,px(12),0);
        button.setBackground(shape(themed(0x18FFFFFF,0xDD1C2C40),themed(0x33FFFFFF,0xFF516987),1));button.setId(View.generateViewId());
        button.setFocusableInTouchMode(true);
        button.setOnFocusChangeListener((v,has)->button.setBackground(shape(has?themed(0x38FFFFFF,0xFF365879):themed(0x18FFFFFF,0xDD1C2C40),has?accent():themed(0x33FFFFFF,0xFF516987),has?2:1)));
        button.setOnClickListener(v->action.run());return button;
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
        ScrollView view=new ScrollView(this);view.setFillViewport(false);view.setClipToPadding(false);
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
