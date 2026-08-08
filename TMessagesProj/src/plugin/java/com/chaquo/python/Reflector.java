package com.chaquo.python;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/* JADX INFO: loaded from: Reflector.class */
public class Reflector {
    private static final DeniedMethod[] DENIED_METHODS = {new DeniedMethod("android.widget.TextView", "setTextColor", Void.TYPE, new Class[]{Long.TYPE})};
    private static Map<Class<?>, Reflector> instances = new HashMap();
    private Map<String, Class<?>> classes;
    private Map<String, Class<?>> classesAll;
    private Map<String, Field> fields;
    private Map<String, Field> fieldsAll;
    private final Class<?> klass;
    private Map<String, Member> methods;
    private Map<String, Member> methodsAll;
    private Map<String, List<Member>> multipleMethods;
    private Map<String, List<Member>> multipleMethodsAll;
    private Map<String, List<Member>> multiplePropertyGetters;
    private Map<String, List<Member>> multiplePropertyGettersAll;
    private Map<String, List<Member>> multiplePropertySetters;
    private Map<String, List<Member>> multiplePropertySettersAll;
    private Map<String, List<Member>> multipleStaticPropertyGetters;
    private Map<String, List<Member>> multipleStaticPropertyGettersAll;
    private Map<String, List<Member>> multipleStaticPropertySetters;
    private Map<String, List<Member>> multipleStaticPropertySettersAll;
    private Map<String, Member> propertyGetters;
    private Map<String, Member> propertyGettersAll;
    private Map<String, Member> propertySetters;
    private Map<String, Member> propertySettersAll;
    private Map<String, Member> staticPropertyGetters;
    private Map<String, Member> staticPropertyGettersAll;
    private Map<String, Member> staticPropertySetters;
    private Map<String, Member> staticPropertySettersAll;

    private Reflector(Class<?> cls) {
        this.klass = cls;
    }

    private void addPropertyAlias(List<String> list, String str) {
        if (str == null || list.contains(str)) {
            return;
        }
        list.add(str);
    }

    private String booleanPropertyAliasName(String str) {
        if (str.isEmpty()) {
            return null;
        }
        return (str.startsWith("Is") && str.length() > 2 && Character.isUpperCase(str.charAt(2))) ? decapitalizePropertyName(str) : "is".concat(str);
    }

    private String decapitalizePropertyName(String str) {
        if (str.isEmpty()) {
            return null;
        }
        return Character.toLowerCase(str.charAt(0)) + str.substring(1);
    }

    private java.util.Collection<java.lang.reflect.Field> getDeclaredFields() {
        try {
            return Arrays.asList(klass.getDeclaredFields());
        } catch (NoClassDefFoundError ignored) {}

        // On Android, getDeclaredFields fails if any field type refers to a class that cannot
        // be loaded, so fall back to discovering the public fields instead.
        Set<Field> result = new HashSet<>();
        try {
            Field[] fields = klass.getFields();
            for (int i = 0; i < fields.length; i++) {
                Field f = fields[i];
                if (f.getDeclaringClass() == klass) {
                    try {
                        f.getType();
                        result.add(f);
                    } catch (NoClassDefFoundError ignored2) {}
                }
            }
        } catch (NoClassDefFoundError ignored2) {}
        return result;
    }

    private java.util.Collection<java.lang.reflect.Method> getDeclaredMethods() {
        try {
            return Arrays.asList(klass.getDeclaredMethods());
        } catch (NoClassDefFoundError ignored) {}

        // On Android this fails if any method signature references a class that cannot be
        // loaded; fall back to public methods plus inherited overrides.
        Set<Method> result = new HashSet<>();

        try {
            Method[] methods = klass.getMethods();
            for (int i = 0; i < methods.length; i++) {
                Method m = methods[i];
                if (m.getDeclaringClass() == klass) {
                    try {
                        m.getReturnType();
                        m.getParameterTypes();
                        result.add(m);
                    } catch (NoClassDefFoundError ignored2) {}
                }
            }
        } catch (NoClassDefFoundError ignored2) {}

        for (Class<?> c = klass.getSuperclass(); c != null; c = c.getSuperclass()) {
            for (Method inherited : Reflector.getInstance(c).getDeclaredMethods()) {
                try {
                    result.add(klass.getDeclaredMethod(inherited.getName(),
                                                       inherited.getParameterTypes()));
                } catch (NoSuchMethodException ignored2) {}
            }
        }

        return result;
    }

