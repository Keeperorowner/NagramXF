package com.exteragram.messenger.plugins.utils;

import androidx.annotation.Keep;

import com.android.dx.Code;
import com.android.dx.Comparison;
import com.android.dx.DexMaker;
import com.android.dx.FieldId;
import com.android.dx.Label;
import com.android.dx.Local;
import com.android.dx.MethodId;
import com.android.dx.TypeId;
import com.chaquo.python.PyObject;

import org.mvel2.MVEL;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;

import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

import dalvik.system.InMemoryDexClassLoader;

public class ClassProxy {
    private static final String GENERATED_PACKAGE = "com.exteragram.messenger.plugins.generated";
    private static final String GENERATED_SOURCE = "ClassProxy.generated";
    private static final String GENERATED_NAME_FALLBACK = "GeneratedProxy";
    private static final String CALLBACK_FIELD_NAME = "__callback__";
    private static final String PYTHON_PEER_FIELD_NAME = "__extera_internal_python_peer__";
    private static final String SUPER_METHOD_PREFIX = "SUPER_";

    private static volatile ClassLoader sharedGeneratedClassLoader;
    private static final Object[] EMPTY_ARGS = new Object[0];
    private static final ConcurrentHashMap<String, Serializable> mvelExpressionCache = new ConcurrentHashMap<>();
    private static final Object generatedClassLoaderLock = new Object();

    public interface DexMakerHook {
        void apply(DexMaker dexMaker, TypeId<?> generatedType, TypeId<?> baseType, List<TypeId<?>> interfaceTypes);
    }

    public static class TypeHelper {
        public static Object box(Object value) { return value; }
        public static Object box(int value) { return value; }
        public static Object box(boolean value) { return value; }
        public static Object box(long value) { return value; }
        public static Object box(double value) { return value; }
        public static Object box(float value) { return value; }
        public static Object box(short value) { return value; }
        public static Object box(byte value) { return value; }
        public static Object box(char value) { return value; }

        public static int unboxInt(Object value) { return value instanceof Number ? ((Number) value).intValue() : 0; }
        public static boolean unboxBool(Object value) { return value instanceof Boolean && (Boolean) value; }
        public static long unboxLong(Object value) { return value instanceof Number ? ((Number) value).longValue() : 0L; }
        public static double unboxDouble(Object value) { return value instanceof Number ? ((Number) value).doubleValue() : 0d; }
        public static float unboxFloat(Object value) { return value instanceof Number ? ((Number) value).floatValue() : 0f; }
        public static short unboxShort(Object value) { return value instanceof Number ? ((Number) value).shortValue() : (short) 0; }
        public static byte unboxByte(Object value) { return value instanceof Number ? ((Number) value).byteValue() : (byte) 0; }
        public static char unboxChar(Object value) { return value instanceof Character ? (Character) value : (char) 0; }
    }

    public static class ProxyMethodSpec {
        public final String name;
        public final Class<?> returnType;
        public final Class<?>[] parameterTypes;
        public final int modifiers;
        public final boolean overrideExisting;
        public final String implementation;
        public final String mvelCode;
        public final List<String> argumentNames;

        public ProxyMethodSpec(String name, Class<?> returnType, Class<?>[] parameterTypes, int modifiers, boolean overrideExisting) {
            this(name, returnType, parameterTypes, modifiers, overrideExisting, "callback", null, null);
        }

        public ProxyMethodSpec(String name, Class<?> returnType, Class<?>[] parameterTypes, int modifiers,
                               boolean overrideExisting, String implementation, String mvelCode,
                               List<String> argumentNames) {
            this.name = name;
            this.returnType = returnType;
            this.parameterTypes = parameterTypes;
            this.modifiers = modifiers;
            this.overrideExisting = overrideExisting;
            this.implementation = implementation;
            this.mvelCode = mvelCode;
            this.argumentNames = argumentNames;
        }

        public boolean isMvel() {
            return "mvel".equals(implementation) && mvelCode != null;
        }
    }

    public static class FieldSpec {
        public final String name;
        public final Class<?> type;
        public final int modifiers;
        public final List<FieldMethodSpec> methods;

        public FieldSpec(String name, Class<?> type, int modifiers) {
            this(name, type, modifiers, null);
        }

        public FieldSpec(String name, Class<?> type, int modifiers, List<FieldMethodSpec> methods) {
            this.name = name;
            this.type = type;
            this.modifiers = modifiers;
            this.methods = methods;
        }
    }

