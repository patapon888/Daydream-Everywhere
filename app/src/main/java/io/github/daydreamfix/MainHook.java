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
    private static final String PKG_DAYDREAM   = "com.google.android.vr.home";
    private static final String PKG_VRCORE     = "com.google.vr.vrcore";
    private static final String PKG_YOUTUBE_VR = "com.google.android.apps.youtube.vr";

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
            hookSurfaceViewType();
            hookControllerServiceBridge(lpparam.classLoader); // Hook CSB (PKG_DAYDREAM classloader)
            hookStoreRequest(lpparam.classLoader);        // Hook PS: private store
        }

        if (lpparam.packageName.equals(PKG_YOUTUBE_VR)) {
            XposedBridge.log(TAG + ": Injecting into " + PKG_YOUTUBE_VR);
            hookParcelReadException();
            hookBlastBufferQueue(lpparam.classLoader);
            hookSurfaceViewType();
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
    private void hookSurfaceViewType() {
        try {
            XposedHelpers.findAndHookMethod(SurfaceView.class, "onAttachedToWindow",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                            SurfaceView sv = (SurfaceView) p.thisObject;
                            Context ctx = sv.getContext();
                            if (ctx == null || !PKG_DAYDREAM.equals(ctx.getPackageName())) return;
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
                @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                    Activity a = (Activity) p.thisObject;
                    if (!a.getClass().getName().equals("com.google.vr.app.Launcher.Launcher")) return;
                    a.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
                    XposedBridge.log(TAG + ": Hook 4: Locked LANDSCAPE");
                    try {
                        ContentValues cv = new ContentValues();
                        cv.put("name", "is_in_vr_session"); cv.put("value", "true");
                        a.getContentResolver().insert(
                                Uri.parse("content://com.google.vr.vrcore.settings/boolean_settings"), cv);
                        XposedBridge.log(TAG + ": Hook 8: is_in_vr_session=true");
                    } catch (Throwable t) { XposedBridge.log(TAG + ": Hook 8 set-true FAILED: " + t); }
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

    // ── Hook PS: Private store — intercept gRPC call, return JSON-sourced response ─
    // amu.a(Account, dvq, ada) is the store fetch entry point. We skip the dead
    // Google gRPC endpoint entirely and construct dvw→dwa→egv[]→egu[]→egr objects
    // from store.json fetched from STORE_JSON_URL. The success callback ada.a(dvw)
    // then follows the normal 200 path in biw.
    private static final String STORE_JSON_URL =
            "https://raw.githubusercontent.com/patapon888/Daydream-Everywhere/main/store.json";

    private void hookStoreRequest(ClassLoader cl) {
        try {
            Class<?> amuClass  = XposedHelpers.findClass("defpackage.amu",  cl);
            Class<?> dvqClass  = XposedHelpers.findClass("defpackage.dvq",  cl);
            Class<?> adaClass  = XposedHelpers.findClass("defpackage.ada",  cl);

            XposedHelpers.findAndHookMethod(amuClass, "a",
                    Account.class, dvqClass, adaClass,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            p.setResult(null); // skip real gRPC call
                            final Object ada = p.args[2];
                            new Thread(() -> {
                                try {
                                    Object dvw = buildStoreResponse(cl);
                                    Method cb = null;
                                    for (Method m : ada.getClass().getMethods()) {
                                        if (m.getName().equals("a") && m.getParameterCount() == 1
                                                && m.getParameterTypes()[0] == Object.class) {
                                            cb = m; break;
                                        }
                                    }
                                    if (cb == null) {
                                        // ada is an interface — find via declared methods on biw
                                        cb = ada.getClass().getDeclaredMethod("a", Object.class);
                                    }
                                    cb.setAccessible(true);
                                    cb.invoke(ada, dvw);
                                    XposedBridge.log(TAG + ": Hook PS: store response delivered");
                                } catch (Throwable t) {
                                    XposedBridge.log(TAG + ": Hook PS: error: " + t);
                                }
                            }, "DaydreamStore").start();
                        }
                    });
            XposedBridge.log(TAG + ": Hook PS installed");
        } catch (Throwable e) { XposedBridge.log(TAG + ": Hook PS FAILED: " + e); }
    }

    private Object buildStoreResponse(ClassLoader cl) throws Exception {
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
        Class<?> egvClass = XposedHelpers.findClass("defpackage.egv", cl);
        Class<?> eguClass = XposedHelpers.findClass("defpackage.egu", cl);
        Class<?> egrClass = XposedHelpers.findClass("defpackage.egr", cl);
        Class<?> dwaClass = XposedHelpers.findClass("defpackage.dwa", cl);
        Class<?> dvwClass = XposedHelpers.findClass("defpackage.dvw", cl);

        Object[] egvArr = (Object[]) java.lang.reflect.Array.newInstance(egvClass, collections.length());
        for (int i = 0; i < collections.length(); i++) {
            JSONObject col = collections.getJSONObject(i);
            JSONArray apps = col.getJSONArray("apps");

            Object[] eguArr = (Object[]) java.lang.reflect.Array.newInstance(eguClass, apps.length());
            for (int j = 0; j < apps.length(); j++) {
                JSONObject app = apps.getJSONObject(j);
                Object egr = egrClass.newInstance();
                XposedHelpers.setObjectField(egr, "b", app.getString("package"));
                XposedHelpers.setObjectField(egr, "c", app.getString("title"));
                Object egu = eguClass.newInstance();
                XposedHelpers.setObjectField(egu, "b", egr);
                eguArr[j] = egu;
            }

            Object egv = egvClass.newInstance();
            XposedHelpers.setObjectField(egv, "a", col.getString("id"));
            XposedHelpers.setObjectField(egv, "b", col.getString("title"));
            XposedHelpers.setObjectField(egv, "d", eguArr);
            egvArr[i] = egv;
        }

        Object dwa = dwaClass.newInstance();
        XposedHelpers.setObjectField(dwa, "a", egvArr);

        Object dvw = dvwClass.newInstance();
        XposedHelpers.setObjectField(dvw, "e", dwa);
        return dvw;
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