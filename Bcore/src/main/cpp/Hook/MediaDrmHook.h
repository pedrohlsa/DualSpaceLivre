#ifndef BLACKBOX_MEDIADRMHOOK_H
#define BLACKBOX_MEDIADRMHOOK_H


#include "BaseHook.h"

class MediaDrmHook : public BaseHook {
public:
    static void init(JNIEnv *env);
};

#endif