    public static class FieldMethodSpec {
        public final String name;
        public final int modifiers;
        public final boolean getter;

        public FieldMethodSpec(String name, int modifiers, boolean getter) {
            this.name = name;
            this.modifiers = modifiers;
            this.getter = getter;
        }
    }

    @Keep
    public static void runPythonRunnable(PyObject pyObject) {
        if (pyObject == null) {
            FileLog.e("runPythonRunnable called with null callable");
            return;
        }
        try {
            pyObject.call(EMPTY_ARGS);
        } catch (Exception e) {
            FileLog.e("failed to run python runnable", e);
        }
    }

    public static Class<?> createProxyClass(Class<?> baseClass,
                                            Utilities.Callback3Return<Object, String, Object[], Object> callback,
                                            List<Method> methods,
                                            List<Constructor<?>> constructors) throws Exception {
        return createProxyClassInternal(baseClass, callback, null, null, methods, constructors, null, null, null, true);
    }

    public static Class<?> createProxyClass(Class<?> baseClass,
                                            Utilities.Callback3Return<Object, String, Object[], Object> callback,
                                            List<Method> methods,
                                            List<Constructor<?>> constructors,
                                            String nameSuffix) throws Exception {
        return createProxyClassInternal(baseClass, callback, null, null, methods, constructors, null, null, nameSuffix, true);
    }

    public static Class<?> createProxyClass(Class<?> baseClass,
                                            Utilities.Callback3Return<Object, String, Object[], Object> callback,
                                            List<Method> methods,
                                            List<Constructor<?>> constructors,
                                            List<ProxyMethodSpec> methodSpecs,
                                            List<FieldSpec> fieldSpecs) throws Exception {
        return createProxyClassInternal(baseClass, callback, null, null, methods, constructors, methodSpecs, fieldSpecs, null, false);
    }

    public static Class<?> createProxyClass(Class<?> baseClass,
                                            Utilities.Callback3Return<Object, String, Object[], Object> callback,
                                            List<Method> methods,
                                            List<Constructor<?>> constructors,
                                            List<ProxyMethodSpec> methodSpecs,
                                            List<FieldSpec> fieldSpecs,
                                            String nameSuffix) throws Exception {
        return createProxyClassInternal(baseClass, callback, null, null, methods, constructors, methodSpecs, fieldSpecs, nameSuffix, false);
    }

    public static Class<?> createProxyClass(Class<?> baseClass,
                                            Utilities.Callback3Return<Object, String, Object[], Object> callback,
                                            DexMakerHook dexMakerHook,
                                            List<Class<?>> interfaces,
                                            List<Method> methods,
                                            List<Constructor<?>> constructors,
                                            List<ProxyMethodSpec> methodSpecs,
                                            List<FieldSpec> fieldSpecs) throws Exception {
        return createProxyClassInternal(baseClass, callback, dexMakerHook, interfaces, methods, constructors, methodSpecs, fieldSpecs, null, false);
    }

    public static Class<?> createProxyClass(Class<?> baseClass,
                                            Utilities.Callback3Return<Object, String, Object[], Object> callback,
                                            DexMakerHook dexMakerHook,
                                            List<Class<?>> interfaces,
                                            List<Method> methods,
                                            List<Constructor<?>> constructors,
                                            List<ProxyMethodSpec> methodSpecs,
                                            List<FieldSpec> fieldSpecs,
                                            String nameSuffix) throws Exception {
        return createProxyClassInternal(baseClass, callback, dexMakerHook, interfaces, methods, constructors, methodSpecs, fieldSpecs, nameSuffix, false);
    }