    public static Reflector getInstance(Class<?> cls) {
        Reflector reflector = instances.get(cls);
        if (reflector != null) {
            return reflector;
        }
        Reflector reflector2 = new Reflector(cls);
        instances.put(cls, reflector2);
        return reflector2;
    }

    private Member[] getPropertyMethods(String str, boolean z, int i, Map<String, Member> map, Map<String, List<Member>> map2, Map<String, Member> map3, Map<String, List<Member>> map4, Map<String, Member> map5, Map<String, List<Member>> map6, Map<String, Member> map7, Map<String, List<Member>> map8) {
        if (z) {
            if (i == 1) {
                map5 = map7;
            }
            map = map5;
            if (i == 1) {
                map6 = map8;
                map = map5;
            }
        } else {
            if (i == 1) {
                map = map3;
            }
            if (i == 1) {
                map2 = map4;
            }
            map6 = map2;
        }
        List<Member> list = map6.get(str);
        if (list != null) {
            return (Member[]) list.toArray(new Member[0]);
        }
        Member member = map.get(str);
        if (member != null) {
            return new Member[]{member};
        }
        return null;
    }

    private boolean includeMember(Member member, boolean z) {
        return z ? !member.isSynthetic() : isAccessible(member);
    }

    private boolean isAccessible(int i) {
        return (i & 5) != 0;
    }

    private boolean isAccessible(Member member) {
        return isAccessible(member.getModifiers()) && !member.isSynthetic();
    }

    private boolean isBooleanType(Class<?> cls) {
        return cls == Boolean.TYPE || cls == Boolean.class;
    }

    private boolean isDeniedMethod(Method method) {
        for (DeniedMethod deniedMethod : DENIED_METHODS) {
            if (deniedMethod.matches(method)) {
                return true;
            }
        }
        return false;
    }

    private void loadClasses(boolean z) {
        HashMap map = new HashMap();
        for (Class<?> cls : this.klass.getDeclaredClasses()) {
            if (z || isAccessible(cls.getModifiers())) {
                String simpleName = cls.getSimpleName();
                if (!simpleName.isEmpty()) {
                    map.put(simpleName, cls);
                }
            }
        }
        if (z) {
            this.classesAll = map;
        } else {
            this.classes = map;
        }
    }

    private void loadFields(boolean z) {
        HashMap map = new HashMap();
        for (Field field : getDeclaredFields()) {
            if (z || isAccessible(field)) {
                prepareAccessible(field, z);
                map.put(field.getName(), field);
            }
        }
        if (z) {
            this.fieldsAll = map;
        } else {
            this.fields = map;
        }
    }

    private void loadMethod(Map<String, Member> map, Map<String, List<Member>> map2, Member member, String str) {
        Member memberRemove = map.remove(str);
        if (memberRemove != null) {
            ArrayList arrayList = new ArrayList();
            arrayList.add(memberRemove);
            arrayList.add(member);
            map2.put(str, arrayList);
            return;
        }
        List<Member> list = map2.get(str);
        if (list != null) {
            list.add(member);
        } else {
            map.put(str, member);
        }
    }

    private void loadMethods(boolean z) {
        Map<String, Member> map = new HashMap<>();
        Map<String, List<Member>> map2 = new HashMap<>();
        Map<String, Member> map3 = new HashMap<>();
        Map<String, List<Member>> map4 = new HashMap<>();
        Map<String, Member> map5 = new HashMap<>();
        Map<String, List<Member>> map6 = new HashMap<>();
        Map<String, Member> map7 = new HashMap<>();
        Map<String, List<Member>> map8 = new HashMap<>();
        Map<String, Member> map9 = new HashMap<>();
        Map<String, List<Member>> map10 = new HashMap<>();
        for (Constructor<?> constructor : this.klass.getDeclaredConstructors()) {
            if (includeMember(constructor, z)) {
                prepareAccessible(constructor, z);
                loadMethod(map, map2, constructor, "<init>");
            }
        }
        for (Method method : getDeclaredMethods()) {
            if (!isDeniedMethod(method) && includeMember(method, z)) {
                prepareAccessible(method, z);
                loadMethod(map, map2, method, method.getName());
                loadPropertyMethods(method, map3, map4, map5, map6, map7, map8, map9, map10);
            }
        }
        if (z) {
            this.methodsAll = map;
            this.multipleMethodsAll = map2;
            this.propertyGettersAll = map3;
            this.multiplePropertyGettersAll = map4;
            this.staticPropertyGettersAll = map5;
            this.multipleStaticPropertyGettersAll = map6;
            this.propertySettersAll = map7;
            this.multiplePropertySettersAll = map8;
            this.staticPropertySettersAll = map9;
            this.multipleStaticPropertySettersAll = map10;
            return;
        }
        this.methods = map;
        this.multipleMethods = map2;
        this.propertyGetters = map3;
        this.multiplePropertyGetters = map4;
        this.staticPropertyGetters = map5;
        this.multipleStaticPropertyGetters = map6;
        this.propertySetters = map7;
        this.multiplePropertySetters = map8;
        this.staticPropertySetters = map9;
        this.multipleStaticPropertySetters = map10;
    }

