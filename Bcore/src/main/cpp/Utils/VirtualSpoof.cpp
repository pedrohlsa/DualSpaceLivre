#include <sys/system_properties.h>
#include <cstring>
#include "./xdl.h"
#include <android/log.h>
#include <dlfcn.h>
#include "Dobby/dobby.h"


#define LOG_TAG "VirtualSpoof"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

struct SpoofedProp {
    const char* key;
    const char* value;
};

// IDENTITY ONLY.
//
// Everything here answers "which product is this"; nothing here answers "what
// can this chip do". That line was crossed once already and it cost the media
// pipeline: ro.hardware, ro.board.platform, ro.product.board and ro.soc.* are
// how Android resolves real vendor code. libhardware's hw_get_module() walks
// ro.hardware.<module> -> ro.hardware -> ro.product.board -> ro.board.platform
// and dlopens /vendor/lib64/hw/<module>.<value>.so, and the EGL loader falls
// back to ro.board.platform when ro.hardware.egl is unset. Pointing all of them
// at a Pixel leaves a MediaTek device looking for Tensor libraries that are not
// on it, while MediaCodecList — built in mediaserver, out of this process's
// reach — keeps reporting the physical c2.mtk.* codecs. The guest then holds a
// device profile whose declared silicon and available encoders disagree.
//
// So: cosmetic identity is virtual, capability stays physical. A fingerprinter
// can notice the seam; a video that never finishes encoding is worse.
SpoofedProp spoofed_props[] = {
        {"ro.product.model", "Pixel 6"},
        {"ro.product.brand", "google"},
        {"ro.product.manufacturer", "Google"},
        {"ro.product.device", "oriole"},
        {"ro.product.name", "oriole"},
        {"ro.build.product", "oriole"},
        {"ro.build.id", "SQ1D.220105.007"},
        {"ro.build.display.id", "SQ1D.220105.007"},
        {"ro.build.version.incremental", "8030436"},
        {"ro.build.fingerprint", "google/oriole/oriole:12/SQ1D.220105.007/8030436:user/release-keys"},
        {"ro.build.version.release", "12"},
        {"ro.build.version.security_patch", "2022-01-05"},
        {"ro.build.type", "user"},
        {"ro.build.tags", "release-keys"},
        {"ro.kernel.qemu", "0"},
        {"ro.kernel.android.qemud", ""},
        {"ro.boot.qemu", "0"},
    {nullptr, nullptr} 
};


static int (*orig_system_property_get)(const char *name, char *value) = nullptr;
static bool spoof_enabled = false;

// The same native library is loaded by the launcher and the :black server.
// Only an initialized guest process may expose the virtual profile.
void enable_virtual_spoof() {
    spoof_enabled = true;
    LOGD("VirtualSpoof enabled for bound guest");
}


int my_system_property_get(const char *name, char *value) {
    if (!spoof_enabled) {
        if (orig_system_property_get) {
            return orig_system_property_get(name, value);
        }
        value[0] = '\0';
        return 0;
    }
    for (int i = 0; spoofed_props[i].key != nullptr; ++i) {
        if (strcmp(name, spoofed_props[i].key) == 0) {
            strcpy(value, spoofed_props[i].value);
             LOGD("[spoof] %s = %s", name, value);
            return strlen(value);
        }
    }
    if (orig_system_property_get) {
        return orig_system_property_get(name, value);
    }
    value[0] = '\0';
    return 0;
}

void install_property_get_hook() {
    void* handle = xdl_open("libc.so", XDL_DEFAULT);
    void* target = xdl_dsym(handle, "__system_property_get", nullptr);
    if (target) {
        if (DobbyHook(target, (void*)my_system_property_get, (void**)&orig_system_property_get) == 0) {
            LOGD("Spoof installed successfully");
        } else {
            LOGD("Spoof hook failed");
        }
        xdl_close(handle);
    } else{
        xdl_close(handle);
    }

}


__attribute__((constructor)) void init_virtual_spoof()
{
    install_property_get_hook();
    LOGD("VirtualSpoof: __system_property_get hook loaded");
}