    private static Class<?> createProxyClassInternal(Class<?> baseClass,
                                                     Utilities.Callback3Return<Object, String, Object[], Object> callback,
                                                     DexMakerHook dexMakerHook,
                                                     List<Class<?>> interfaces,
                                                     List<Method> methods,
                                                     List<Constructor<?>> constructors,
                                                     List<ProxyMethodSpec> methodSpecs,
                                                     List<FieldSpec> fieldSpecs,
                                                     String nameSuffix,
                                                     boolean overrideAllByDefault) throws Exception {
        DexMaker dexMaker = new DexMaker();
        String simpleName = baseClass.getSimpleName();
        if (simpleName == null || simpleName.isEmpty()) {
            simpleName = GENERATED_NAME_FALLBACK;
        }
        String sanitizedSuffix = sanitizeProxyClassSegment(nameSuffix);
        String generatedClassName = GENERATED_PACKAGE + "." + simpleName
                + (sanitizedSuffix != null ? "$" + sanitizedSuffix : "") + "$Proxy$"
                + Math.abs(baseClass.getName().hashCode()) + "$" + Math.abs(new Random().nextInt());
        TypeId<?> generatedType = TypeId.get("L" + generatedClassName.replace('.', '/') + ";");
        TypeId<?> baseType = TypeId.get(baseClass);
        FieldId<?, ?> callbackField = generatedType.getField(TypeId.get(Utilities.Callback3Return.class), CALLBACK_FIELD_NAME);
        TypeId<?>[] interfaceTypeIds = toInterfaceTypeIds(interfaces);
        ArrayList<TypeId<?>> declaredInterfaces = new ArrayList<>(Arrays.asList(interfaceTypeIds));

        dexMaker.declare(generatedType, GENERATED_SOURCE, Modifier.PUBLIC, baseType, interfaceTypeIds);
        dexMaker.declare(callbackField, Modifier.PUBLIC | Modifier.STATIC, null);

        if (fieldSpecs != null) {
            for (FieldSpec fieldSpec : fieldSpecs) {
                if (fieldSpec == null || fieldSpec.name == null || fieldSpec.type == null) {
                    continue;
                }
                FieldId<?, ?> fieldId = generatedType.getField(TypeId.get(fieldSpec.type), fieldSpec.name);
                Object staticDefault = Modifier.isStatic(fieldSpec.modifiers) ? defaultValueForType(fieldSpec.type) : null;
                dexMaker.declare(fieldId, fieldSpec.modifiers, staticDefault);
                if (fieldSpec.methods != null) {
                    for (FieldMethodSpec fieldMethodSpec : fieldSpec.methods) {
                        if (fieldMethodSpec != null && fieldMethodSpec.name != null) {
                            generateFieldAccessorMethod(dexMaker, generatedType, fieldSpec, fieldMethodSpec);
                        }
                    }
                }
            }
        }

        Constructor<?>[] constructorsToUse = constructors != null
                ? constructors.toArray(new Constructor<?>[0])
                : baseClass.getDeclaredConstructors();
        for (Constructor<?> constructor : constructorsToUse) {
            if (!Modifier.isPrivate(constructor.getModifiers())) {
                generateConstructor(dexMaker, generatedType, baseType, constructor, callbackField);
            }
        }

        Map<String, Method> allOverridableMethods = getAllOverridableMethods(baseClass, interfaces);
        LinkedHashMap<String, Method> methodsToOverride = new LinkedHashMap<>();
        if (overrideAllByDefault && methods == null && (methodSpecs == null || methodSpecs.isEmpty())) {
            methodsToOverride.putAll(allOverridableMethods);
        }
        if (methods != null) {
            for (Method method : methods) {
                if (method != null) {
                    methodsToOverride.put(buildMethodSignature(method.getName(), method.getParameterTypes()), method);
                }
            }
        }

        ArrayList<ProxyMethodSpec> customMethods = new ArrayList<>();
        LinkedHashMap<String, ProxyMethodSpec> mvelOverrides = new LinkedHashMap<>();
        if (methodSpecs != null) {
            for (ProxyMethodSpec methodSpec : methodSpecs) {
                if (methodSpec == null || methodSpec.name == null) {
                    continue;
                }
                if (methodSpec.overrideExisting) {
                    if (methodSpec.parameterTypes == null) {
                        for (Method method : allOverridableMethods.values()) {
                            if (methodSpec.name.equals(method.getName())) {
                                String signature = buildMethodSignature(method.getName(), method.getParameterTypes());
                                methodsToOverride.put(signature, method);
                                if (methodSpec.isMvel()) {
                                    mvelOverrides.put(signature, methodSpec);
                                }
                            }
                        }
                    } else {
                        String signature = buildMethodSignature(methodSpec.name, methodSpec.parameterTypes);
                        Method method = allOverridableMethods.get(signature);
                        if (method == null) {
                            throw new NoSuchMethodException("No overridable method found: " + signature);
                        }
                        methodsToOverride.put(signature, method);
                        if (methodSpec.isMvel()) {
                            mvelOverrides.put(signature, methodSpec);
                        }
                    }
                } else {
                    customMethods.add(methodSpec);
                }
            }
        }

        for (Map.Entry<String, Method> entry : methodsToOverride.entrySet()) {
            Method method = entry.getValue();
            generateProxyMethod(dexMaker, generatedType, method, callbackField, true);
            ProxyMethodSpec override = mvelOverrides.get(entry.getKey());
            if (override != null && override.isMvel()) {
                generateMvelMethod(dexMaker, generatedType, method.getName(), method.getReturnType(), method.getParameterTypes(),
                        Modifier.PUBLIC, override.mvelCode, override.argumentNames);
            } else {
                generateProxyMethod(dexMaker, generatedType, method, callbackField, false);
            }
        }

        for (ProxyMethodSpec methodSpec : customMethods) {
            Class<?> returnType = methodSpec.returnType != null ? methodSpec.returnType : Void.TYPE;
            Class<?>[] parameterTypes = methodSpec.parameterTypes != null ? methodSpec.parameterTypes : new Class[0];
            int modifiers = methodSpec.modifiers != 0 ? methodSpec.modifiers : Modifier.PUBLIC;
            if (methodSpec.isMvel()) {
                generateMvelMethod(dexMaker, generatedType, methodSpec.name, returnType, parameterTypes,
                        modifiers, methodSpec.mvelCode, methodSpec.argumentNames);
            } else {
                generateHandlerMethod(dexMaker, generatedType, methodSpec.name, returnType, parameterTypes,
                        modifiers, callbackField);
            }
        }

        if (dexMakerHook != null) {
            dexMakerHook.apply(dexMaker, generatedType, baseType, declaredInterfaces);
        }

        byte[] dexBytes = dexMaker.generate();
        Class<?> generatedClass;
        synchronized (generatedClassLoaderLock) {
            ClassLoader parent = sharedGeneratedClassLoader != null
                    ? sharedGeneratedClassLoader
                    : ApplicationLoader.applicationContext.getClassLoader();
            InMemoryDexClassLoader classLoader = new InMemoryDexClassLoader(ByteBuffer.wrap(dexBytes), parent);
            generatedClass = classLoader.loadClass(generatedClassName);
            sharedGeneratedClassLoader = classLoader;
        }
        Field declaredField = generatedClass.getDeclaredField(CALLBACK_FIELD_NAME);
        declaredField.setAccessible(true);
        declaredField.set(null, callback);
        return generatedClass;
    }

