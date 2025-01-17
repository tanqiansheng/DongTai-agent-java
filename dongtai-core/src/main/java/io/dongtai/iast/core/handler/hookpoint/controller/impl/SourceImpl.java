package io.dongtai.iast.core.handler.hookpoint.controller.impl;

import io.dongtai.iast.core.EngineManager;
import io.dongtai.iast.core.handler.hookpoint.models.MethodEvent;
import io.dongtai.iast.core.handler.hookpoint.models.policy.SourceNode;
import io.dongtai.iast.core.handler.hookpoint.models.policy.TaintPosition;
import io.dongtai.iast.core.handler.hookpoint.models.taint.range.*;
import io.dongtai.iast.core.utils.StackUtils;
import io.dongtai.iast.core.utils.TaintPoolUtils;

import java.lang.reflect.Array;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 污点来源处理方法
 *
 * @author dongzhiyong@huoxian.cn
 */
public class SourceImpl {

    /**
     * 属性黑名单，用于检测属性是否可用
     */
    private static final ArrayList<String> WHITE_ATTRIBUTES = new ArrayList<String>();
    private static final String METHOD_OF_GETATTRIBUTE = "getAttribute";

    public static void solveSource(MethodEvent event, SourceNode sourceNode, AtomicInteger invokeIdSequencer) {
        if (!TaintPoolUtils.isNotEmpty(event.returnInstance)
                || !TaintPoolUtils.isAllowTaintType(event.returnInstance)
                || !allowCall(event)) {
            return;
        }

        event.source = true;
        event.setCallStacks(StackUtils.createCallStack(4));

        int invokeId = invokeIdSequencer.getAndIncrement();
        event.setInvokeId(invokeId);

        boolean valid = trackTarget(event, sourceNode);
        if (!valid) {
            return;
        }

        for (TaintPosition src : sourceNode.getSources()) {
            if (src.isObject()) {
                event.setObjectValue(event.returnInstance, true);
            } else if (src.isParameter()) {
                if (event.parameterInstances.length > src.getParameterIndex()) {
                    event.addParameterValue(src.getParameterIndex(), event.parameterInstances[src.getParameterIndex()], true);
                }
            }
        }

        for (TaintPosition tgt : sourceNode.getTargets()) {
            if (tgt.isObject()) {
                event.setObjectValue(event.returnInstance, true);
            } else if (tgt.isParameter()) {
                if (event.parameterInstances.length > tgt.getParameterIndex()) {
                    event.addParameterValue(tgt.getParameterIndex(), event.parameterInstances[tgt.getParameterIndex()], true);
                }
            } else if (tgt.isReturn()) {
                event.setReturnValue(event.returnInstance, true);

            }
        }

        if (!TaintPosition.hasObject(sourceNode.getSources()) && !TaintPosition.hasObject(sourceNode.getTargets())) {
            event.setObjectValue(event.objectInstance, false);
        }

        event.setTaintPositions(sourceNode.getSources(), sourceNode.getTargets());

        EngineManager.TRACK_MAP.addTrackMethod(invokeId, event);
    }

    private static boolean trackTarget(MethodEvent event, SourceNode sourceNode) {
        int length = TaintRangesBuilder.getLength(event.returnInstance);
        if (length == 0) {
            return false;
        }

        trackObject(event, sourceNode, event.returnInstance, 0);
        // @TODO: hook json serializer for custom model
        handlerCustomModel(event, sourceNode);
        return true;
    }

