package top.niunaijun.blackbox.fake.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.content.Context;
import android.os.Build;

import androidx.annotation.RequiresApi;

import java.lang.reflect.Method;
import java.util.List;

import black.android.app.BRNotificationManager;
import black.android.content.pm.BRParceledListSlice;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.fake.frameworks.BNotificationManager;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.utils.MethodParameterUtils;
import top.niunaijun.blackbox.utils.Reflector;
import top.niunaijun.blackbox.utils.Slog;
import top.niunaijun.blackbox.utils.compat.BuildCompat;
import top.niunaijun.blackbox.utils.compat.ParceledListSliceCompat;


public class INotificationManagerProxy extends BinderInvocationStub {
    public static final String TAG = "INotificationManagerProxy";

    public INotificationManagerProxy() {
        super(BRNotificationManager.get().getService().asBinder());
    }

    @Override
    protected Object getWho() {
        return BRNotificationManager.get().getService();
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        BRNotificationManager.get()._set_sService(getProxyInvocation());
        replaceSystemService(Context.NOTIFICATION_SERVICE);
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {

        MethodParameterUtils.replaceAllAppPkg(args);
        return super.invoke(proxy, method, args);
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @ProxyMethod("getNotificationChannel")
    public static class GetNotificationChannel extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            NotificationChannel notificationChannel = BNotificationManager.get().getNotificationChannel((String) args[args.length - 1]);
            return notificationChannel;
        }
    }

    @ProxyMethod("getAppActiveNotifications")
    public static class GetAppActiveNotifications extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) {
            // Active notifications belong to the physical host package and must
            // not leak between virtual spaces. Avoid forwarding guest user 0 to
            // a secondary Android profile, which the system rejects cross-user.
            return ParceledListSliceCompat.create(new java.util.ArrayList<>());
        }
    }

    @ProxyMethod("getNotificationChannels")
    public static class GetNotificationChannels extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            List<NotificationChannel> notificationChannels = BNotificationManager.get().getNotificationChannels(BActivityThread.getAppPackageName());
            return ParceledListSliceCompat.create(notificationChannels);
        }
    }

    @ProxyMethod("cancelNotificationWithTag")
    public static class CancelNotificationWithTag extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String tag = (String) args[getTagIndex()];
            int id = (int) args[getIdIndex()];
            try {
                BNotificationManager.get().cancelNotificationWithTag(id, tag);
            } catch (Throwable error) {
                // Same rule as posting: dismissing a notification must never be
                // able to take the guest process down with it.
                Slog.w(TAG, "Unable to cancel notification " + tag + "/" + id, error);
            }
            return 0;
        }

        public int getTagIndex() {
            if (BuildCompat.isR()) {
                return 2;
            }
            return 1;
        }

        public int getIdIndex() {
            return getTagIndex() + 1;
        }
    }


    @ProxyMethod("enqueueNotificationWithTag")
    public static class EnqueueNotificationWithTag extends MethodHook {

        /**
         * Posts the guest's notification through the host.
         *
         * The host is the process that actually talks to the framework, so any
         * {@code content://} the notification points at has to be readable by
         * the host uid. A guest FileProvider URI is not: posting a photo or reel
         * makes Instagram build an upload notification carrying a
         * {@code content://com.instagram.fileprovider/cache/images/
         * notification_thumbnail….png} preview, and the framework answers with
         *
         * <pre>SecurityException: UID ….. does not have permission to
         * content://com.instagram.fileprovider/…</pre>
         *
         * That used to propagate straight out of this hook. Instagram raises it
         * on a background executor thread with no handler, so the whole guest
         * process died and the launcher came back to the foreground — the app
         * "closed and went home" the moment the user tapped share.
         *
         * A notification is never worth a process. Try it as sent, and if the
         * framework refuses, retry once without the artwork the host cannot
         * reach; if even that fails, drop the notification and let the app carry
         * on uploading.
         */
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String tag = (String) args[getTagIndex()];
            int id = (int) args[getIdIndex()];
            Notification notification = MethodParameterUtils.getFirstParam(args, Notification.class);
            try {
                BNotificationManager.get().enqueueNotificationWithTag(id, tag, notification);
            } catch (Throwable error) {
                Slog.w(TAG, "Notification " + tag + "/" + id
                        + " was rejected, retrying without guest-owned media", error);
                try {
                    stripInaccessibleMedia(notification);
                    BNotificationManager.get().enqueueNotificationWithTag(id, tag, notification);
                } catch (Throwable retryError) {
                    Slog.w(TAG, "Dropping notification " + tag + "/" + id, retryError);
                }
            }
            return 0;
        }

        /**
         * Removes the parts of a notification that can reference a URI only the
         * guest may read. Bitmaps that were already inlined survive; only the
         * indirect references go.
         */
        private void stripInaccessibleMedia(Notification notification) {
            if (notification == null) {
                return;
            }
            notification.sound = null;
            if (notification.extras != null) {
                notification.extras.remove(Notification.EXTRA_LARGE_ICON);
                notification.extras.remove(Notification.EXTRA_LARGE_ICON_BIG);
                notification.extras.remove(Notification.EXTRA_PICTURE);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // There is no public setter, and largeIcon is the field the
                // framework resolves the URI from.
                try {
                    Reflector.with(notification).field("mLargeIcon").set(null);
                } catch (Throwable ignored) {
                }
            }
            try {
                Reflector.with(notification).field("largeIcon").set(null);
            } catch (Throwable ignored) {
            }
        }

        public int getTagIndex() {
            return 2;
        }

        public int getIdIndex() {
            return getTagIndex() + 1;
        }
    }

    @ProxyMethod("createNotificationChannels")
    @RequiresApi(api = Build.VERSION_CODES.O)
    public static class CreateNotificationChannels extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            List<?> list = BRParceledListSlice.get(args[1]).getList();
            if (list == null)
                return 0;
            for (Object o : list) {
                BNotificationManager.get().createNotificationChannel((NotificationChannel) o);
            }
            return 0;
        }
    }

    @ProxyMethod("deleteNotificationChannel")
    public static class DeleteNotificationChannel extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            BNotificationManager.get().deleteNotificationChannel((String) args[1]);
            return 0;
        }
    }

    @ProxyMethod("createNotificationChannelGroups")
    @RequiresApi(api = Build.VERSION_CODES.O)
    public static class CreateNotificationChannelGroups extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            List<?> list = BRParceledListSlice.get(args[1]).getList();
            for (Object o : list) {
                BNotificationManager.get().createNotificationChannelGroup((NotificationChannelGroup) o);
            }
            return 0;
        }
    }

    @ProxyMethod("deleteNotificationChannelGroup")
    public static class DeleteNotificationChannelGroup extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            BNotificationManager.get().deleteNotificationChannelGroup((String) args[1]);
            return 0;
        }
    }

    @ProxyMethod("getNotificationChannelGroups")
    public static class GetNotificationChannelGroups extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            List<NotificationChannelGroup> notificationChannelGroups = BNotificationManager.get().getNotificationChannelGroups(BActivityThread.getAppPackageName());
            return ParceledListSliceCompat.create(notificationChannelGroups);
        }
    }
}
