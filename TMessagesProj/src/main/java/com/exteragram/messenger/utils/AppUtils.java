package com.exteragram.messenger.utils;

import android.content.Context;
import android.graphics.Point;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Keep;

import com.google.gson.ExclusionStrategy;
import com.google.gson.FieldAttributes;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.util.Calendar;

@Keep
public class AppUtils {
    private static Gson gson;

    private AppUtils() {
    }

    @Keep
    public static Gson getGson() {
        if (gson == null) {
            gson = new GsonBuilder()
                    .setPrettyPrinting()
                    .serializeSpecialFloatingPointValues()
                    .addSerializationExclusionStrategy(new ExclusionStrategy() {
                        @Override
                        public boolean shouldSkipField(FieldAttributes fieldAttributes) {
                            Package pkg = fieldAttributes.getDeclaringClass().getPackage();
                            if (pkg == null) {
                                return false;
                            }
                            String name = pkg.getName();
                            return name.startsWith("android.") || name.startsWith("androidx.");
                        }

                        @Override
                        public boolean shouldSkipClass(Class<?> cls) {
                            Package pkg = cls.getPackage();
                            if (pkg == null) {
                                return false;
                            }
                            String name = pkg.getName();
                            return name.startsWith("android.") || name.startsWith("androidx.");
                        }
                    })
                    .create();
        }
        return gson;
    }

    @Keep
    public static void ensureRunningOnUi(Runnable runnable) {
        if (runnable == null) {
            return;
        }
        if (Thread.currentThread() != Looper.getMainLooper().getThread()) {
            AndroidUtilities.runOnUIThread(runnable);
        } else {
            runnable.run();
        }
    }