    private static void trackObject(MethodEvent event, SourceNode sourceNode, Object obj, int depth) {
        if (depth >= 10 || !TaintPoolUtils.isNotEmpty(obj) || !TaintPoolUtils.isAllowTaintType(obj)) {
            return;
        }

        int hash = System.identityHashCode(obj);
        if (EngineManager.TAINT_HASH_CODES.contains(hash)) {
            return;
        }

        Class<?> cls = obj.getClass();
        if (cls.isArray() && !cls.getComponentType().isPrimitive()) {
            trackArray(event, sourceNode, obj, depth);
        } else if (obj instanceof Iterator) {
            trackIterator(event, sourceNode, (Iterator<?>) obj, depth);
        } else if (obj instanceof Map) {
            trackMap(event, sourceNode, (Map<?, ?>) obj, depth);
        } else if (obj instanceof Map.Entry) {
            trackMapEntry(event, sourceNode, (Map.Entry<?, ?>) obj, depth);
        } else if (obj instanceof Collection) {
            if (obj instanceof List) {
                trackList(event, sourceNode, (List<?>) obj, depth);
            } else {
                trackIterator(event, sourceNode, ((Collection<?>) obj).iterator(), depth);
            }
        } else if ("java.util.Optional".equals(obj.getClass().getName())) {
            trackOptional(event, sourceNode, obj, depth);
        } else {
            int len = TaintRangesBuilder.getLength(obj);
            if (len == 0) {
                return;
            }

            TaintRanges tr = new TaintRanges(new TaintRange(0, len));
            if (sourceNode.hasTags()) {
                String[] tags = sourceNode.getTags();
                for (String tag : tags) {
                    tr.add(new TaintRange(tag, 0, len));
                }
            }
            event.targetRanges.add(new MethodEvent.MethodEventTargetRange(hash, tr));

            EngineManager.TAINT_HASH_CODES.add(hash);
            event.addTargetHash(hash);
            EngineManager.TAINT_RANGES_POOL.add(hash, tr);
        }
    }

    private static void trackArray(MethodEvent event, SourceNode sourceNode, Object arr, int depth) {
        int length = Array.getLength(arr);
        for (int i = 0; i < length; i++) {
            trackObject(event, sourceNode, Array.get(arr, i), depth + 1);
        }
    }

    private static void trackIterator(MethodEvent event, SourceNode sourceNode, Iterator<?> it, int depth) {
        while (it.hasNext()) {
            trackObject(event, sourceNode, it.next(), depth + 1);
        }
    }

    private static void trackMap(MethodEvent event, SourceNode sourceNode, Map<?, ?> map, int depth) {
        for (Object key : map.keySet()) {
            trackObject(event, sourceNode, key, depth + 1);
            trackObject(event, sourceNode, map.get(key), depth + 1);
        }
    }

    private static void trackMapEntry(MethodEvent event, SourceNode sourceNode, Map.Entry<?, ?> entry, int depth) {
        trackObject(event, sourceNode, entry.getKey(), depth + 1);
        trackObject(event, sourceNode, entry.getValue(), depth + 1);
    }

    private static void trackList(MethodEvent event, SourceNode sourceNode, List<?> list, int depth) {
        for (Object obj : list) {
            trackObject(event, sourceNode, obj, depth + 1);
        }
    }

    private static void trackOptional(MethodEvent event, SourceNode sourceNode, Object obj, int depth) {
        try {
            Object v = ((Optional<?>) obj).orElse(null);
            trackObject(event, sourceNode, v, depth + 1);
        } catch (Throwable ignore) {
        }
    }

    /**
     * todo: 处理过程和结果需要细化
     *
     * @param event MethodEvent
     */
    public static void handlerCustomModel(MethodEvent event, SourceNode sourceNode) {
        if (!"getSession".equals(event.getMethodName())) {
            Set<Object> modelValues = TaintPoolUtils.parseCustomModel(event.returnInstance);
            for (Object modelValue : modelValues) {
                trackObject(event, sourceNode, modelValue, 0);
            }
        }
    }

    private static boolean allowCall(MethodEvent event) {
        boolean allowed = true;
        if (METHOD_OF_GETATTRIBUTE.equals(event.getMethodName())) {
            return allowAttribute((String) event.parameterInstances[0]);
        }
        return allowed;
    }

    /**
     * 检查属性是否xxx，时间复杂度：O(n) fixme: spring参数解析，白名单导致数据不正确
     *
     * @param attribute 属性名称
     * @return true-属性允许，false-属性不允许
     */
    private static boolean allowAttribute(String attribute) {
        return WHITE_ATTRIBUTES.contains(attribute);
    }

    static {
        WHITE_ATTRIBUTES.add(" org.springframework.web.servlet.HandlerMapping.bestMatchingPattern".substring(1));
        WHITE_ATTRIBUTES.add(" org.springframework.web.servlet.HandlerMapping.pathWithinHandlerMapping".substring(1));
        WHITE_ATTRIBUTES.add(" org.springframework.web.servlet.HandlerMapping.uriTemplateVariables".substring(1));
        WHITE_ATTRIBUTES.add(" org.springframework.web.servlet.View.pathVariables".substring(1));
    }

}