    private void loadPropertyMethods(Method method, Map<String, Member> map, Map<String, List<Member>> map2, Map<String, Member> map3, Map<String, List<Member>> map4, Map<String, Member> map5, Map<String, List<Member>> map6, Map<String, Member> map7, Map<String, List<Member>> map8) {
        boolean zIsStatic = Modifier.isStatic(method.getModifiers());
        for (String str : propertyGetterNames(method)) {
            loadMethod(map, map2, method, str);
            if (zIsStatic) {
                loadMethod(map3, map4, method, str);
            }
        }
        for (String str2 : propertySetterNames(method)) {
            loadMethod(map5, map6, method, str2);
            if (zIsStatic) {
                loadMethod(map7, map8, method, str2);
            }
        }
    }

    private void prepareAccessible(AccessibleObject accessibleObject, boolean z) {
        if (z) {
            try {
                accessibleObject.setAccessible(true);
            } catch (SecurityException e) {
            }
        }
    }

    private List<String> propertyGetterNames(Method method) {
        String name = method.getName();
        ArrayList arrayList = new ArrayList();
        if (name.startsWith("get") && name.length() > 3 && method.getParameterTypes().length == 0 && method.getReturnType() != Void.TYPE) {
            String strSubstring = name.substring(3);
            addPropertyAlias(arrayList, decapitalizePropertyName(strSubstring));
            if (isBooleanType(method.getReturnType())) {
                addPropertyAlias(arrayList, booleanPropertyAliasName(strSubstring));
            }
        }
        if (name.startsWith("is") && name.length() > 2 && method.getParameterTypes().length == 0 && isBooleanType(method.getReturnType())) {
            addPropertyAlias(arrayList, decapitalizePropertyName(name.substring(2)));
            addPropertyAlias(arrayList, name);
        }
        return arrayList;
    }

    private List<String> propertySetterNames(Method method) {
        String name = method.getName();
        ArrayList arrayList = new ArrayList();
        if (name.startsWith("set") && name.length() > 3 && method.getParameterTypes().length != 0) {
            String strSubstring = name.substring(3);
            addPropertyAlias(arrayList, decapitalizePropertyName(strSubstring));
            if (isBooleanType(method.getParameterTypes()[0])) {
                addPropertyAlias(arrayList, booleanPropertyAliasName(strSubstring));
            }
        }
        return arrayList;
    }

    public String[] dir() {
        String[] strArrDir;
        synchronized (this) {
            strArrDir = dir(false);
        }
        return strArrDir;
    }

    public String[] dir(boolean z) {
        String[] strArr;
        synchronized (this) {
            if (z) {
                if (this.methodsAll == null) {
                    loadMethods(true);
                }
                if (this.fieldsAll == null) {
                    loadFields(true);
                }
                if (this.classesAll == null) {
                    loadClasses(true);
                }
            } else {
                if (this.methods == null) {
                    loadMethods(false);
                }
                if (this.fields == null) {
                    loadFields(false);
                }
                if (this.classes == null) {
                    loadClasses(false);
                }
            }
            HashSet hashSet = new HashSet();
            hashSet.addAll((z ? this.methodsAll : this.methods).keySet());
            hashSet.addAll((z ? this.multipleMethodsAll : this.multipleMethods).keySet());
            hashSet.addAll((z ? this.fieldsAll : this.fields).keySet());
            hashSet.addAll((z ? this.classesAll : this.classes).keySet());
            strArr = (String[]) hashSet.toArray(new String[0]);
        }
        return strArr;
    }

