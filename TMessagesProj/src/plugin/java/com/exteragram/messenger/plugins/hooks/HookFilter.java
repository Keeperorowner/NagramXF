package com.exteragram.messenger.plugins.hooks;

import com.exteragram.messenger.plugins.PluginsConstants;

import org.mvel2.MVEL;
import org.telegram.messenger.FileLog;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;

public class HookFilter {
    private static final ConcurrentHashMap<String, Serializable> MVEL_EXPRESSION_CACHE = new ConcurrentHashMap<>();

    public final String filterType;
    public Integer argIndex;
    public ArrayList<HookFilter> orFilters;
    public String mvelExpression;
    public Class<?> instanceOf;
    public Object object;

    public HookFilter(String filterType) {
        this.filterType = filterType;
    }

    public boolean execute(XC_MethodHook.MethodHookParam param, boolean before) {
        try {
            if (PluginsConstants.HookFilterTypes.OR.equals(filterType)) {
                if (orFilters == null) {
                    return false;
                }
                for (HookFilter filter : orFilters) {
                    if (filter.execute(param, before)) {
                        return true;
                    }
                }
                return false;
            }
            if (PluginsConstants.HookFilterTypes.CONDITION.equals(filterType)) {
                return executeCondition(param, before);
            }
            if (isResultFilter(filterType)) {
                if (before) {
                    return false;
                }
                return evaluateValue(param.getResult(), filterType, true);
            }
            if (argIndex == null || param.args == null || argIndex < 0 || argIndex >= param.args.length) {
                return false;
            }
            return evaluateValue(param.args[argIndex], filterType, false);
        } catch (Throwable t) {
            FileLog.e(t);
            return false;
        }
    }

    private boolean executeCondition(XC_MethodHook.MethodHookParam param, boolean before) {
        if (mvelExpression == null) {
            return false;
        }
        Map<String, Object> variables = new HashMap<>();
        variables.put("param", param);
        variables.put("result", before ? null : param.getResult());
        variables.put("object", object);
        Serializable expression = MVEL_EXPRESSION_CACHE.computeIfAbsent(mvelExpression, MVEL::compileExpression);
        Boolean result = MVEL.executeExpression(expression, param.thisObject, variables, Boolean.class);
        return Boolean.TRUE.equals(result);
    }

    private static boolean isResultFilter(String type) {
        return type != null && type.startsWith("result_");
    }

    private boolean evaluateValue(Object value, String type, boolean resultFilter) {
        return switch (type) {
            case PluginsConstants.HookFilterTypes.ARGUMENT_EQUAL, PluginsConstants.HookFilterTypes.RESULT_EQUAL -> valuesEqual(value, object);
            case PluginsConstants.HookFilterTypes.ARGUMENT_NOT_EQUAL, PluginsConstants.HookFilterTypes.RESULT_NOT_EQUAL -> !valuesEqual(value, object);
            case PluginsConstants.HookFilterTypes.ARGUMENT_IS_TRUE, PluginsConstants.HookFilterTypes.RESULT_IS_TRUE -> value instanceof Boolean b && b;
            case PluginsConstants.HookFilterTypes.ARGUMENT_IS_FALSE, PluginsConstants.HookFilterTypes.RESULT_IS_FALSE -> value instanceof Boolean b && !b;
            case PluginsConstants.HookFilterTypes.ARGUMENT_IS_NULL, PluginsConstants.HookFilterTypes.RESULT_IS_NULL -> value == null;
            case PluginsConstants.HookFilterTypes.ARGUMENT_NOT_NULL, PluginsConstants.HookFilterTypes.RESULT_NOT_NULL -> value != null;
            case PluginsConstants.HookFilterTypes.ARGUMENT_IS_INSTANCE_OF, PluginsConstants.HookFilterTypes.RESULT_IS_INSTANCE_OF -> instanceOf != null && instanceOf.isInstance(value);
            default -> false;
        };
    }

    private static boolean valuesEqual(Object a, Object b) {
        if (Objects.equals(a, b)) {
            return true;
        }
        if (a instanceof Number na && b instanceof Number nb) {
            if (!(a instanceof Double) && !(a instanceof Float) && !(b instanceof Double) && !(b instanceof Float)) {
                return na.longValue() == nb.longValue();
            }
            return na.doubleValue() == nb.doubleValue();
        }
        return false;
    }
}
