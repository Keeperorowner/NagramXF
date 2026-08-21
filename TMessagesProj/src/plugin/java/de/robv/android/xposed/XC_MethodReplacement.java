package de.robv.android.xposed;

public abstract class XC_MethodReplacement extends XC_MethodHook {
    public static final XC_MethodReplacement DO_NOTHING = new XC_MethodReplacement(20000) {
        @Override
        protected Object replaceHookedMethod(MethodHookParam param) {
            return null;
        }
    };

    public XC_MethodReplacement() {
    }

    public XC_MethodReplacement(int priority) {
        super(priority);
    }

    @Override
    protected final void beforeHookedMethod(MethodHookParam param) {
        try {
            param.setResult(replaceHookedMethod(param));
        } catch (Throwable t) {
            param.setThrowable(t);
        }
    }

    @Override
    protected final void afterHookedMethod(MethodHookParam param) {
    }

    protected abstract Object replaceHookedMethod(MethodHookParam param);

    public static XC_MethodReplacement returnConstant(Object value) {
        return returnConstant(50, value);
    }

    public static XC_MethodReplacement returnConstant(int priority, final Object value) {
        return new XC_MethodReplacement(priority) {
            @Override
            protected Object replaceHookedMethod(MethodHookParam param) {
                return value;
            }
        };
    }
}
