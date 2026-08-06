#include "MediaDrmHook.h"
#include <BoxCore.h>
#include <cstring>
#include "JniHook/JniHook.h"


static bool isDeviceIdentifier(JNIEnv *env, jstring property) {
    if (property == nullptr) {
        return false;
    }
    const char *name = env->GetStringUTFChars(property, JNI_FALSE);
    if (name == nullptr) {
        env->ExceptionClear();
        return false;
    }
    bool match = strcmp(name, "deviceUniqueId") == 0
                 || strcmp(name, "provisioningUniqueId") == 0;
    env->ReleaseStringUTFChars(property, name);
    return match;
}


HOOK_JNI(jbyteArray, getPropertyByteArray, JNIEnv *env, jobject thiz, jstring property) {
    jbyteArray original = orig_getPropertyByteArray(env, thiz, property);

    if (env->ExceptionCheck() || original == nullptr) {
        return original;
    }
    if (!isDeviceIdentifier(env, property)) {
        return original;
    }
    jbyteArray replacement = BoxCore::getWidevineDeviceId(env, original);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return original;
    }
    return replacement == nullptr ? original : replacement;
}


void MediaDrmHook::init(JNIEnv *env) {
    JniHook::HookJniFun(env, "android/media/MediaDrm", "getPropertyByteArray",
                        "(Ljava/lang/String;)[B", (void *) new_getPropertyByteArray,
                        (void **) (&orig_getPropertyByteArray), false);
}