    public String[] dirAll() {
        String[] strArrDir;
        synchronized (this) {
            strArrDir = dir(true);
        }
        return strArrDir;
    }

    public Field getField(String str) {
        Field field;
        synchronized (this) {
            field = getField(str, false);
        }
        return field;
    }

    public Field getField(String str, boolean z) {
        synchronized (this) {
            if (z) {
                if (this.fieldsAll == null) {
                    loadFields(true);
                }
                return this.fieldsAll.get(str);
            }
            if (this.fields == null) {
                loadFields(false);
            }
            return this.fields.get(str);
        }
    }

    public Field getFieldAll(String str) {
        Field field;
        synchronized (this) {
            field = getField(str, true);
        }
        return field;
    }

    public Member[] getMethods(String str) {
        Member[] methods;
        synchronized (this) {
            methods = getMethods(str, false);
        }
        return methods;
    }

    public Member[] getMethods(String str, boolean z) {
        synchronized (this) {
            if (z) {
                if (this.methodsAll == null) {
                    loadMethods(true);
                }
            } else if (this.methods == null) {
                loadMethods(false);
            }
            Map<String, Member> map = z ? this.methodsAll : this.methods;
            List<Member> list = (z ? this.multipleMethodsAll : this.multipleMethods).get(str);
            if (list != null) {
                return (Member[]) list.toArray(new Member[0]);
            }
            Member member = map.get(str);
            if (member != null) {
                return new Member[]{member};
            }
            return null;
        }
    }

    public Member[] getMethodsAll(String str) {
        Member[] methods;
        synchronized (this) {
            methods = getMethods(str, true);
        }
        return methods;
    }

    public Class<?> getNestedClass(String str) {
        Class<?> nestedClass;
        synchronized (this) {
            nestedClass = getNestedClass(str, false);
        }
        return nestedClass;
    }

    public Class<?> getNestedClass(String str, boolean z) {
        synchronized (this) {
            if (z) {
                if (this.classesAll == null) {
                    loadClasses(true);
                }
                return this.classesAll.get(str);
            }
            if (this.classes == null) {
                loadClasses(false);
            }
            return this.classes.get(str);
        }
    }

    public Class<?> getNestedClassAll(String str) {
        Class<?> nestedClass;
        synchronized (this) {
            nestedClass = getNestedClass(str, true);
        }
        return nestedClass;
    }

    public Member[] getPropertyGetters(String str, boolean z, int i) {
        Member[] propertyMethods;
        synchronized (this) {
            if (z) {
                if (this.propertyGettersAll == null) {
                    loadMethods(true);
                }
            } else if (this.propertyGetters == null) {
                loadMethods(false);
            }
            propertyMethods = getPropertyMethods(str, z, i, this.propertyGetters, this.multiplePropertyGetters, this.staticPropertyGetters, this.multipleStaticPropertyGetters, this.propertyGettersAll, this.multiplePropertyGettersAll, this.staticPropertyGettersAll, this.multipleStaticPropertyGettersAll);
        }
        return propertyMethods;
    }

    public Member[] getPropertySetters(String str, boolean z, int i) {
        Member[] propertyMethods;
        synchronized (this) {
            if (z) {
                if (this.propertySettersAll == null) {
                    loadMethods(true);
                }
            } else if (this.propertySetters == null) {
                loadMethods(false);
            }
            propertyMethods = getPropertyMethods(str, z, i, this.propertySetters, this.multiplePropertySetters, this.staticPropertySetters, this.multipleStaticPropertySetters, this.propertySettersAll, this.multiplePropertySettersAll, this.staticPropertySettersAll, this.multipleStaticPropertySettersAll);
        }
        return propertyMethods;
    }


    public static class DeniedMethod {
        private final String className;
        private final String methodName;
        private final Class<?>[] parameterTypes;
        private final Class<?> returnType;

        public DeniedMethod(String className, String methodName, Class<?> returnType,
                            Class<?>... parameterTypes) {
            this.className = className;
            this.methodName = methodName;
            this.returnType = returnType;
            this.parameterTypes = parameterTypes;
        }

        public boolean matches(Method method) {
            return className.equals(method.getDeclaringClass().getName())
                    && methodName.equals(method.getName())
                    && returnType == method.getReturnType()
                    && Arrays.equals(parameterTypes, method.getParameterTypes());
        }
    }
}