    private static String sanitizeProxyClassSegment(String segment) {
        if (segment == null) {
            return null;
        }
        String trimmed = segment.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        StringBuilder builder = new StringBuilder(trimmed.length());
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            builder.append(Character.isLetterOrDigit(c) || c == '_' ? c : '_');
        }
        return builder.toString();
    }

    private static TypeId<?>[] toInterfaceTypeIds(List<Class<?>> interfaces) {
        if (interfaces == null || interfaces.isEmpty()) {
            return new TypeId[0];
        }
        ArrayList<TypeId<?>> result = new ArrayList<>();
        for (Class<?> iface : interfaces) {
            if (iface == null) {
                continue;
            }
            if (!iface.isInterface()) {
                throw new IllegalArgumentException("Expected interface but got " + iface.getName());
            }
            result.add(TypeId.get(iface));
        }
        return result.toArray(new TypeId[0]);
    }

    private static Map<String, Method> getAllOverridableMethods(Class<?> baseClass, List<Class<?>> interfaces) {
        LinkedHashMap<String, Method> result = new LinkedHashMap<>();
        collectOverridableMethods(baseClass, result);
        if (interfaces != null) {
            for (Class<?> iface : interfaces) {
                collectOverridableMethods(iface, result);
            }
        }
        return result;
    }

    private static void collectOverridableMethods(Class<?> type, Map<String, Method> result) {
        if (type == null || type == Object.class) {
            return;
        }
        for (Method method : type.getDeclaredMethods()) {
            int modifiers = method.getModifiers();
            if (!Modifier.isStatic(modifiers) && !Modifier.isFinal(modifiers)
                    && (Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers))) {
                String signature = buildMethodSignature(method.getName(), method.getParameterTypes());
                result.putIfAbsent(signature, method);
            }
        }
        for (Class<?> iface : type.getInterfaces()) {
            collectOverridableMethods(iface, result);
        }
        collectOverridableMethods(type.getSuperclass(), result);
    }

    private static String buildMethodSignature(String name, Class<?>[] parameterTypes) {
        StringBuilder builder = new StringBuilder(name);
        if (parameterTypes != null) {
            for (Class<?> parameterType : parameterTypes) {
                builder.append(':').append(parameterType.getName());
            }
        }
        return builder.toString();
    }

    private static Object defaultValueForType(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == Boolean.TYPE) return false;
        if (type == Long.TYPE) return 0L;
        if (type == Double.TYPE) return 0d;
        if (type == Float.TYPE) return 0f;
        if (type == Short.TYPE) return (short) 0;
        if (type == Byte.TYPE) return (byte) 0;
        if (type == Character.TYPE) return (char) 0;
        return 0;
    }
    private static void generateConstructor(DexMaker dexMaker, TypeId<?> generatedType, TypeId<?> baseType,
                                            Constructor<?> constructor, FieldId<?, ?> callbackField) {
        Class<?>[] parameterTypes = constructor.getParameterTypes();
        TypeId<?>[] parameterTypeIds = new TypeId[parameterTypes.length];
        for (int i = 0; i < parameterTypes.length; i++) {
            parameterTypeIds[i] = TypeId.get(parameterTypes[i]);
        }

        Code code = dexMaker.declare(generatedType.getConstructor(parameterTypeIds), Modifier.PUBLIC);
        Local<?> thisLocal = code.getThis(generatedType);
        Local<?> callbackLocal = code.newLocal(TypeId.get(Utilities.Callback3Return.class));
        Local<Object> callbackResult = code.newLocal(TypeId.OBJECT);
        Local<String> signatureLocal = code.newLocal(TypeId.STRING);
        Local<Object[]> boxedArgs = code.newLocal(TypeId.get(Object[].class));
        Local<Object[]> replacementArgs = code.newLocal(TypeId.get(Object[].class));
        Local<Object> nullObject = code.newLocal(TypeId.OBJECT);
        Local<Integer> lengthLocal = code.newLocal(TypeId.INT);
        Local<Integer> indexLocal = code.newLocal(TypeId.INT);
        Local<Object> boxedValue = code.newLocal(TypeId.OBJECT);
        Local<?>[] typedArgs = new Local[parameterTypes.length];

        for (int i = 0; i < parameterTypes.length; i++) {
            typedArgs[i] = code.newLocal(parameterTypeIds[i]);
            code.move((Local) typedArgs[i], (Local) code.getParameter(i, parameterTypeIds[i]));
        }

        code.sget((FieldId) callbackField, (Local) callbackLocal);
        code.loadConstant(signatureLocal, buildMethodSignature("<constructor>", parameterTypes));
        code.loadConstant(nullObject, null);
        code.loadConstant(lengthLocal, parameterTypes.length);
        code.newArray(boxedArgs, lengthLocal);

        for (int i = 0; i < parameterTypes.length; i++) {
            MethodId<?, Object> boxMethod = TypeId.get(TypeHelper.class)
                    .getMethod(TypeId.OBJECT, "box", parameterTypes[i].isPrimitive() ? parameterTypeIds[i] : TypeId.OBJECT);
            code.invokeStatic(boxMethod, boxedValue, typedArgs[i]);
            code.loadConstant(indexLocal, i);
            code.aput(boxedArgs, indexLocal, boxedValue);
        }

        MethodId<?, Object> callbackMethod = TypeId.get(Utilities.Callback3Return.class)
                .getMethod(TypeId.OBJECT, "run", TypeId.OBJECT, TypeId.OBJECT, TypeId.OBJECT);
        code.invokeInterface((MethodId) callbackMethod, callbackResult, (Local) callbackLocal, nullObject, signatureLocal, boxedArgs);

        Label useOriginalArgs = new Label();
        code.compare(Comparison.EQ, useOriginalArgs, callbackResult, nullObject);
        code.cast(replacementArgs, callbackResult);
        for (int i = 0; i < parameterTypes.length; i++) {
            code.loadConstant(indexLocal, i);
            code.aget(boxedValue, replacementArgs, indexLocal);
            if (parameterTypes[i].isPrimitive()) {
                MethodId<?, ?> unboxMethod = TypeId.get(TypeHelper.class)
                        .getMethod(parameterTypeIds[i], "unbox" + capitalize(parameterTypes[i].getName()), TypeId.OBJECT);
                code.invokeStatic((MethodId) unboxMethod, (Local) typedArgs[i], boxedValue);
            } else {
                code.cast(typedArgs[i], boxedValue);
            }
        }
        code.mark(useOriginalArgs);
        code.invokeDirect((MethodId) baseType.getConstructor(parameterTypeIds), null, (Local) thisLocal, (Local[]) typedArgs);
        code.invokeInterface((MethodId) callbackMethod, null, (Local) callbackLocal, (Local) thisLocal, signatureLocal, boxedArgs);
        code.returnVoid();
    }

    private static void generateProxyMethod(DexMaker dexMaker, TypeId<?> generatedType, Method method,
                                            FieldId<?, ?> callbackField, boolean superBridge) {
        Class<?>[] parameterTypes = method.getParameterTypes();
        TypeId<?>[] parameterTypeIds = new TypeId[parameterTypes.length];
        for (int i = 0; i < parameterTypes.length; i++) {
            parameterTypeIds[i] = TypeId.get(parameterTypes[i]);
        }
        TypeId<?> returnTypeId = TypeId.get(method.getReturnType());
        String name = superBridge ? SUPER_METHOD_PREFIX + method.getName() : method.getName();
        if (superBridge) {
            Code code = dexMaker.declare(generatedType.getMethod(returnTypeId, name, parameterTypeIds), Modifier.PUBLIC);
            Local<?> thisLocal = code.getThis(generatedType);
            Local<?> resultLocal = returnTypeId.equals(TypeId.VOID) ? null : code.newLocal(returnTypeId);
            Local<?>[] params = new Local[parameterTypes.length];
            for (int i = 0; i < parameterTypes.length; i++) {
                params[i] = code.getParameter(i, parameterTypeIds[i]);
            }
            code.invokeSuper((MethodId) TypeId.get(method.getDeclaringClass()).getMethod(returnTypeId, method.getName(), parameterTypeIds),
                    (Local) resultLocal, (Local) thisLocal, (Local[]) params);
            if (returnTypeId.equals(TypeId.VOID)) {
                code.returnVoid();
            } else {
                code.returnValue(resultLocal);
            }
        } else {
            generateHandlerMethod(dexMaker, generatedType, name, method.getReturnType(), parameterTypes, Modifier.PUBLIC, callbackField);
        }
    }

    private static void generateHandlerMethod(DexMaker dexMaker, TypeId<?> generatedType, String methodName,
                                              Class<?> returnType, Class<?>[] parameterTypes, int modifiers,
                                              FieldId<?, ?> callbackField) {
        TypeId<?>[] parameterTypeIds = new TypeId[parameterTypes.length];
        for (int i = 0; i < parameterTypes.length; i++) {
            parameterTypeIds[i] = TypeId.get(parameterTypes[i]);
        }
        TypeId<?> returnTypeId = TypeId.get(returnType);
        Code code = dexMaker.declare(generatedType.getMethod(returnTypeId, methodName, parameterTypeIds), modifiers);
        Local<?> callbackLocal = code.newLocal(TypeId.get(Utilities.Callback3Return.class));
        Local<String> signatureLocal = code.newLocal(TypeId.STRING);
        Local<Object[]> boxedArgs = code.newLocal(TypeId.get(Object[].class));
        Local<Integer> lengthLocal = code.newLocal(TypeId.INT);
        Local<Integer> indexLocal = code.newLocal(TypeId.INT);
        Local<Object> boxedValue = code.newLocal(TypeId.OBJECT);
        Local<Object> resultObject = code.newLocal(TypeId.OBJECT);
        Local<Object> selfObject = code.newLocal(TypeId.OBJECT);
        Local<?> resultValue = returnTypeId.equals(TypeId.VOID) ? null : code.newLocal(returnTypeId);

        if (Modifier.isStatic(modifiers)) {
            code.loadConstant(selfObject, null);
        } else {
            Local<?> thisLocal = code.getThis(generatedType);
            code.cast(selfObject, thisLocal);
        }

        code.sget((FieldId) callbackField, (Local) callbackLocal);
        code.loadConstant(signatureLocal, buildMethodSignature(methodName, parameterTypes));
        code.loadConstant(lengthLocal, parameterTypes.length);
        code.newArray(boxedArgs, lengthLocal);

        for (int i = 0; i < parameterTypes.length; i++) {
            Local<?> parameter = code.getParameter(i, parameterTypeIds[i]);
            MethodId<?, Object> boxMethod = TypeId.get(TypeHelper.class)
                    .getMethod(TypeId.OBJECT, "box", parameterTypes[i].isPrimitive() ? parameterTypeIds[i] : TypeId.OBJECT);
            code.invokeStatic(boxMethod, boxedValue, parameter);
            code.loadConstant(indexLocal, i);
            code.aput(boxedArgs, indexLocal, boxedValue);
        }

        MethodId<?, Object> callbackMethod = TypeId.get(Utilities.Callback3Return.class)
                .getMethod(TypeId.OBJECT, "run", TypeId.OBJECT, TypeId.OBJECT, TypeId.OBJECT);
        code.invokeInterface((MethodId) callbackMethod, resultObject, (Local) callbackLocal, selfObject, signatureLocal, boxedArgs);

        if (returnTypeId.equals(TypeId.VOID)) {
            code.returnVoid();
            return;
        }
        if (returnType.isPrimitive()) {
            MethodId<?, ?> unboxMethod = TypeId.get(TypeHelper.class)
                    .getMethod(returnTypeId, "unbox" + capitalize(returnType.getName()), TypeId.OBJECT);
            code.invokeStatic((MethodId) unboxMethod, (Local) resultValue, resultObject);
        } else {
            code.cast(resultValue, resultObject);
        }
        code.returnValue(resultValue);
    }
    private static void generateMvelMethod(DexMaker dexMaker, TypeId<?> generatedType, String methodName,
                                           Class<?> returnType, Class<?>[] parameterTypes, int modifiers,
                                           String expression, List<String> argumentNames) {
        TypeId<?>[] parameterTypeIds = new TypeId[parameterTypes.length];
        for (int i = 0; i < parameterTypes.length; i++) {
            parameterTypeIds[i] = TypeId.get(parameterTypes[i]);
        }
        TypeId<?> returnTypeId = TypeId.get(returnType);
        Code code = dexMaker.declare(generatedType.getMethod(returnTypeId, methodName, parameterTypeIds), modifiers);

        Local<Object> javaInstance = code.newLocal(TypeId.OBJECT);
        Local<Object> pythonPeer = code.newLocal(TypeId.OBJECT);
        Local<Object> mvelResult = code.newLocal(TypeId.OBJECT);
        Local<String> expressionLocal = code.newLocal(TypeId.STRING);
        Local<Object[]> boxedArgs = code.newLocal(TypeId.get(Object[].class));
        Local<String[]> argumentNamesLocal = code.newLocal(TypeId.get(String[].class));
        Local<Integer> lengthLocal = code.newLocal(TypeId.INT);
        Local<Integer> indexLocal = code.newLocal(TypeId.INT);
        Local<Object> boxedValue = code.newLocal(TypeId.OBJECT);
        Local<String> stringValue = code.newLocal(TypeId.STRING);
        Local<?> resultValue = returnTypeId.equals(TypeId.VOID) ? null : code.newLocal(returnTypeId);

        code.loadConstant(javaInstance, null);
        code.loadConstant(pythonPeer, null);
        if (!Modifier.isStatic(modifiers)) {
            Local<?> thisLocal = code.getThis(generatedType);
            code.cast(javaInstance, thisLocal);
            FieldId<?, ?> peerField = generatedType.getField(TypeId.OBJECT, PYTHON_PEER_FIELD_NAME);
            code.iget((FieldId) peerField, pythonPeer, (Local) thisLocal);
        }

        code.loadConstant(expressionLocal, expression);
        code.loadConstant(lengthLocal, parameterTypes.length);
        code.newArray(boxedArgs, lengthLocal);
        for (int i = 0; i < parameterTypes.length; i++) {
            Local<?> parameter = code.getParameter(i, parameterTypeIds[i]);
            MethodId<?, Object> boxMethod = TypeId.get(TypeHelper.class)
                    .getMethod(TypeId.OBJECT, "box", parameterTypes[i].isPrimitive() ? parameterTypeIds[i] : TypeId.OBJECT);
            code.invokeStatic(boxMethod, boxedValue, parameter);
            code.loadConstant(indexLocal, i);
            code.aput(boxedArgs, indexLocal, boxedValue);
        }

        if (argumentNames != null && !argumentNames.isEmpty()) {
            code.loadConstant(lengthLocal, argumentNames.size());
            code.newArray(argumentNamesLocal, lengthLocal);
            for (int i = 0; i < argumentNames.size(); i++) {
                code.loadConstant(stringValue, argumentNames.get(i));
                code.loadConstant(indexLocal, i);
                code.aput(argumentNamesLocal, indexLocal, stringValue);
            }
        } else {
            code.loadConstant(argumentNamesLocal, null);
        }

        MethodId<?, Object> execMethod = TypeId.get(ClassProxy.class)
                .getMethod(TypeId.OBJECT, "executeMvelMethod", TypeId.OBJECT, TypeId.OBJECT, TypeId.STRING,
                        TypeId.get(String[].class), TypeId.get(Object[].class));
        code.invokeStatic(execMethod, mvelResult, javaInstance, pythonPeer, expressionLocal, argumentNamesLocal, boxedArgs);

        if (returnTypeId.equals(TypeId.VOID)) {
            code.returnVoid();
            return;
        }
        if (returnType.isPrimitive()) {
            MethodId<?, ?> unboxMethod = TypeId.get(TypeHelper.class)
                    .getMethod(returnTypeId, "unbox" + capitalize(returnType.getName()), TypeId.OBJECT);
            code.invokeStatic((MethodId) unboxMethod, (Local) resultValue, mvelResult);
        } else {
            code.cast(resultValue, mvelResult);
        }
        code.returnValue(resultValue);
    }

    private static void generateFieldAccessorMethod(DexMaker dexMaker, TypeId<?> generatedType,
                                                    FieldSpec fieldSpec, FieldMethodSpec fieldMethodSpec) {
        TypeId<?> fieldType = TypeId.get(fieldSpec.type);
        FieldId<?, ?> fieldId = generatedType.getField(fieldType, fieldSpec.name);
        boolean isStaticField = Modifier.isStatic(fieldSpec.modifiers);
        int modifiers = fieldMethodSpec.modifiers != 0 ? fieldMethodSpec.modifiers : Modifier.PUBLIC;
        if (fieldMethodSpec.getter) {
            Code code = dexMaker.declare(generatedType.getMethod(fieldType, fieldMethodSpec.name), modifiers);
            Local<?> valueLocal = code.newLocal(fieldType);
            if (isStaticField) {
                code.sget((FieldId) fieldId, (Local) valueLocal);
            } else {
                code.iget((FieldId) fieldId, (Local) valueLocal, (Local) code.getThis(generatedType));
            }
            code.returnValue(valueLocal);
        } else {
            Code code = dexMaker.declare(generatedType.getMethod(TypeId.VOID, fieldMethodSpec.name, fieldType), modifiers);
            Local<?> parameter = code.getParameter(0, fieldType);
            if (isStaticField) {
                code.sput((FieldId) fieldId, (Local) parameter);
            } else {
                code.iput((FieldId) fieldId, (Local) code.getThis(generatedType), (Local) parameter);
            }
            code.returnVoid();
        }
    }

    public static Object executeMvelMethod(Object javaInstance, Object pythonPeer, String expression,
                                           String[] argumentNames, Object[] args) {
        HashMap<String, Object> vars = new HashMap<>();
        Object[] safeArgs = args != null ? args : new Object[0];
        vars.put("java_instance", javaInstance);
        vars.put("java_this", javaInstance);
        vars.put("python_peer", pythonPeer);
        vars.put("peer", pythonPeer);
        vars.put("self", pythonPeer);
        vars.put("this", pythonPeer);
        vars.put("args", safeArgs);
        vars.put("arguments", safeArgs);
        vars.put("argc", safeArgs.length);
        vars.put("arg_count", safeArgs.length);
        for (int i = 0; i < safeArgs.length; i++) {
            Object arg = safeArgs[i];
            vars.put("arg" + i, arg);
            if (argumentNames != null && i < argumentNames.length) {
                String name = argumentNames[i];
                if (name != null && !name.isEmpty()) {
                    vars.put(name, arg);
                }
            }
        }
        Serializable compiled = mvelExpressionCache.computeIfAbsent(expression, MVEL::compileExpression);
        return MVEL.executeExpression(compiled, javaInstance, vars);
    }

    private static String capitalize(String value) {
        switch (value) {
            case "int": return "Int";
            case "boolean": return "Bool";
            case "long": return "Long";
            case "double": return "Double";
            case "float": return "Float";
            case "short": return "Short";
            case "byte": return "Byte";
            case "char": return "Char";
            default: return value.substring(0, 1).toUpperCase() + value.substring(1);
        }
    }
}