    @Keep
    public static int getNotificationColor() {
        int accentColor = Theme.getActiveTheme().hasAccentColors()
                ? Theme.getActiveTheme().getAccentColor(Theme.getActiveTheme().currentAccentId)
                : 0;
        if (accentColor == 0) {
            accentColor = Theme.getColor(Theme.key_actionBarDefault) | 0xFF000000;
        }
        float brightness = AndroidUtilities.computePerceivedBrightness(accentColor);
        return (brightness >= 0.721f || brightness <= 0.279f)
                ? (Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader) | 0xFF000000)
                : accentColor;
    }

    @Keep
    public static int[] getDrawerIconPack() {
        int eventType = Theme.getEventType();
        if (eventType == 0) {
            return new int[]{
                    resolveDrawable("msg_groups_ny", R.drawable.msg_groups),
                    resolveDrawable("msg_secret_ny_solar", R.drawable.msg_secret),
                    resolveDrawable("msg_channel_ny_solar", R.drawable.msg_channel),
                    resolveDrawable("msg_contacts_ny", R.drawable.msg_contacts),
                    resolveDrawable("msg_calls_ny", R.drawable.msg_calls),
                    resolveDrawable("msg_saved_ny_solar", R.drawable.msg_saved)
            };
        }
        if (eventType == 1) {
            return new int[]{
                    resolveDrawable("msg_groups_14", R.drawable.msg_groups),
                    resolveDrawable("msg_secret_14", R.drawable.msg_secret),
                    resolveDrawable("msg_channel_14_solar", R.drawable.msg_channel),
                    resolveDrawable("msg_contacts_14", R.drawable.msg_contacts),
                    resolveDrawable("msg_calls_14", R.drawable.msg_calls),
                    resolveDrawable("msg_saved_14_solar", R.drawable.msg_saved)
            };
        }
        if (eventType == 2) {
            return new int[]{
                    resolveDrawable("msg_groups_hw", R.drawable.msg_groups),
                    resolveDrawable("msg_secret_hw", R.drawable.msg_secret),
                    resolveDrawable("msg_channel_hw_solar", R.drawable.msg_channel),
                    resolveDrawable("msg_contacts_hw", R.drawable.msg_contacts),
                    resolveDrawable("msg_calls_hw", R.drawable.msg_calls),
                    resolveDrawable("msg_saved_hw_solar", R.drawable.msg_saved)
            };
        }
        return new int[]{
                R.drawable.msg_groups,
                R.drawable.msg_secret,
                R.drawable.msg_channel,
                R.drawable.msg_contacts,
                R.drawable.msg_calls,
                R.drawable.msg_saved
        };
    }

    @Keep
    public static boolean isWinter() {
        int month = Calendar.getInstance().get(Calendar.MONTH);
        return month == Calendar.DECEMBER || month == Calendar.JANUARY || month == Calendar.FEBRUARY;
    }

    @Keep
    public static int getSwipeVelocity() {
        Point point = AndroidUtilities.displaySize;
        return point.x > point.y ? 1250 : 850;
    }

    @Keep
    public static void log(String message) {
        logInternal(message, null, 5);
    }

    @Keep
    public static void log(Throwable throwable) {
        logInternal("", throwable, 5);
    }

    @Keep
    public static void log(String message, Throwable throwable) {
        logInternal(message, throwable, 5);
    }

    private static void logInternal(String message, Throwable throwable, int stackIndex) {
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        StackTraceElement element = stackTrace[Math.max(3, Math.min(stackIndex, stackTrace.length - 1))];
        String className = element.getClassName();
        if (className.contains(".")) {
            className = className.substring(className.lastIndexOf('.') + 1);
        }
        if (className.contains("$")) {
            className = className.substring(className.lastIndexOf('$') + 1);
        }
        String tag = "[" + className + "]";
        String text = String.format("[%s] %s", element.getMethodName(), message == null ? "" : message);
        if (throwable != null) {
            Log.e(tag, text, throwable);
            FileLog.e(tag + " " + text, throwable);
        } else {
            Log.d(tag, text);
            FileLog.d(tag + " " + text);
        }
    }

    @Keep
    public static void printObjectDetails(Object object) {
        if (object == null) {
            return;
        }
        try {
            logInternal(object.getClass().getName() + ": " + getGson().toJson(object), null, 6);
        } catch (Exception e) {
            logInternal(object.getClass().getName(), e, 6);
        }
    }

    @Keep
    public static Object getPrivateField(Object object, String fieldName) {
        if (object == null || fieldName == null) {
            return null;
        }
        try {
            Field field = findField(object.getClass(), fieldName);
            if (field != null) {
                field.setAccessible(true);
                return field.get(object);
            }
        } catch (Exception e) {
            logInternal(object.getClass().getName(), e, 6);
        }
        return null;
    }

    @Keep
    public static void setPrivateField(Object object, String fieldName, Object value) {
        if (object == null || fieldName == null) {
            return;
        }
        try {
            Field field = findField(object.getClass(), fieldName);
            if (field != null) {
                field.setAccessible(true);
                field.set(object, value);
            }
        } catch (Exception e) {
            logInternal(object.getClass().getName(), e, 6);
        }
    }

    @Keep
    public static Object getPrivateStaticField(Class<?> cls, String fieldName) {
        if (cls == null || fieldName == null) {
            return null;
        }
        try {
            Field field = findField(cls, fieldName);
            if (field != null) {
                field.setAccessible(true);
                return field.get(null);
            }
        } catch (Exception e) {
            logInternal(cls.getName(), e, 6);
        }
        return null;
    }

    @Keep
    public static void setPrivateStaticField(Class<?> cls, String fieldName, Object value) {
        if (cls == null || fieldName == null) {
            return;
        }
        try {
            Field field = findField(cls, fieldName);
            if (field != null) {
                field.setAccessible(true);
                field.set(null, value);
            }
        } catch (Exception e) {
            logInternal(cls.getName(), e, 6);
        }
    }

    @Keep
    public static String stackTraceToString(Throwable throwable) {
        if (throwable == null) {
            return "";
        }
        StringWriter stringWriter = new StringWriter();
        throwable.printStackTrace(new PrintWriter(stringWriter));
        return stringWriter.toString();
    }

    private static Field findField(Class<?> cls, String fieldName) {
        Class<?> current = cls;
        while (current != null) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignore) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private static int resolveDrawable(String name, int fallback) {
        try {
            Context context = ApplicationLoader.applicationContext;
            if (context != null) {
                int id = context.getResources().getIdentifier(name, "drawable", context.getPackageName());
                if (id != 0) {
                    return id;
                }
            }
        } catch (Exception e) {
            logInternal("Failed to resolve drawable " + name, e, 6);
        }
        return fallback;
    }

    @Keep
    public static boolean compareVersions(String operator, String left, String right) {
        int comparison = compareVersionValues(left, right);
        switch (operator) {
            case "<":
                return comparison < 0;
            case ">":
                return comparison > 0;
            case "<=":
                return comparison <= 0;
            case "==":
                return comparison == 0;
            case ">=":
                return comparison >= 0;
            default:
                FileLog.e("Unsupported operator: " + operator);
                return false;
        }
    }

    @Keep
    public static boolean compareVersions(String operator, int left, int right) {
        int comparison = Integer.compare(left, right);
        switch (operator) {
            case "<":
                return comparison < 0;
            case ">":
                return comparison > 0;
            case "<=":
                return comparison <= 0;
            case "==":
                return comparison == 0;
            case ">=":
                return comparison >= 0;
            default:
                FileLog.e("Unsupported operator: " + operator);
                return false;
        }
    }

    @Keep
    public static int compareVersionValues(String left, String right) {
        String[] leftParts = left.split("\\.");
        String[] rightParts = right.split("\\.");
        int max = Math.max(leftParts.length, rightParts.length);
        for (int i = 0; i < max; i++) {
            int leftValue = i < leftParts.length ? Integer.parseInt(leftParts[i]) : 0;
            int rightValue = i < rightParts.length ? Integer.parseInt(rightParts[i]) : 0;
            if (leftValue != rightValue) {
                return Integer.compare(leftValue, rightValue);
            }
        }
        return 0;
    }
}
