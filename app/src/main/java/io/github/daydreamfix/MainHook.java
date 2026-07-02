package io.github.daydreamfix;

import android.accounts.Account;
import android.app.Activity;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.os.BadParcelableException;
import android.os.IBinder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import android.os.Parcel;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.io.BufferedReader;
import java.io.FileReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "DaydreamEverywhere";
    private static final String PKG_DAYDREAM       = "com.google.android.vr.home";
    private static final String PKG_VRCORE         = "com.google.vr.vrcore";
    private static final String PKG_YOUTUBE_VR     = "com.google.android.apps.youtube.vr";
    private static final String PKG_FIREFOX_REALITY = "org.mozilla.vrbrowser";
    private static final String PKG_PHOTOS_VR       = "com.google.android.apps.photos.daydream";

    // Strong reference to the NativeCallbacks instance captured at onServiceConnected.
    private static volatile Object sNativeCallbacks = null;
    // Set to true when onControllerStateChanged is actually called; watchdog checks this.
    private static volatile boolean sStateDelivered = false;

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
        if (lpparam.packageName.equals(PKG_DAYDREAM)) {
            XposedBridge.log(TAG + ": Injecting into " + PKG_DAYDREAM);
            hookParcelReadException();
            hookVrLauncherOrientation();
            hookBlastBufferQueue(lpparam.classLoader);
            hookNativeCallbacks(lpparam.classLoader);     // Hook 6 + D
            hookSurfaceViewType(PKG_DAYDREAM);
            hookControllerServiceBridge(lpparam.classLoader); // Hook CSB (PKG_DAYDREAM classloader)
            hookStoreRequest(lpparam.classLoader);        // Hook PS: private store
            hookAppTile(lpparam.classLoader);             // Hook Tile: force placeholder icons
            hookAegBackground(lpparam.classLoader);       // Hook AegBg: fix non-fife background URL
            hookDiscoveryClick(lpparam.classLoader);      // Hook DC: launch apps from discovery
            hookDiscoveryNetworkCheck(lpparam.classLoader); // Hook DNet: agv.g() always true
            hookVrSessionQuery();                         // Hook VrQ: always return is_in_vr_session=true
            hookLibraryFakeEntries(lpparam.classLoader);  // Hook Lib: alu.h=true for fake entries
        }

        if (lpparam.packageName.equals(PKG_YOUTUBE_VR)) {
            XposedBridge.log(TAG + ": Injecting into " + PKG_YOUTUBE_VR);
            hookParcelReadException();
            hookBlastBufferQueue(lpparam.classLoader);
            hookSurfaceViewType(PKG_YOUTUBE_VR);
            hookYouTubeVrEdgeToEdge();
        }

        if (lpparam.packageName.equals(PKG_FIREFOX_REALITY)) {
            XposedBridge.log(TAG + ": Injecting into " + PKG_FIREFOX_REALITY);
            hookParcelReadException();
            // hookBlastBufferQueue intentionally omitted: Firefox Reality uses GVR's
            // single-buffered rendering path; forcing singleBufferMode=false crashes GVR init.
            // hookSurfaceViewType intentionally omitted: GeckoView SurfaceViews + PUSH_BUFFERS crashes.
            hookGvrLayout(lpparam.classLoader);
            hookFirefoxContextWrapper();
            hookFirefoxVrService();
        }

        if (lpparam.packageName.equals(PKG_PHOTOS_VR)) {
            XposedBridge.log(TAG + ": Injecting into " + PKG_PHOTOS_VR);
            hookParcelReadException();
            hookBlastBufferQueue(lpparam.classLoader);
            // hookSurfaceViewType intentionally omitted: same reason as Firefox Reality.
            hookDaydreamApi(lpparam.classLoader);         // force Daydream mode (not Cardboard fallback)
            hookVrSessionQuery();                         // Photos VR queries is_in_vr_session in its own process
        }

        if (lpparam.packageName.equals(PKG_VRCORE)) {
            XposedBridge.log(TAG + ": Injecting into " + PKG_VRCORE);
            hookParcelReadException();
            hookThermalInfoCache(lpparam.classLoader);
            hookRecenterChoreographer(lpparam.classLoader);   // Hook 5a/5b/5c
            hookControllerServiceVrMode(lpparam.classLoader); // Hook 7a/7b
            hookReadThreadTrace(lpparam.classLoader);         // Hook D2
            hookInjectViaStateMachine(lpparam.classLoader);   // Hook 9
            hookNativeCallbacks(lpparam.classLoader);         // Hook 6+D on correct classloader
            hookDqoReconcile(lpparam.classLoader);            // Hook dqo: force listeners active
            // Hook E (dud) and Hook F (dff) removed: class names differ in installed APK
            // State delivery is now handled by direct injection in Hook CSB
            hookControllerServiceBridge(lpparam.classLoader); // Hook CSB (vrcore remote classloader)
            hookHeadsetCheck(lpparam.classLoader);            // Hook H: accept any headset
        }
    }

    // ── Hook 1: Parcel BadParcelableException / NPE ──────────────────────────
    private void hookParcelReadException() {
        try {
            XposedHelpers.findAndHookMethod(Parcel.class, "readException",
                    int.class, String.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                            if (!p.hasThrowable()) return;
                            Throwable t = p.getThrowable();
                            String msg = t.getMessage();
                            if (t instanceof BadParcelableException && msg != null && msg.contains("not fully consumed")) {
                                Parcel parcel = (Parcel) p.thisObject;
                                parcel.setDataPosition(parcel.dataSize());
                                p.setThrowable(null);
                                XposedBridge.log(TAG + ": Hook 1: Swallowed BadParcelableException");
                            } else if (t instanceof NullPointerException
                                    && t.getStackTrace().length > 0
                                    && t.getStackTrace()[0].getClassName().contains("Parcel")) {
                                p.setThrowable(null);
                                XposedBridge.log(TAG + ": Hook 1: Swallowed NPE from createExceptionOrNull");
                            }
                        }
                    });
            XposedBridge.log(TAG + ": Hook 1 installed");
        } catch (Throwable e) { XposedBridge.log(TAG + ": Hook 1 FAILED: " + e); }
    }

    // ── Hook 2: ThermalInfoCache SecurityException ────────────────────────────
    private void hookThermalInfoCache(ClassLoader cl) {
        try {
            Class<?> c = XposedHelpers.findClass("com.google.vr.vrcore.controller.ThermalInfoCache", cl);
            XposedBridge.hookAllConstructors(c, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                    if (p.hasThrowable() && p.getThrowable() instanceof SecurityException) {
                        p.setThrowable(null);
                        XposedBridge.log(TAG + ": Hook 2: Swallowed ThermalInfoCache SecurityException");
                    }
                }
            });
            XposedBridge.log(TAG + ": Hook 2 installed");
        } catch (Throwable e) { XposedBridge.log(TAG + ": Hook 2 skipped: " + e); }
    }

    // ── Hook 3: BLASTBufferQueue singleBufferMode=false ──────────────────────
    private void hookBlastBufferQueue(ClassLoader cl) {
        try {
            Class<?> c = XposedHelpers.findClass("android.graphics.BLASTBufferQueue", cl);
            XposedHelpers.findAndHookMethod(c, "nativeCreate", String.class, boolean.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                            if ((boolean) p.args[1]) {
                                p.args[1] = false;
                                XposedBridge.log(TAG + ": Hook 3: forced singleBufferMode=false");
                            }
                        }
                    });
            XposedBridge.log(TAG + ": Hook 3 installed");
        } catch (Throwable e) { XposedBridge.log(TAG + ": Hook 3 FAILED: " + e); }
    }

    // ── Hook 3b: SurfaceView PUSH_BUFFERS ────────────────────────────────────
    private void hookSurfaceViewType(String pkgName) {
        try {
            XposedHelpers.findAndHookMethod(SurfaceView.class, "onAttachedToWindow",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                            SurfaceView sv = (SurfaceView) p.thisObject;
                            Context ctx = sv.getContext();
                            if (ctx == null || !pkgName.equals(ctx.getPackageName())) return;
                            sv.getHolder().setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
                            XposedBridge.log(TAG + ": Hook 3b: Set PUSH_BUFFERS on VR SurfaceView");
                        }
                    });
            XposedBridge.log(TAG + ": Hook 3b installed");
        } catch (Throwable e) { XposedBridge.log(TAG + ": Hook 3b FAILED: " + e); }
    }

    // ── Hook 4 + 8: Orientation lock + is_in_vr_session ──────────────────────
    private void hookVrLauncherOrientation() {
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                    Activity a = (Activity) p.thisObject;
                    // beforeHook fires before fragment dispatching — ensures is_in_vr_session=true
                    // is already written before agv.onResume() calls amu.a().
                    // hookVrSessionQuery intercepts the read side too (belt-and-suspenders).
                    try {
                        ContentValues cv = new ContentValues();
                        cv.put("name", "is_in_vr_session"); cv.put("value", "true");
                        Uri res = a.getContentResolver().insert(
                                Uri.parse("content://com.google.vr.vrcore.settings/boolean_settings"), cv);
                        XposedBridge.log(TAG + ": Hook 8: is_in_vr_session=true res=" + res
                                + " (" + a.getClass().getSimpleName() + ")");
                    } catch (Throwable t) { XposedBridge.log(TAG + ": Hook 8 FAILED: " + t); }
                    // Landscape lock only for VR launcher
                    if (!a.getClass().getName().equals("com.google.vr.app.Launcher.Launcher")) return;
                    a.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
                    XposedBridge.log(TAG + ": Hook 4: Locked LANDSCAPE");
                }
            });
            XposedHelpers.findAndHookMethod(Activity.class, "onStop", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                    Activity a = (Activity) p.thisObject;
                    if (!a.getClass().getName().equals("com.google.vr.app.Launcher.Launcher")) return;
                    a.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
                    XposedBridge.log(TAG + ": Hook 4: Released orientation");
                    try {
                        ContentValues cv = new ContentValues();
                        cv.put("name", "is_in_vr_session"); cv.put("value", "false");
                        a.getContentResolver().insert(
                                Uri.parse("content://com.google.vr.vrcore.settings/boolean_settings"), cv);
                        XposedBridge.log(TAG + ": Hook 8: is_in_vr_session=false");
                    } catch (Throwable t) { XposedBridge.log(TAG + ": Hook 8 set-false FAILED: " + t); }
                }
            });
            XposedBridge.log(TAG + ": Hook 4+8 installed");
        } catch (Throwable e) { XposedBridge.log(TAG + ": Hook 4+8 FAILED: " + e); }
    }

    // ── Hook 5a/5b/5c: RecenterChoreographer ─────────────────────────────────
    private void hookRecenterChoreographer(ClassLoader cl) {
        // 5a: null session ID in ckk.c(String)
        try {
            Class<?> c = XposedHelpers.findClass("ckk", cl);
            XposedHelpers.findAndHookMethod(c, "c", String.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                    if (p.args[0] == null) { p.args[0] = ""; XposedBridge.log(TAG + ": Hook 5a: null→empty"); }
                }
            });
            XposedBridge.log(TAG + ": Hook 5a installed");
        } catch (Throwable e) { XposedBridge.log(TAG + ": Hook 5a FAILED: " + e); }

        // 5b: NPE guard in dri.run()
        try {
            Class<?> c = XposedHelpers.findClass("dri", cl);
            XposedHelpers.findAndHookMethod(c, "run", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                    if (p.hasThrowable() && p.getThrowable() instanceof NullPointerException) {
                        XposedBridge.log(TAG + ": Hook 5b: suppressed NPE in dri.run()");
                        p.setThrowable(null);
                    }
                }
            });
            XposedBridge.log(TAG + ": Hook 5b installed");
        } catch (Throwable e) { XposedBridge.log(TAG + ": Hook 5b FAILED: " + e); }

        // 5c: inject fake controller orientation when dri is at j=5
        try {
            Class<?> driClass = XposedHelpers.findClass("dri", cl);
            Class<?> dtkClass = XposedHelpers.findClass("dtk", cl);
            XposedBridge.hookAllMethods(driClass, "run", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                    Object dri = p.thisObject;
                    int j = XposedHelpers.getIntField(dri, "j"); // FIX: getIntField, not getObjectField
                    if (j != 5) return;
                    XposedBridge.log(TAG + ": Hook 5c: j=5, injecting fake dtk");
                    final Object finalDri = dri;
                    new Thread(() -> {
                        try {
                            Thread.sleep(30);
                            Object dtk = dtkClass.newInstance();
                            XposedHelpers.setFloatField(dtk, "a", 0.0f);
                            XposedHelpers.setFloatField(dtk, "b", 0.0f);
                            XposedHelpers.setFloatField(dtk, "c", 0.0f);
                            XposedHelpers.setFloatField(dtk, "f", 1.0f);
                            XposedHelpers.setLongField(dtk, "d", System.nanoTime());
                            Method m = finalDri.getClass().getDeclaredMethod("a", dtkClass);
                            m.setAccessible(true);
                            m.invoke(finalDri, dtk);
                            XposedBridge.log(TAG + ": Hook 5c: dtk injected → j→6");
                        } catch (Throwable t) { XposedBridge.log(TAG + ": Hook 5c error: " + t); }
                    }).start();
                }
            });
            XposedBridge.log(TAG + ": Hook 5c installed");
        } catch (Throwable e) { XposedBridge.log(TAG + ": Hook 5c FAILED: " + e); }
    }

    // ── Hook 6 + D: NativeCallbacks suppression + service-connected logging ───
    // NB: NativeCallbacks may be in the vrcore remote classloader inside vr.home;
    // Hook 6 suppresses failures. Hook D logs onServiceConnected (fires if classloader matches).
    private void hookNativeCallbacks(ClassLoader cl) {
        try {
            Class<?> nc = XposedHelpers.findClass(
                    "com.google.vr.vrcore.controller.api.NativeCallbacks", cl);

            XC_MethodHook suppress = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                    XposedBridge.log(TAG + ": Hook 6: suppressed " + p.method.getName());
                    p.setResult(null);
                }
            };
            XposedBridge.hookAllMethods(nc, "onServiceFailed",        suppress);
            XposedBridge.hookAllMethods(nc, "onServiceUnavailable",   suppress);
            XposedBridge.hookAllMethods(nc, "onServiceInitFailed",    suppress);
            // Suppress disconnect too: if vrcore Binder drops, we don't want GVR reset to DISCONNECTED
            XposedBridge.hookAllMethods(nc, "onServiceDisconnected",  suppress);

            XposedBridge.hookAllMethods(nc, "onServiceConnected", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                    // Capture the nc instance as a strong reference so dud's WeakReference
                    // doesn't lose the dtx→nc chain to GC before state changes arrive.
                    sNativeCallbacks = p.thisObject;
                    XposedBridge.log(TAG + ": Hook D: onServiceConnected(i=" + p.args[0] + ") nc captured cl=" + p.thisObject.getClass().getClassLoader());
                }
            });

            XposedBridge.hookAllMethods(nc, "onControllerStateChanged", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                    sStateDelivered = true;
                    XposedBridge.log(TAG + ": Hook D: onControllerStateChanged(idx=" + p.args[0] + " state=" + p.args[1] + ")");
                }
                @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                    if (p.hasThrowable()) {
                        XposedBridge.log(TAG + ": Hook D: handleStateChanged THREW: " + p.getThrowable());
                    } else {
                        XposedBridge.log(TAG + ": Hook D: handleStateChanged returned OK");
                    }
                    // Probe inner_ptr+0x34 after CONNECTED injection to see if state was stored
                    if ((int) p.args[1] == 3) {
                        Object ncRef = sNativeCallbacks;
                        if (ncRef != null) {
                            try { MainHook.this.probeVtable(ncRef); }
                            catch (Throwable t) { XposedBridge.log(TAG + ": Hook D: post-state probe error: " + t); }
                        }
                    }
                }
            });

            // Log event packet arrivals (first few + every 500th) to confirm delivery
            XC_MethodHook logEvt = new XC_MethodHook() {
                private volatile int evtCount = 0;
                @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                    int n = ++evtCount;
                    if (n <= 5 || n % 500 == 0)
                        XposedBridge.log(TAG + ": Hook D: " + p.method.getName() + " #" + n);
                }
            };
            XposedBridge.hookAllMethods(nc, "onControllerEventPacket",  logEvt);
            XposedBridge.hookAllMethods(nc, "onControllerEventPacket2", logEvt);

            XposedBridge.log(TAG + ": Hook 6+D (NativeCallbacks) installed cl=" + cl);
        } catch (Throwable e) { XposedBridge.log(TAG + ": Hook 6+D FAILED: " + e); }
    }

    // ── Hook 7a/7b: ControllerService VR mode force ──────────────────────────
    private void hookControllerServiceVrMode(ClassLoader cl) {
        try {
            Class<?> cs = XposedHelpers.findClass("com.google.vr.vrcore.controller.ControllerService", cl);
            XC_MethodHook setVr = new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                    XposedHelpers.setBooleanField(p.thisObject, "k", true);
                    XposedBridge.log(TAG + ": Hook 7a: k=true in " + p.method.getName());
                }
            };
            XposedBridge.hookAllMethods(cs, "onCreate", setVr);
            XposedBridge.hookAllMethods(cs, "onBind",   setVr);
            XposedBridge.log(TAG + ": Hook 7a installed");
        } catch (Throwable e) { XposedBridge.log(TAG + ": Hook 7a FAILED: " + e); }

        try {
            Class<?> dpj = XposedHelpers.findClass("dpj", cl);
            XposedBridge.hookAllMethods(dpj, "d", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                    XposedBridge.log(TAG + ": Hook 7b: suppressed dpj.d()");
                    p.setResult(null);
                }
            });
            XposedBridge.log(TAG + ": Hook 7b installed");
        } catch (Throwable e) { XposedBridge.log(TAG + ": Hook 7b FAILED: " + e); }
    }

    // ── Hook D2: dnw.run() trace ──────────────────────────────────────────────
    private void hookReadThreadTrace(ClassLoader cl) {
        try {
            Class<?> dnw = XposedHelpers.findClass("dnw", cl);
            XposedBridge.hookAllMethods(dnw, "run", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    XposedBridge.log(TAG + ": Hook D2: dnw.run() started");
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    XposedBridge.log(TAG + ": Hook D2: dnw.run() EXITED");
                }
            });
            XposedBridge.log(TAG + ": Hook D2 installed");
        } catch (Throwable e) { XposedBridge.log(TAG + ": Hook D2 FAILED: " + e); }
    }

    // ── Hook 9: DISABLED — was causing vrcore crash via race condition ────────
    // dnw.a() reuses dnt.e as the parse buffer: reset → populate → dispatch (sync).
    // The old injection thread called dnt.e.a() (reset, f=0) concurrently with
    // ControllerService.a() iterating dsuVar.f → IndexOutOfBoundsException → crash.
    // With hookDqoReconcile forcing listeners active, real companion-app events
    // (orientation/gyro/touch/buttons) flow naturally through dqo → dud → NativeCallbacks.
    private void hookInjectViaStateMachine(ClassLoader cl) {
        XposedBridge.log(TAG + ": Hook 9 disabled (race-condition fix)");
    }

    // ── Hook F: dff.b() – return vr.home ComponentName when null ────────────────
    // dqo.a() activates a listener only when z=true. z=true requires dff.d.getPackageName()
    // == listener's package ("com.google.android.vr.home"). If setVrModeEnabled was never
    // called or failed, dff.d stays null → z=false → listeners never activated → CONNECTED
    // state never delivered to NativeCallbacks. Returning a non-null vr.home ComponentName
    // ensures z=true and the listener enters dqoVar.b (active set).
    private void hookDffComponentName(ClassLoader cl) {
        try {
            Class<?> dffClass = XposedHelpers.findClass("defpackage.dff", cl);
            XposedHelpers.findAndHookMethod(dffClass, "b", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                    Object result = p.getResult();
                    if (result == null) {
                        ComponentName cn = new ComponentName("com.google.android.vr.home",
                                "com.google.android.vr.home.launcher.Launcher");
                        p.setResult(cn);
                        XposedBridge.log(TAG + ": Hook F: dff.d was null → injected vr.home CN");
                    }
                }
            });
            XposedBridge.log(TAG + ": Hook F (dff.b) installed");
        } catch (Throwable e) { XposedBridge.log(TAG + ": Hook F FAILED: " + e); }
    }

    // ── Hook E: dud – fix WeakReference GC silently dropping state changes ─────
    // dud stores dtxVar as WeakReference(dtxVar). By the time vrcore sends CONNECTED
    // (3s after bind), GC may have collected dtxVar, causing a(int,int) to return
    // silently. Fix: when dtxVar is gone, call the statically-held sNativeCallbacks directly.
    private void hookDud(ClassLoader cl) {
        try {
            Class<?> dudClass = XposedHelpers.findClass("defpackage.dud", cl);
            XposedHelpers.findAndHookMethod(dudClass, "a", int.class, int.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                    try {
                        Field aField = p.thisObject.getClass().getDeclaredField("a");
                        aField.setAccessible(true);
                        java.lang.ref.WeakReference<?> ref = (java.lang.ref.WeakReference<?>) aField.get(p.thisObject);
                        Object dtxVar = (ref != null) ? ref.get() : null;
                        int state = (int) p.args[1];
                        int idx   = (int) p.args[0];
                        if (dtxVar != null) {
                            XposedBridge.log(TAG + ": Hook E: dud.a(idx=" + idx + " state=" + state + ") dtx=alive");
                        } else {
                            XposedBridge.log(TAG + ": Hook E: dud.a(idx=" + idx + " state=" + state + ") dtx=GC'd, using sNC");
                            Object nc = sNativeCallbacks;
                            if (nc != null) {
                                Method scm = nc.getClass().getMethod("onControllerStateChanged", int.class, int.class);
                                scm.invoke(nc, idx, state);
                                XposedBridge.log(TAG + ": Hook E: called nc.onControllerStateChanged(" + idx + "," + state + ") OK");
                            } else {
                                XposedBridge.log(TAG + ": Hook E: sNC also null, state change lost");
                            }
                            p.setResult(null); // skip original (dtxVar is null anyway)
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": Hook E error: " + t);
                    }
                }
            });
            XposedBridge.log(TAG + ": Hook E (dud) installed");
        } catch (Throwable e) {
            XposedBridge.log(TAG + ": Hook E FAILED: " + e);
        }
    }

    // ── Hook dqo: force all registered listeners into the active set ─────────
    // dqo.a() (reconciliation) only adds listeners to this.b when dff.d is non-null
    // and its package matches the listener's. On Android 16 without VrManager,
    // dff.d stays null → z=false → no listener ever enters this.b → all state
    // changes and event packets are silently dropped before reaching dud Binder.
    // Fix: after each reconciliation, scan this.a (all registered) and force any
    // missing entries into this.b (active set).
    private void hookDqoReconcile(ClassLoader cl) {
        try {
            Class<?> dqoClass = XposedHelpers.findClass("dqo", cl);
            // Locate void a() — the 250-instruction reconciliation
            java.lang.reflect.Method reconcile = null;
            for (java.lang.reflect.Method m : dqoClass.getDeclaredMethods()) {
                if (m.getName().equals("a") && m.getParameterCount() == 0
                        && m.getReturnType() == void.class) {
                    reconcile = m;
                    break;
                }
            }
            if (reconcile == null) {
                XposedBridge.log(TAG + ": Hook dqo: void a() not found in dqo");
                return;
            }
            XposedBridge.hookMethod(reconcile, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                    try {
                        Object dqo = p.thisObject;
                        // this.a = Collections.synchronizedMap(HashMap<String, SparseArray<dqq>>)
                        Field aField = dqo.getClass().getDeclaredField("a");
                        aField.setAccessible(true);
                        java.util.Map<?, ?> allMap = (java.util.Map<?, ?>) aField.get(dqo);
                        // this.b = HashSet<dqq> (active listeners)
                        Field bField = dqo.getClass().getDeclaredField("b");
                        bField.setAccessible(true);
                        @SuppressWarnings("unchecked")
                        java.util.Set<Object> activeSet = (java.util.Set<Object>) bField.get(dqo);

                        int added = 0;
                        synchronized (allMap) {
                            for (Object val : allMap.values()) {
                                android.util.SparseArray<?> sa = (android.util.SparseArray<?>) val;
                                for (int i = 0; i < sa.size(); i++) {
                                    Object dqq = sa.valueAt(i);
                                    if (dqq != null && activeSet.add(dqq)) added++;
                                }
                            }
                        }
                        if (added > 0)
                            XposedBridge.log(TAG + ": Hook dqo: forced " + added + " listener(s) → active");
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": Hook dqo error: " + t);
                    }
                }
            });
            XposedBridge.log(TAG + ": Hook dqo installed");
        } catch (Throwable e) { XposedBridge.log(TAG + ": Hook dqo FAILED: " + e); }
    }

    // ── Hook CSB: ControllerServiceBridge – force setup when initialize fails ─
    // Called for BOTH PKG_DAYDREAM and PKG_VRCORE classloaders to catch all cases.
    // BUG FIX: previous version called setupAndBind but never called nc.onServiceConnected(1),
    // meaning handleServiceConnected JNI was never called and GVR context never told about the service.
    private void hookControllerServiceBridge(ClassLoader cl) {
        try {
            Class<?> csb = XposedHelpers.findClass(
                    "com.google.vr.vrcore.controller.api.ControllerServiceBridge", cl);

            XposedHelpers.findAndHookMethod(csb, "onServiceConnected",
                    ComponentName.class, IBinder.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                            try {
                                Object csbObj = p.thisObject;
                                ClassLoader cl2 = csbObj.getClass().getClassLoader();

                                // Field "j" = IControllerService proxy (null if initialize failed + b() ran)
                                Field jField = csbObj.getClass().getDeclaredField("j");
                                jField.setAccessible(true);
                                Object service = jField.get(csbObj);

                                XposedBridge.log(TAG + ": Hook CSB: onServiceConnected done, service="
                                        + (service != null ? "ok" : "null"));

                                // Always probe vtable: nativePtr is set at NativeCallbacks creation
                                // and persists regardless of whether initialize succeeded.
                                Object nc = getNativeCallbacks(csbObj);
                                if (nc != null) {
                                    probeVtable(nc);
                                } else {
                                    XposedBridge.log(TAG + ": Hook CSB: nc is null, skipping vtable probe");
                                }

                                if (service == null) {
                                    // initialize(25) failed: b() was called, j=null, d=false.
                                    // Recover: reconstruct j from the binder and call c()
                                    // (which calls nc.onServiceConnected(1) + setupAndBind).
                                    IBinder binder = (IBinder) p.args[1];
                                    // dul.a(binder) is the generated asInterface equivalent
                                    Class<?> dulClass = XposedHelpers.findClass("defpackage.dul", cl2);
                                    Method aMethod = dulClass.getDeclaredMethod("a", IBinder.class);
                                    aMethod.setAccessible(true);
                                    Object svc = aMethod.invoke(null, binder);

                                    jField.set(csbObj, svc);
                                    Field dField = csbObj.getClass().getDeclaredField("d");
                                    dField.setAccessible(true);
                                    dField.setBoolean(csbObj, true);

                                    // c() does: nc.onServiceConnected(1) + setupAndBind
                                    Method cMethod = csbObj.getClass().getDeclaredMethod("c");
                                    cMethod.setAccessible(true);
                                    cMethod.invoke(csbObj);
                                            XposedBridge.log(TAG + ": Hook CSB: forced c() OK");
                                }

                                // Inject onControllerStateChanged(0,3) directly.
                                // The natural path (dqo→dud Binder) may silently fail if
                                // dff.d=null (z=false → listener never activated in dqoVar.b).
                                // We bypass dqo entirely: after handleServiceConnected (JNI) ran
                                // inside c(), GVR is initialized and will accept the state change.
                                // Use a short delay so the NC hook's beforeHook has time to
                                // capture sNativeCallbacks if it fires concurrently.
                                sStateDelivered = false;
                                new Thread(() -> {
                                    try {
                                        Thread.sleep(300);
                                        Object nc2 = sNativeCallbacks;
                                        if (nc2 == null) {
                                            XposedBridge.log(TAG + ": Hook inject: sNC null after 300ms");
                                            return;
                                        }
                                        if (sStateDelivered) {
                                            XposedBridge.log(TAG + ": Hook inject: state already delivered naturally");
                                            return;
                                        }
                                        // Inject SCANNING→CONNECTING→CONNECTED to walk GVR state machine
                                        Method scm = nc2.getClass().getDeclaredMethod(
                                                "onControllerStateChanged", int.class, int.class);
                                        scm.setAccessible(true);
                                        scm.invoke(nc2, 0, 1); // SCANNING
                                        Thread.sleep(50);
                                        scm.invoke(nc2, 0, 2); // CONNECTING
                                        Thread.sleep(50);
                                        scm.invoke(nc2, 0, 3); // CONNECTED
                                        XposedBridge.log(TAG + ": Hook inject: injected SCANNING→CONNECTING→CONNECTED");
                                    } catch (Throwable t2) {
                                        XposedBridge.log(TAG + ": Hook inject error: " + t2);
                                    }
                                }, "DaydreamStateInject").start();
                                XposedBridge.log(TAG + ": Hook CSB: state injection thread started");
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + ": Hook CSB afterHook error: " + t);
                            }
                        }
                    });
            XposedBridge.log(TAG + ": Hook CSB installed (cl=" + cl + ")");
        } catch (Throwable e) {
            XposedBridge.log(TAG + ": Hook CSB FAILED (cl=" + cl + "): " + e);
        }
    }

    // Returns the NativeCallbacks object from inside a ControllerServiceBridge instance.
    // ControllerServiceBridge.c is a dtx; dtx.a is the Callbacks (NativeCallbacks).
    private Object getNativeCallbacks(Object csb) {
        try {
            // Field "c" in ControllerServiceBridge is the dtx holding the callbacks.
            Field cf = csb.getClass().getDeclaredField("c");
            cf.setAccessible(true);
            Object dtx = cf.get(csb);
            if (dtx == null) return null;
            // Field "a" in dtx is the ControllerServiceBridge.Callbacks (NativeCallbacks).
            Field af = dtx.getClass().getDeclaredField("a");
            af.setAccessible(true);
            return af.get(dtx);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": getNativeCallbacks error: " + t);
            return null;
        }
    }

    // Read the vtable[0..7] addresses from the GVR native context via Unsafe,
    // scan /proc/self/maps to identify which library each function lives in,
    // and print the exact file offset so we can binary-patch the right .so.
    private void probeVtable(Object nc) {
        try {
            Field ptrField = nc.getClass().getDeclaredField("a");
            ptrField.setAccessible(true);
            long nativePtr = ptrField.getLong(nc);
            XposedBridge.log(TAG + ": Hook VPTR: nativePtr=0x" + Long.toHexString(nativePtr));
            if (nativePtr == 0) return;

            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field uf = unsafeClass.getDeclaredField("theUnsafe");
            uf.setAccessible(true);
            Object unsafe = uf.get(null);
            Method getLong = unsafeClass.getMethod("getLong", long.class);

            long gvrObj    = (long) getLong.invoke(unsafe, nativePtr + 8);
            if (gvrObj == 0) { XposedBridge.log(TAG + ": Hook VPTR: gvrObj is null"); return; }
            long vtablePtr = (long) getLong.invoke(unsafe, gvrObj);
            if (vtablePtr == 0) { XposedBridge.log(TAG + ": Hook VPTR: vtablePtr is null"); return; }

            // Probe inner_ptr slot and connection state
            long innerPtr = (long) getLong.invoke(unsafe, gvrObj + 12);
            XposedBridge.log(TAG + ": Hook VPTR: innerPtr(gvrObj+12)=0x" + Long.toHexString(innerPtr));
            if (innerPtr != 0) {
                Method getIntM = unsafeClass.getMethod("getInt", long.class);
                int connState = (int) getIntM.invoke(unsafe, innerPtr + 0x34);
                XposedBridge.log(TAG + ": Hook VPTR: connState(innerPtr+0x34)=" + connState);
            }

            // Read vtable[0..7] to log all early virtual function pointers
            long[] vt = new long[8];
            for (int i = 0; i < 8; i++) {
                vt[i] = (long) getLong.invoke(unsafe, vtablePtr + (long)(i * 8));
            }
            XposedBridge.log(TAG + ": Hook VPTR: gvrObj=0x" + Long.toHexString(gvrObj));
            XposedBridge.log(TAG + ": Hook VPTR: vtablePtr=0x" + Long.toHexString(vtablePtr));
            for (int i = 0; i < 8; i++) {
                XposedBridge.log(TAG + ": Hook VPTR: vtable[" + i + "]=0x" + Long.toHexString(vt[i]));
            }

            // Parse ALL r-xp mappings from /proc/self/maps into a lookup table.
            // For each vtable entry, find which .so it belongs to and print:
            //   libname  file_offset  (= (addr - map_start) + map_file_offset)
            // This is the offset we need to patch in that .so on disk.
            java.util.List<long[]>  mapStarts   = new java.util.ArrayList<>();
            java.util.List<long[]>  mapEnds     = new java.util.ArrayList<>();
            java.util.List<Long>    mapFOff     = new java.util.ArrayList<>();
            java.util.List<String>  mapNames    = new java.util.ArrayList<>();

            BufferedReader br = new BufferedReader(new FileReader("/proc/self/maps"));
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.contains("r-xp")) continue;
                // Format: start-end perms offset dev inode [path]
                String[] tok = line.trim().split("\\s+");
                if (tok.length < 5) continue;
                String[] range = tok[0].split("-");
                if (range.length != 2) continue;
                try {
                    long s  = Long.parseLong(range[0], 16);
                    long e  = Long.parseLong(range[1], 16);
                    long fo = Long.parseLong(tok[2], 16);
                    String name = tok.length >= 6 ? tok[5] : "(anon)";
                    mapStarts.add(new long[]{s});
                    mapEnds.add(new long[]{e});
                    mapFOff.add(fo);
                    mapNames.add(name);
                } catch (NumberFormatException ignored) {}
            }
            br.close();

            // For each vtable entry, find its mapping
            for (int i = 0; i < 8; i++) {
                long addr = vt[i];
                boolean found = false;
                for (int m = 0; m < mapNames.size(); m++) {
                    long s = mapStarts.get(m)[0];
                    long e = mapEnds.get(m)[0];
                    if (addr >= s && addr < e) {
                        long fileOff = (addr - s) + mapFOff.get(m);
                        String libName = mapNames.get(m);
                        // Shorten path to just filename
                        int slash = libName.lastIndexOf('/');
                        if (slash >= 0) libName = libName.substring(slash + 1);
                        XposedBridge.log(TAG + ": Hook VPTR: vtable[" + i + "] → "
                                + libName + " @ 0x" + Long.toHexString(fileOff));
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    XposedBridge.log(TAG + ": Hook VPTR: vtable[" + i + "] → (no mapping found)");
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Hook VPTR error: " + t);
        }
    }

    // ── Hook Tile: force placeholder icons + fix text visibility ────────────
    // Only applies to our fake store tiles, identified by egr.j starting with "FAKE:".
    // buildStoreResponse() sets egr.j = "FAKE:" + pkg; real tiles never have this field set.
    // egr.b holds the real GitHub icon URL; egw.a is set from it before bhd.a() runs.
    // Text visibility: tint the info-bg entity dark and the title entity white so text
    // is readable on both the dark VR cave and the white 2D discovery panel.
    private void hookAppTile(ClassLoader cl) {
        try {
            Class<?> bhdClass = XposedHelpers.findClass("bhd", cl);
            Class<?> eguClass = XposedHelpers.findClass("egu", cl);
            Class<?> eiaClass = XposedHelpers.findClass("eia", cl);
            final Class<?> mathfuClass = XposedHelpers.findClass(
                    "com.google.vr.internal.lullaby.Mathfu", cl);
            final Class<?> vec4Class = XposedHelpers.findClass(
                    "com.google.vr.internal.lullaby.Mathfu$Vec4", cl);

            // Find eia.a(Vec4) by exact parameter type — avoids callMethod ambiguity
            // with other "a" overloads (a(String), a(String,Object), etc.)
            java.lang.reflect.Method colorMethod = null;
            for (Class<?> c = eiaClass; c != null && colorMethod == null; c = c.getSuperclass()) {
                for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                    if (m.getName().equals("a") && m.getParameterCount() == 1
                            && m.getParameterTypes()[0] == vec4Class) {
                        colorMethod = m;
                        break;
                    }
                }
            }
            if (colorMethod != null) colorMethod.setAccessible(true);
            final java.lang.reflect.Method finalColorMethod = colorMethod;
            XposedBridge.log(TAG + ": Hook Tile: eia.a(Vec4) = " + colorMethod);

            // Scan all Vec4 constructors: prefer 4-param (static inner class) over
            // 5-param (non-static, first param = outer Mathfu instance).
            java.lang.reflect.Constructor<?> _ctor4 = null, _ctor5 = null;
            for (java.lang.reflect.Constructor<?> c : vec4Class.getDeclaredConstructors()) {
                if (c.getParameterCount() == 4) _ctor4 = c;
                else if (c.getParameterCount() == 5) _ctor5 = c;
            }
            final java.lang.reflect.Constructor<?> vec4Ctor = _ctor4 != null ? _ctor4 : _ctor5;
            if (vec4Ctor == null) throw new RuntimeException("Hook Tile: no Vec4 ctor with 4 or 5 params");
            vec4Ctor.setAccessible(true);
            final Object mathfuInst = (vec4Ctor.getParameterCount() == 5)
                    ? mathfuClass.newInstance() : null;
            XposedBridge.log(TAG + ": Hook Tile: Vec4 ctor params=" + vec4Ctor.getParameterCount()
                    + " mathfuInst=" + (mathfuInst != null));

            XposedHelpers.findAndHookMethod(bhdClass, "a",
                    eguClass, int.class, int.class, int.class,
                    new XC_MethodHook() {
                        // BEFORE native bhd.a() runs: ensure egw.a is set to the GitHub icon URL
                        // so bhd.a()'s image loader (bkhVar.l.a) picks it up via aeg.c() which
                        // passes non-fife URLs through unchanged. buildStoreResponse already sets
                        // egw.a, but this is a safety net in case the egw object was replaced.
                        @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                            try {
                                Object eguArg = p.args[0];
                                Object egrObj = XposedHelpers.getObjectField(eguArg, "b");
                                if (egrObj == null) return;
                                String marker = (String) XposedHelpers.getObjectField(egrObj, "j");
                                if (marker == null || !marker.startsWith("FAKE:")) return;
                                String pkg = marker.substring(5);
                                String iconUrl = (String) XposedHelpers.getObjectField(egrObj, "b");
                                if (iconUrl == null || iconUrl.isEmpty()) return;
                                Object egwObj = XposedHelpers.getObjectField(egrObj, "q");
                                if (egwObj != null) {
                                    XposedHelpers.setObjectField(egwObj, "a", iconUrl); // foreground
                                    XposedHelpers.setObjectField(egwObj, "b", iconUrl); // background — required by bhd.a()
                                    XposedBridge.log(TAG + ": Hook Tile: egw.a+b confirmed for " + pkg);
                                }
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + ": Hook Tile beforeHook error: " + t);
                            }
                        }

                        // AFTER native bhd.a() runs: set background placeholder, apply dark bg + white title colors.
                        @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                            Object tile = p.getResult();
                            if (tile == null) return;
                            try {
                                Object eguArg = p.args[0];
                                Object egrObj = XposedHelpers.getObjectField(eguArg, "b");
                                if (egrObj == null) return;
                                String marker = (String) XposedHelpers.getObjectField(egrObj, "j");
                                if (marker == null || !marker.startsWith("FAKE:")) return;
                            } catch (Throwable t) { return; }

                            try {
                                String tileN = null;
                                try { tileN = (String) XposedHelpers.getObjectField(tile, "n"); }
                                catch (Throwable ignored) {}
                                XposedBridge.log(TAG + ": Hook Tile afterHook: tile.n=" + tileN);

                                // Background: native code skipped tile.o (egw.b not set), apply placeholder
                                XposedHelpers.setObjectField(tile, "o",
                                        "textures/app_icon_background_placeholder.webp");
                                // If native code didn't load any foreground (file:// not supported),
                                // fall back to placeholder so the tile isn't completely blank.
                                if (tileN == null || tileN.isEmpty()) {
                                    XposedHelpers.setObjectField(tile, "n",
                                            "textures/app_icon_foreground_placeholder.webp");
                                }
                                tile.getClass().getMethod("d").invoke(tile);

                                Object entity_i = XposedHelpers.getObjectField(tile, "i");
                                Object entity_j = XposedHelpers.getObjectField(tile, "j");
                                if (finalColorMethod != null && entity_i != null && entity_j != null) {
                                    Object darkBg = mathfuInst != null
                                            ? vec4Ctor.newInstance(mathfuInst, 0.1f, 0.1f, 0.1f, 0.85f)
                                            : vec4Ctor.newInstance(0.1f, 0.1f, 0.1f, 0.85f);
                                    Object white = mathfuInst != null
                                            ? vec4Ctor.newInstance(mathfuInst, 1.0f, 1.0f, 1.0f, 1.0f)
                                            : vec4Ctor.newInstance(1.0f, 1.0f, 1.0f, 1.0f);
                                    finalColorMethod.invoke(entity_i, darkBg);
                                    finalColorMethod.invoke(entity_j, white);
                                    XposedBridge.log(TAG + ": Hook Tile: colors applied");
                                }
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + ": Hook Tile afterHook error: " + t);
                            }
                        }
                    });
            XposedBridge.log(TAG + ": Hook Tile installed");
        } catch (Throwable e) { XposedBridge.log(TAG + ": Hook Tile FAILED: " + e); }
    }

    // ── Hook AegBg: fix non-fife background URL ───────────────────────────────
    // aeg.b(String, int, RectF) instance method always appends "-fcrop64=1,..." crop params,
    // even for non-fife URLs (e.g. GitHub). This corrupts the background URL, Glide fails,
    // alz.d() sets e=true, and alz.c() then refuses to deliver the foreground bitmap either.
    // Fix: for non-fife URLs, return str unchanged (same as aeg.c() does for foreground).
    private void hookAegBackground(ClassLoader cl) {
        try {
            Class<?> aegClass = XposedHelpers.findClass("aeg", cl);
            XposedHelpers.findAndHookMethod(aegClass, "b",
                    String.class, int.class, android.graphics.RectF.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                            String url = (String) p.args[0];
                            if (url == null) return;
                            if (url.contains("googleusercontent") || url.contains("ggpht.com")) return;
                            // Non-fife URL: return unchanged, bypassing the -fcrop64 suffix append.
                            // store.json provides separate icon_url (foreground) and background_url
                            // (banner), so foreground and background load different images.
                            p.setResult(url);
                        }
                    });
            XposedBridge.log(TAG + ": Hook AegBg installed");
        } catch (Throwable e) { XposedBridge.log(TAG + ": Hook AegBg FAILED: " + e); }
    }

    // ── Hook PS: Private store — intercept gRPC call, return JSON-sourced response ─
    // amu.a(Account, dvq, ada) is the store fetch entry point. We skip the dead
    // Google gRPC endpoint entirely and construct dvw→dwa→egv[]→egu[]→egr objects
    // from store.json fetched from STORE_JSON_URL. The success callback ada.a(dvw)
    // then follows the normal 200 path in biw.
    private static final String STORE_JSON_URL =
            "https://raw.githubusercontent.com/patapon888/Daydream-Everywhere/main/store.json";

    private void hookStoreRequest(ClassLoader cl) {
        try {
            Class<?> amuClass  = XposedHelpers.findClass("amu",  cl);
            Class<?> dvqClass  = XposedHelpers.findClass("dvq",  cl);
            Class<?> adaClass  = XposedHelpers.findClass("ada",  cl);

            XposedHelpers.findAndHookMethod(amuClass, "a",
                    Account.class, dvqClass, adaClass,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            // Intercept store GetCollection calls for both the VR play store
                            // (biw → bit.c() renders the Lullaby tile row) and the 2D discovery
                            // tab (ahd → agv.a() renders the RecyclerView list). Both use amu.a()
                            // with the same dvw response format; all other callbacks pass through.
                            String cbName = p.args[2].getClass().getSimpleName();
                            XposedBridge.log(TAG + ": Hook PS: amu.a() cb=" + cbName);
                            // biw = VR play store, ahd = 2D discovery, bcw = VR library
                            if (!"biw".equals(cbName) && !"ahd".equals(cbName) && !"bcw".equals(cbName)) {
                                return;
                            }
                            p.setResult(null); // skip real gRPC call
                            final Object ada = p.args[2];
                            // ahd (2D discovery) updates RecyclerView → needs main thread.
                            // biw (VR store) and bcw (VR library) use Lullaby → thread-agnostic.
                            final boolean isDiscovery = "ahd".equals(cbName);
                            new Thread(() -> {
                                try {
                                    // bcw (VR library) needs egu.a=3 so bcd.b() hits case 3 → bby
                                    // controller → non-null bfj tile. biw/ahd must NOT have egu.a set:
                                    // ahu.b() returns viewType 2 instead of -2 when egu.a is non-null,
                                    // which breaks the DiscoveryCard layout and makes Apps tiles vanish.
                                    Object dvw = buildStoreResponse(cl, "bcw".equals(cbName));
                                    Method cb = null;
                                    for (Method m : ada.getClass().getMethods()) {
                                        if (m.getName().equals("a") && m.getParameterCount() == 1
                                                && m.getParameterTypes()[0] == Object.class) {
                                            cb = m; break;
                                        }
                                    }
                                    if (cb == null) {
                                        cb = ada.getClass().getDeclaredMethod("a", Object.class);
                                    }
                                    cb.setAccessible(true);
                                    final Method finalCb = cb;
                                    final Object finalDvw = dvw;
                                    if (isDiscovery) {
                                        // 2D discovery: agv renders into a RecyclerView, which
                                        // requires the main thread. Delivering from DaydreamStore
                                        // thread causes a CalledFromWrongThreadException that is
                                        // silently swallowed inside the 444-instruction a(dvw,int).
                                        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                                            try {
                                                finalCb.invoke(ada, finalDvw);
                                                XposedBridge.log(TAG + ": Hook PS: [ahd] discovery delivered on main thread");
                                            } catch (Throwable t) {
                                                XposedBridge.log(TAG + ": Hook PS: [ahd] main-thread error: " + t);
                                            }
                                        });
                                    } else {
                                        // biw/bcw: Lullaby is thread-agnostic but bcw's internal
                                        // Handler creation requires a Looper — deliver on main thread.
                                        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                                            try {
                                                finalCb.invoke(ada, finalDvw);
                                                XposedBridge.log(TAG + ": Hook PS: [" + cbName + "] delivered on main thread");
                                            } catch (Throwable t) {
                                                XposedBridge.log(TAG + ": Hook PS: [" + cbName + "] main-thread error: " + t);
                                            }
                                        });
                                    }
                                } catch (Throwable t) {
                                    Throwable root = t;
                                    while (root.getCause() != null) root = root.getCause();
                                    XposedBridge.log(TAG + ": Hook PS: error=" + t.getClass().getSimpleName()
                                            + " root=" + root.getClass().getSimpleName() + ": " + root.getMessage());
                                    StackTraceElement[] st = root.getStackTrace();
                                    for (int ii = 0; ii < Math.min(5, st.length); ii++)
                                        XposedBridge.log(TAG + ":   at " + st[ii]);
                                }
                            }, "DaydreamStore").start();
                        }
                    });
            XposedBridge.log(TAG + ": Hook PS installed");
        } catch (Throwable e) { XposedBridge.log(TAG + ": Hook PS FAILED: " + e); }
    }

    private Object buildStoreResponse(ClassLoader cl, boolean forLibrary) throws Exception {
        // Fetch JSON
        HttpURLConnection conn = (HttpURLConnection) new URL(STORE_JSON_URL).openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
            String line; while ((line = r.readLine()) != null) sb.append(line);
        }
        JSONObject json = new JSONObject(sb.toString());
        JSONArray collections = json.getJSONArray("collections");

        // Build egv[] (one per collection)
        Class<?> egvClass = XposedHelpers.findClass("egv", cl);
        Class<?> eguClass = XposedHelpers.findClass("egu", cl);
        Class<?> egrClass = XposedHelpers.findClass("egr", cl);
        Class<?> egwClass = XposedHelpers.findClass("egw", cl);
        Class<?> egzClass = XposedHelpers.findClass("egz", cl);
        Class<?> egyClass = XposedHelpers.findClass("egy", cl);
        Class<?> dwaClass = XposedHelpers.findClass("dwa", cl);
        Class<?> dvzClass = XposedHelpers.findClass("dvz", cl);
        Class<?> dvwClass = XposedHelpers.findClass("dvw", cl);

        Object[] egvArr = (Object[]) java.lang.reflect.Array.newInstance(egvClass, collections.length());
        for (int i = 0; i < collections.length(); i++) {
            JSONObject col = collections.getJSONObject(i);
            JSONArray apps = col.getJSONArray("apps");

            Object[] eguArr = (Object[]) java.lang.reflect.Array.newInstance(eguClass, apps.length());
            for (int j = 0; j < apps.length(); j++) {
                JSONObject app = apps.getJSONObject(j);
                String pkg = app.getString("package");
                String title = app.getString("title");

                // icon_url = foreground icon (512×512 square), background_url = wide banner (1600×900).
                // egw.a = foreground URL → aeg.c() passes non-fife unchanged → Glide loads it.
                // egw.b = background URL → aeg.b(url,size,RectF) instance method intercepted by
                //         hookAegBackground which returns non-fife URLs unchanged (no -fcrop64 suffix).
                // bhd.a() requires BOTH egw.a AND egw.b non-empty or the entire icon block is skipped.
                String iconUrl = app.optString("icon_url", null);
                // background_url defaults to icon_url if not specified
                String backgroundUrl = app.optString("background_url", iconUrl);

                Object egr = egrClass.newInstance();
                XposedHelpers.setObjectField(egr, "j", "FAKE:" + pkg); // marker for our hooks
                if (iconUrl != null) {
                    XposedHelpers.setObjectField(egr, "b", iconUrl);    // icon URL for 2D discovery
                }
                XposedHelpers.setObjectField(egr, "c", title); // display title (bkhVar.j.b(egr.c))
                XposedHelpers.setObjectField(egr, "k", pkg);   // package_name for launch

                // egr.q (DaydreamInfo egw) must be non-null for bhw.a(egr) to return true.
                // egw.a = foreground icon URL, egw.b = background banner URL.
                // egw.d = motion type Integer: 1=NO_MOTION, 2=MODERATE, 3=INTENSE.
                int motionType = app.optInt("motion", 2);
                Object egw = egwClass.newInstance();
                XposedHelpers.setObjectField(egw, "d", Integer.valueOf(motionType));
                if (iconUrl != null) {
                    XposedHelpers.setObjectField(egw, "a", iconUrl);       // foreground icon
                    XposedHelpers.setObjectField(egw, "b", backgroundUrl); // background banner
                }
                XposedHelpers.setObjectField(egr, "q", egw);

                if (iconUrl != null) {
                    XposedBridge.log(TAG + ": Hook PS: icon URL set for " + pkg + " → " + iconUrl);
                }

                Object egu = eguClass.newInstance();
                XposedHelpers.setObjectField(egu, "b", egr);
                // egu.a = content type Integer (APP_CONTENT_TYPE = 3) — only for VR library (bcw).
                // Must NOT be set for 2D discovery (ahd/biw): breaks DiscoveryCard layout.
                if (forLibrary) {
                    XposedHelpers.setObjectField(egu, "a", Integer.valueOf(3));
                }
                // egu.c = egz (content metadata). Both DiscoveryCard and DiscoverySlide check egu.c != null.
                Object egz = egzClass.newInstance();
                if (iconUrl != null) {
                    Object egyItem = egyClass.newInstance();
                    XposedHelpers.setObjectField(egyItem, "a", iconUrl);
                    XposedHelpers.setObjectField(egyItem, "d", Integer.valueOf(4)); // PREVIEW
                    Object egyArr = java.lang.reflect.Array.newInstance(egyClass, 1);
                    java.lang.reflect.Array.set(egyArr, 0, egyItem);
                    XposedHelpers.setObjectField(egz, "d", egyArr);
                }
                XposedHelpers.setObjectField(egu, "c", egz);
                eguArr[j] = egu;
            }

            Object egv = egvClass.newInstance();
            XposedHelpers.setObjectField(egv, "a", col.getString("id"));
            XposedHelpers.setObjectField(egv, "b", col.getString("title"));
            XposedHelpers.setObjectField(egv, "d", eguArr);
            egvArr[i] = egv;
        }

        // dvw.e (dwa): used by biw (VR play store) and ahd (2D discovery)
        Object dwa = dwaClass.newInstance();
        XposedHelpers.setObjectField(dwa, "a", egvArr);

        // dvw.c (dvz): used by bcw (VR library). dvz.a is egv[], dvz.a() returns OK by default.
        Object dvz = dvzClass.newInstance();
        XposedHelpers.setObjectField(dvz, "a", egvArr);

        Object dvw = dvwClass.newInstance();
        XposedHelpers.setObjectField(dvw, "e", dwa);
        XposedHelpers.setObjectField(dvw, "c", dvz);
        return dvw;
    }

    // ── Hook DNet: agv.g() network check always returns true ────────────────
    // agv.g() calls ConnectivityManager.getActiveNetworkInfo() which returns null
    // right after process startup on Android 16 (deprecated API, not yet initialized).
    // When null, agv.a(false) calls f() (offline/TRY AGAIN view) instead of amu.a().
    // After orientation change the fragment is retained but onResume fires again;
    // by then getActiveNetworkInfo() has initialized → content loads. Fix: always
    // return true — we bypass the real server anyway via hookStoreRequest.
    private void hookDiscoveryNetworkCheck(ClassLoader cl) {
        try {
            Class<?> agvClass = XposedHelpers.findClass("agv", cl);
            XposedHelpers.findAndHookMethod(agvClass, "g", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    p.setResult(Boolean.TRUE);
                }
            });
            XposedBridge.log(TAG + ": Hook DNet (agv.g) installed");
        } catch (Throwable e) { XposedBridge.log(TAG + ": Hook DNet FAILED: " + e); }
    }

    // ── Hook Lib: force alu.h=true for fake store entries ───────────────────
    // bcm.a(egu, aqe) returns null when (!aluVarA.c() || aluVarA.h) is false.
    // alu.h = afp.a(pkg, null, null) != null — afp tries the Play Store catalog
    // which is dead, so h=false for all our fake entries. With h=false and c()=true
    // (app treated as "purchasable"), both conditions fail and bcm is null → the
    // VR library collection stays empty → shows error state ("HAUT DE PAGE").
    // Fix: after alu.a(egu,...) runs, if the egu is one of our FAKE: entries,
    // force h=true so the bcm is included and the library renders the tile.
    private void hookLibraryFakeEntries(ClassLoader cl) {
        try {
            Class<?> aluClass = XposedHelpers.findClass("alu", cl);
            XposedBridge.hookAllMethods(aluClass, "a", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                    Object result = p.getResult();
                    if (result == null || p.args.length < 1 || p.args[0] == null) return;
                    try {
                        // alu.a(egu, context, afb, afp) has egu as first arg
                        Object egrObj = XposedHelpers.getObjectField(p.args[0], "b");
                        if (egrObj == null) return;
                        String marker = (String) XposedHelpers.getObjectField(egrObj, "j");
                        if (marker == null || !marker.startsWith("FAKE:")) return;
                        XposedHelpers.setBooleanField(result, "h", true);
                        XposedBridge.log(TAG + ": Hook Lib: alu.h=true for " + marker);
                    } catch (Throwable ignored) {}
                }
            });
            XposedBridge.log(TAG + ": Hook Lib (alu.h patch) installed");
        } catch (Throwable e) { XposedBridge.log(TAG + ": Hook Lib FAILED: " + e); }
    }

    // ── Hook DC: Discovery click — launch app by package name ───────────────
    // agv.onEvent(aiz) handles RecyclerView item clicks in the 2D discovery tab.
    // aiz.a type comes from alt enum ordinal: INSTALL_APP(0)→1, UPDATE_APP(1)→2, OPEN_APP(2)→3.
    // For installed apps with dead afp, alu.a() hits the z6 branch → i=OPEN_APP → type=3.
    // als.b() for type=3 calls afp.b(pkg,null,null) → DaydreamApi launch → fails without
    // VrManager → fallback Play Store URL is null → nothing. For type=1, als.a() opens
    // market://details?id=pkg (Play Store is present on device, so something happens visually).
    // Fix: intercept types 1/2/3 and launch by PackageManager directly for all.
    // NOTE: JADX puts these in "defpackage.*" but the actual dex has no package prefix
    // (same as amu, dvq, ada, bhd, egu etc.). Use bare class names.
    private void hookDiscoveryClick(ClassLoader cl) {
        try {
            Class<?> agvClass = XposedHelpers.findClass("agv", cl);
            Class<?> aizClass = XposedHelpers.findClass("aiz", cl);
            XposedHelpers.findAndHookMethod(agvClass, "onEvent", aizClass, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                    Object aiz = p.args[0];
                    int type = XposedHelpers.getIntField(aiz, "a");
                    String rawPkg = (String) XposedHelpers.getObjectField(aiz, "c");
                    String rawD   = null;
                    try { rawD = (String) XposedHelpers.getObjectField(aiz, "d"); } catch (Throwable ignored) {}
                    XposedBridge.log(TAG + ": Hook DC: onEvent type=" + type
                            + " c='" + rawPkg + "' d='" + rawD + "'");
                    // Type 1=INSTALL_APP, 2=UPDATE_APP, 3=OPEN_APP — all have pkg in aiz.c
                    if (type != 1 && type != 2 && type != 3) return;
                    String pkg = rawPkg;
                    if (pkg == null || pkg.isEmpty()) return;
                    // Strip FAKE: marker if our buildStoreResponse put it in egr.b instead of egr.k
                    if (pkg.startsWith("FAKE:")) pkg = pkg.substring(5);
                    try {
                        // agv.h is the Activity reference (public Activity h)
                        Activity activity = (Activity) XposedHelpers.getObjectField(p.thisObject, "h");
                        if (activity == null) return;
                        android.content.pm.PackageManager pm = activity.getPackageManager();
                        // Try Daydream VR intent first — apps like Photos VR register a
                        // separate activity with DAYDREAM category distinct from the LAUNCHER.
                        // getLaunchIntentForPackage would hit the non-VR launcher activity.
                        android.content.Intent vrIntent = new android.content.Intent("android.intent.action.MAIN");
                        vrIntent.addCategory("com.google.intent.category.DAYDREAM");
                        vrIntent.setPackage(pkg);
                        android.content.pm.ResolveInfo ri = pm.resolveActivity(vrIntent, 0);
                        if (ri != null) {
                            vrIntent.setComponent(new android.content.ComponentName(
                                    ri.activityInfo.packageName, ri.activityInfo.name));
                            activity.startActivity(vrIntent);
                            p.setResult(null);
                            XposedBridge.log(TAG + ": Hook DC: launched " + pkg + " via DAYDREAM category");
                        } else {
                            android.content.Intent launchIntent = pm.getLaunchIntentForPackage(pkg);
                            if (launchIntent != null) {
                                activity.startActivity(launchIntent);
                                p.setResult(null);
                                XposedBridge.log(TAG + ": Hook DC: launched " + pkg + " via default intent");
                            } else {
                                XposedBridge.log(TAG + ": Hook DC: no intent for " + pkg);
                            }
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": Hook DC error: " + t);
                    }
                }
            });
            XposedBridge.log(TAG + ": Hook DC installed");
        } catch (Throwable e) { XposedBridge.log(TAG + ": Hook DC FAILED: " + e); }
    }

    // ── Hook VrQ: Intercept is_in_vr_session ContentProvider read ───────────
    // The library/discovery tabs query content://com.google.vr.vrcore.settings/boolean_settings
    // for is_in_vr_session to decide whether to show content. On fresh startup vrcore may not
    // have started yet, or the INSERT from Hook 8 may land after the query fires. Fix: intercept
    // any ContentResolver.query on that URI in the vr.home process and always return true.
    private void hookVrSessionQuery() {
        XC_MethodHook interceptor = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                Uri uri = (Uri) p.args[0];
                if (uri == null) return;
                String uriStr = uri.toString();
                if (!uriStr.contains("boolean_settings")) return;
                android.database.MatrixCursor fake = new android.database.MatrixCursor(
                        new String[]{"name", "value"});
                fake.addRow(new Object[]{"is_in_vr_session", "true"});
                p.setResult(fake);
                XposedBridge.log(TAG + ": Hook VrQ: boolean_settings query intercepted → is_in_vr_session=true");
            }
        };
        // 5-arg form: query(Uri, String[], String, String[], String)
        try {
            XposedHelpers.findAndHookMethod(android.content.ContentResolver.class, "query",
                    Uri.class, String[].class, String.class, String[].class, String.class,
                    interceptor);
            XposedBridge.log(TAG + ": Hook VrQ(5-arg) installed");
        } catch (Throwable e) { XposedBridge.log(TAG + ": Hook VrQ(5-arg) FAILED: " + e); }
        // Bundle-based form: query(Uri, String[], Bundle, CancellationSignal)
        try {
            XposedHelpers.findAndHookMethod(android.content.ContentResolver.class, "query",
                    Uri.class, String[].class, android.os.Bundle.class, android.os.CancellationSignal.class,
                    interceptor);
            XposedBridge.log(TAG + ": Hook VrQ(Bundle) installed");
        } catch (Throwable e) { XposedBridge.log(TAG + ": Hook VrQ(Bundle) FAILED: " + e); }
    }

    // ── Hook YT: YouTube VR edge-to-edge (hide system bars + disable cutout) ──
    // YouTube VR renders stereo at the wrong eye separation because the Pixel 8
    // display cutout (camera hole, 132px) adds a left-edge inset. Two fixes needed:
    // 1. LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES: render into the cutout area.
    // 2. WindowInsetsController.hide(systemBars()): removes nav/status bars.
    // Applied on every onWindowFocusChanged(true) to survive Activity transitions.
    private void hookYouTubeVrEdgeToEdge() {
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onWindowFocusChanged",
                    boolean.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                            if (!(boolean) p.args[0]) return;
                            Activity a = (Activity) p.thisObject;
                            try {
                                android.view.Window win = a.getWindow();
                                // Extend layout into the display cutout region
                                android.view.WindowManager.LayoutParams lp = win.getAttributes();
                                lp.layoutInDisplayCutoutMode =
                                        android.view.WindowManager.LayoutParams
                                                .LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                                win.setAttributes(lp);
                                // Hide all system bars (status + navigation)
                                android.view.WindowInsetsController wic = win.getInsetsController();
                                if (wic != null) {
                                    wic.hide(android.view.WindowInsets.Type.systemBars());
                                    wic.setSystemBarsBehavior(
                                            android.view.WindowInsetsController
                                                    .BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                                }
                                XposedBridge.log(TAG + ": Hook YT: edge-to-edge + cutout applied");
                            } catch (Throwable t) { XposedBridge.log(TAG + ": Hook YT error: " + t); }
                        }
                    });
            XposedBridge.log(TAG + ": Hook YT (edge-to-edge) installed");
        } catch (Throwable e) { XposedBridge.log(TAG + ": Hook YT FAILED: " + e); }
    }

    // ── Hook FFX: Android 16 ContextWrapper.getDisplayId() NPE in Firefox Reality ─
    // Android 16 added context.getDisplayId() inside ViewConfiguration.<init>(), called
    // on every View construction. VR SDK (GVR/Gecko) creates ContextWrapper instances
    // with a null mBase as part of VR surface setup. On Android < 16 this was never
    // called; now it NPEs immediately. Fix: return default display (0) when mBase=null.
    private void hookFirefoxContextWrapper() {
        try {
            XposedHelpers.findAndHookMethod(android.content.ContextWrapper.class, "getDisplayId",
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                            try {
                                java.lang.reflect.Field f =
                                        android.content.ContextWrapper.class.getDeclaredField("mBase");
                                f.setAccessible(true);
                                if (f.get(p.thisObject) == null) {
                                    p.setResult(0);
                                    XposedBridge.log(TAG + ": Hook FFX: getDisplayId null mBase → 0");
                                }
                            } catch (Throwable ignored) {}
                        }
                    });
            XposedBridge.log(TAG + ": Hook FFX (ContextWrapper.getDisplayId) installed");
        } catch (Throwable e) { XposedBridge.log(TAG + ": Hook FFX FAILED: " + e); }
    }

    // ── Hook GVR: catch GvrLayout init failure in Firefox Reality ───────────
    // Firefox Reality calls GvrLayout.createGvrLayout(Activity) on startup.
    // On Android 16 without VrManager, GVR initialization fails and may throw
    // or call exit(). Wrapping in afterHook clears the exception so Firefox can
    // degrade gracefully instead of crashing.
    private void hookGvrLayout(ClassLoader cl) {
        try {
            Class<?> gvrLayout = XposedHelpers.findClass("com.google.vr.sdk.base.GvrLayout", cl);
            XposedHelpers.findAndHookMethod(gvrLayout, "createGvrLayout",
                    android.content.Context.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                            if (p.hasThrowable()) {
                                XposedBridge.log(TAG + ": Hook GVR: GvrLayout.createGvrLayout threw: "
                                        + p.getThrowable() + " — suppressing");
                                p.setThrowable(null);
                                p.setResult(null);
                            } else {
                                XposedBridge.log(TAG + ": Hook GVR: GvrLayout.createGvrLayout → "
                                        + p.getResult());
                            }
                        }
                    });
            XposedBridge.log(TAG + ": Hook GVR (Firefox GvrLayout) installed");
        } catch (Throwable e) { XposedBridge.log(TAG + ": Hook GVR (Firefox GvrLayout) FAILED: " + e); }
    }

    // ── Hook Firefox Reality VRService: fake HTC VR service connection ──────
    // Firefox Reality (VRBrowser) was built for HTC Vive Focus and tries to bind
    // com.htc.vr.core.server.VRService on startup. That service doesn't exist on
    // a Pixel 8, causing VRActivityDecorator to call finish() then System.exit(0).
    // Fix: intercept bindService for the HTC and Google VR service packages,
    // return true (binding "in progress"), then deliver onServiceConnected with a
    // stub Binder on the main thread. Also suppress System.exit as a safety net.
    private void hookFirefoxVrService() {
        // Fake the VRService binding so VRActivityDecorator doesn't call finish().
        try {
            XposedHelpers.findAndHookMethod(android.content.ContextWrapper.class, "bindService",
                android.content.Intent.class,
                android.content.ServiceConnection.class,
                int.class,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                        android.content.Intent intent = (android.content.Intent) p.args[0];
                        if (intent == null) return;
                        android.content.ComponentName comp = intent.getComponent();
                        String pkg = comp != null ? comp.getPackageName() : intent.getPackage();
                        if (pkg == null) return;
                        if (!pkg.contains("htc.vr") && !pkg.contains("google.vr.vrcore")) return;

                        final android.content.ServiceConnection conn =
                            (android.content.ServiceConnection) p.args[1];
                        final android.content.ComponentName name = comp != null ? comp
                            : new android.content.ComponentName(pkg, pkg + ".VRService");
                        XposedBridge.log(TAG + ": Hook FFX: intercepting bindService for " + pkg);

                        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                            try {
                                android.os.Binder stub = new android.os.Binder();
                                conn.onServiceConnected(name, stub);
                                XposedBridge.log(TAG + ": Hook FFX: faked onServiceConnected for " + name.flattenToShortString());
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + ": Hook FFX: faked onServiceConnected error: " + t);
                            }
                        }, 200);
                        p.setResult(Boolean.TRUE);
                    }
                });
            XposedBridge.log(TAG + ": Hook FFX (bindService) installed");
        } catch (Throwable e) { XposedBridge.log(TAG + ": Hook FFX bindService FAILED: " + e); }

        // Safety net: suppress System.exit() so a VR init failure doesn't kill the process.
        try {
            XposedHelpers.findAndHookMethod(java.lang.System.class, "exit", int.class,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                        int code = (int) p.args[0];
                        XposedBridge.log(TAG + ": Hook FFX: suppressed System.exit(" + code + ")");
                        p.setResult(null);
                    }
                });
            XposedBridge.log(TAG + ": Hook FFX (System.exit) installed");
        } catch (Throwable e) { XposedBridge.log(TAG + ": Hook FFX System.exit FAILED: " + e); }
    }

    // ── Hook DaydreamApi: force Daydream mode in Photos VR ───────────────────
    // Google Photos VR uses several gateways to decide Daydream vs Cardboard mode.
    // All must return true/non-null on Android 16 without native VrManager support.
    private void hookDaydreamApi(ClassLoader cl) {
        // DaydreamApi itself
        try {
            Class<?> daydreamApiClass = XposedHelpers.findClass(
                    "com.google.vr.ndk.base.DaydreamApi", cl);

            XC_MethodHook trueHook = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    p.setResult(Boolean.TRUE);
                    XposedBridge.log(TAG + ": DaydreamApi." + p.method.getName() + " → true");
                }
            };

            // isDaydreamEnabled(Context) — static check for device Daydream capability
            try {
                XposedHelpers.findAndHookMethod(daydreamApiClass, "isDaydreamEnabled",
                        android.content.Context.class, trueHook);
            } catch (Throwable t) { XposedBridge.log(TAG + ": DaydreamApi.isDaydreamEnabled: " + t); }

            // isDaydreamCurrentViewer() — checks current viewer type
            try {
                XposedHelpers.findAndHookMethod(daydreamApiClass, "isDaydreamCurrentViewer", trueHook);
            } catch (Throwable t) { XposedBridge.log(TAG + ": DaydreamApi.isDaydreamCurrentViewer: " + t); }

            // create(Activity) — returns null when VrManager absent; null causes Cardboard fallback.
            // Fix: if create() returns null, bypass VrManager by calling the private constructor
            // directly via reflection. DaydreamApi instances created this way have no live service
            // connection but are non-null, which is enough for Photos VR's mode-selection check.
            try {
                XposedHelpers.findAndHookMethod(daydreamApiClass, "create",
                        android.app.Activity.class, new XC_MethodHook() {
                            @Override protected void afterHookedMethod(MethodHookParam p) {
                                if (p.getResult() != null) {
                                    XposedBridge.log(TAG + ": DaydreamApi.create → non-null (real)");
                                    return;
                                }
                                if (p.hasThrowable()) {
                                    p.setThrowable(null);
                                }
                                // Try every declared constructor — log them all first, then use best fit.
                                android.app.Activity activity = (android.app.Activity) p.args[0];
                                java.lang.reflect.Constructor<?>[] ctors =
                                        daydreamApiClass.getDeclaredConstructors();
                                for (java.lang.reflect.Constructor<?> ctor : ctors) {
                                    ctor.setAccessible(true);
                                    Class<?>[] types = ctor.getParameterTypes();
                                    try {
                                        Object instance;
                                        if (types.length == 0) {
                                            instance = ctor.newInstance();
                                        } else if (types.length == 1 && types[0].isAssignableFrom(android.app.Activity.class)) {
                                            instance = ctor.newInstance(activity);
                                        } else if (types.length == 1 && types[0].isAssignableFrom(android.content.Context.class)) {
                                            instance = ctor.newInstance((android.content.Context) activity);
                                        } else {
                                            continue;
                                        }
                                        p.setResult(instance);
                                        XposedBridge.log(TAG + ": DaydreamApi.create → forced non-null via " + ctor);
                                        return;
                                    } catch (Throwable t) {
                                        XposedBridge.log(TAG + ": DaydreamApi.create ctor " + ctor + " failed: " + t);
                                    }
                                }
                                XposedBridge.log(TAG + ": DaydreamApi.create → still null (no usable ctor found)");
                            }
                        });
            } catch (Throwable t) { XposedBridge.log(TAG + ": DaydreamApi.create: " + t); }

            XposedBridge.log(TAG + ": Hook DaydreamApi installed");
        } catch (Throwable e) { XposedBridge.log(TAG + ": Hook DaydreamApi FAILED: " + e); }

        // DaydreamUtils.isDaydreamViewer(DeviceParams) — same check as vrcore Hook H,
        // but Photos VR has its own GVR SDK copy in its own classloader.
        try {
            Class<?> daydreamUtils = XposedHelpers.findClass(
                    "com.google.vr.ndk.base.DaydreamUtils", cl);
            XposedBridge.hookAllMethods(daydreamUtils, "isDaydreamViewer", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (p.args.length > 0 && p.args[0] != null) {
                        p.setResult(Boolean.TRUE);
                        XposedBridge.log(TAG + ": DaydreamUtils.isDaydreamViewer → true (Photos)");
                    }
                }
            });
            XposedBridge.log(TAG + ": Hook DaydreamUtils (Photos) installed");
        } catch (Throwable e) { XposedBridge.log(TAG + ": Hook DaydreamUtils (Photos) FAILED: " + e); }
    }

    // ── Hook H: Accept any headset as Daydream-compatible ───────────────────
    private void hookHeadsetCheck(ClassLoader cl) {
        // DaydreamUtils.isDaydreamViewer(DeviceParams) → always true for non-null params.
        // This bypasses the "daydream_internal" proto field check that rejects Cardboard/Strax headsets.
        try {
            Class<?> daydreamUtils = XposedHelpers.findClass(
                    "com.google.vr.ndk.base.DaydreamUtils", cl);
            Class<?> deviceParams = XposedHelpers.findClass(
                    "com.google.vr.sdk.proto.CardboardDevice$DeviceParams", cl);
            XposedHelpers.findAndHookMethod(daydreamUtils, "isDaydreamViewer", deviceParams,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            // return true for any non-null DeviceParams
                            if (p.args[0] != null) {
                                p.setResult(Boolean.TRUE);
                                XposedBridge.log(TAG + ": Hook H: isDaydreamViewer → true");
                            }
                        }
                    });
            XposedBridge.log(TAG + ": Hook H: isDaydreamViewer hooked");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Hook H: isDaydreamViewer hook failed: " + t);
        }

        // deh.h() → always false: disables the DON incompatible-headset check gate.
        try {
            Class<?> dehClass = XposedHelpers.findClass("defpackage.deh", cl);
            XposedHelpers.findAndHookMethod(dehClass, "h", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    p.setResult(Boolean.FALSE);
                }
            });
            XposedBridge.log(TAG + ": Hook H: deh.h() hooked (incompatible headset check disabled)");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Hook H: deh.h() hook failed: " + t);
        }
    }
}