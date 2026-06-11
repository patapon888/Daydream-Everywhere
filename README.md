# Daydream Everywhere

**Run Google Daydream VR on Pixel 8 (Android 16) — and likely other modern Android devices.**

Google Daydream was officially discontinued, but with a few patches it still works perfectly on a Pixel 8 running Android 16. This LSPosed module + native binary patches fix all the compatibility breaks introduced by Android updates.

---

## What works

| Feature | Status |
|---|---|
| 3D VR rendering (no flicker) | ✅ |
| Any headset (Cardboard, Strax, QR-code viewers) | ✅ |
| Daydream View headset | ✅ |
| Controller (Daydream View remote) | ✅ |
| Controller emulator (`com.google.vr.inputcompanion`) | ✅ |
| YouTube VR | ✅ |
| Expeditions | ❌ (app is dead) |
| Daydream store ("Play Store") | ⚠️ Launches, server-side content unavailable (Google shut down the API) |

---

## Requirements

- Android device with **Magisk** root
- **LSPosed** (Vector fork recommended)
- A Daydream-capable device (Pixel 8 confirmed; others may work)
- A headset: Daydream View, any Cardboard-compatible viewer, or a QR-code custom viewer
- A controller: Daydream View remote, **or** a second phone running `com.google.vr.inputcompanion` (controller emulator APK)

---

## Installation

### 1. LSPosed module (DaydreamFix)

1. Download `daydreamfix.apk` from the [latest release](../../releases/latest)
2. Install it: `adb install daydreamfix.apk`
3. In LSPosed Manager → Modules → enable **DaydreamFix** for:
   - `com.google.android.vr.home`
   - `com.google.vr.vrcore`
   - `com.google.android.apps.youtube.vr` (if you use YouTube VR)
4. Force-stop the target apps — **no reboot needed**

### 2. Native binary patches (Magisk module)

The patched `.so` files fix rendering and controller compatibility at the native layer.

> **Pre-built Magisk module coming in a future release.** For now, see the [patches documentation](PATCHES.md) to apply them manually with the included scripts.

---

## What was broken and how it's fixed

### Rendering flicker
Android updates changed how shared memory is allocated. The `ashmem` calls in `libvrcore_native.so` were patched to use anonymous `mmap` instead.

### Controller not connecting
`vr.home` calls two compiled-in GVR functions — `gvr_controller_state_get_connection_state` and `gvr_controller_state_get_api_status` — on every frame to decide whether to show "unable to access your remote". Without the original VR hardware these always return error/disconnected. Both functions are patched in `libvrhome_vrapps_native_lib.so` to always return success.

### Headset rejected ("visionneuse Cardboard non compatible")
`DaydreamUtils.isDaydreamViewer()` checks for a `daydream_internal` proto field that only Daydream View headsets have. Hooked via LSPosed to always return `true` for any non-null `DeviceParams`.

### Android 16 Binder IPC crash
`Parcel.createExceptionOrNull` throws a `NullPointerException` when `setVrModeEnabled` returns an error response (VR hardware no longer exists in Android 16). Hooked via LSPosed to swallow the NPE.

### BLAST buffer queue slot bug
Android 16 changed the BLAST buffer queue API. `singleBufferMode=true` causes slot-2 corruption. Hooked to force `false`.

---

## Building from source

```bash
git clone https://github.com/patapon888/Daydream-Everywhere
cd Daydream-Everywhere
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```

Dependencies: Android SDK, Java 8+. The XposedBridge API is pulled from Maven automatically.

---

## Contributing

PRs welcome — especially:
- Compatibility reports on other devices/Android versions
- Private server implementation for the Daydream store API
- Controller emulator improvements

---

## Credits

Reverse engineering, patches, and module by **patapon888**.  
Built with [LSPosed](https://github.com/LSPosed/LSPosed) / [XposedBridge](https://github.com/rovo89/XposedBridge).
