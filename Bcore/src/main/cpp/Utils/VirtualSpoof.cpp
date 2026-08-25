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

SpoofedProp spoofed_props[] = {
        {"ro.product.model", "Pixel 6"},
        {"ro.product.brand", "google"},
        {"ro.product.manufacturer", "Google"},
        {"ro.product.device", "oriole"},
        {"ro.product.name", "oriole"},
        {"ro.product.board", "gs101"},
        {"ro.build.product", "oriole"},
        {"ro.build.id", "SQ1D.220105.007"},
        {"ro.build.display.id", "SQ1D.220105.007"},
        {"ro.build.version.incremental", "8030436"},
        {"ro.build.fingerprint", "google/oriole/oriole:12/SQ1D.220105.007/8030436:user/release-keys"},
        {"ro.build.version.release", "12"},
        {"ro.build.version.security_patch", "2022-01-05"},
        {"ro.hardware", "oriole"},
        {"ro.boot.hardware", "oriole"},
        {"ro.board.platform", "gs101"},
        {"ro.soc.manufacturer", "Google"},
        {"ro.soc.model", "Tensor"},
        {"ro.product.cpu.abi", "arm64-v8a"},
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
