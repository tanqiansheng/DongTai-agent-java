package io.dongtai.iast.core.utils.config;

import io.dongtai.iast.common.entity.performance.PerformanceMetrics;
import io.dongtai.iast.common.enums.MetricsKey;
import io.dongtai.iast.core.utils.PropertyUtils;
import io.dongtai.iast.core.utils.json.GsonUtils;
import io.dongtai.log.DongTaiLog;

import java.util.*;

/**
 * 远端配置工具类
 *
 * @author chenyi
 * @date 2022/3/3
 */
public class RemoteConfigUtils {

    private RemoteConfigUtils() {
        throw new IllegalStateException("Utility class");
    }

    /**
     * 全局配置
     */
    private static String existsRemoteConfigMeta = "{}";
    private static Boolean enableAutoFallback;
    /**
     * 高频hook限流相关配置
     */
    private static Double hookLimitTokenPerSecond;
    private static Double hookLimitInitBurstSeconds;
    /**
     * 性能熔断阈值相关配置
     */
    private static Integer performanceBreakerWindowSize;
    private static Double performanceBreakerFailureRate;
    private static Integer performanceBreakerWaitDuration;
    private static Integer maxRiskMetricsCount;
    private static List<PerformanceMetrics> performanceLimitRiskThreshold;
    private static List<PerformanceMetrics> performanceLimitMaxThreshold;

    /**
     * 同步远程配置
     *
     * @param remoteConfig 远程配置内容字符串
     */
    public static void syncRemoteConfig(String remoteConfig) {
        // 和上次配置内容不一致时才重新更新配置文件
        try {
            if (!existsRemoteConfigMeta.equals(remoteConfig)) {
                RemoteConfigEntity remoteConfigEntity = GsonUtils.toObject(remoteConfig, RemoteConfigEntity.class);
                if (remoteConfigEntity.getEnableAutoFallback() != null) {
                    enableAutoFallback = remoteConfigEntity.getEnableAutoFallback();
                }
                if (remoteConfigEntity.getHookLimitTokenPerSecond() != null) {
                    hookLimitTokenPerSecond = remoteConfigEntity.getHookLimitTokenPerSecond();
                }
                if (remoteConfigEntity.getHookLimitInitBurstSeconds() != null) {
                    hookLimitInitBurstSeconds = remoteConfigEntity.getHookLimitInitBurstSeconds();
                }
                if (remoteConfigEntity.getPerformanceBreakerWindowSize() != null) {
                    performanceBreakerWindowSize = remoteConfigEntity.getPerformanceBreakerWindowSize();
                }
                if (remoteConfigEntity.getPerformanceBreakerFailureRate() != null) {
                    performanceBreakerFailureRate = remoteConfigEntity.getPerformanceBreakerFailureRate();
                }
                if (remoteConfigEntity.getPerformanceBreakerWaitDuration() != null) {
                    performanceBreakerWaitDuration = remoteConfigEntity.getPerformanceBreakerWaitDuration();
                }
                if (remoteConfigEntity.getMaxRiskMetricsCount() != null) {
                    maxRiskMetricsCount = remoteConfigEntity.getMaxRiskMetricsCount();
                }
                performanceLimitRiskThreshold = combineRemoteAndLocalMetricsThreshold(performanceLimitRiskThreshold,
                        remoteConfigEntity.getPerformanceLimitRiskThreshold());
                performanceLimitMaxThreshold = combineRemoteAndLocalMetricsThreshold(performanceLimitMaxThreshold,
                        remoteConfigEntity.getPerformanceLimitMaxThreshold());
                existsRemoteConfigMeta = remoteConfig;
                DongTaiLog.info("Sync remote config successful.");
            }
        } catch (Throwable t) {
            DongTaiLog.warn("Sync remote config failed, msg: {}, error: {}", t.getMessage(), t.getCause());
        }
    }

    /**
     * 合并远端和本地的指标阈值配置
     *
     * @param localThreshold  本地阈值配置
     * @param remoteThreshold 远端阈值配置
     * @return {@link List}<{@link PerformanceMetrics}>
     */
    private static List<PerformanceMetrics> combineRemoteAndLocalMetricsThreshold(List<PerformanceMetrics> localThreshold,
                                                                                  List<PerformanceMetrics> remoteThreshold) {
        List<PerformanceMetrics> performanceMetricsList = new ArrayList<>();
        for (MetricsKey metricsKey : MetricsKey.values()) {
            //远端包含该指标配置
            if (remoteThreshold != null) {
                PerformanceMetrics remoteMetrics = remoteThreshold.stream()
                        .filter(each -> each.getMetricsKey() == metricsKey)
                        .findFirst().orElse(null);
                if (remoteMetrics != null) {
                    performanceMetricsList.add(remoteMetrics);
                    continue;
                }
            }
            //本地包含该指标配置
            if (localThreshold != null) {
                localThreshold.stream()
                        .filter(each -> each.getMetricsKey() == metricsKey)
                        .findFirst()
                        .ifPresent(performanceMetricsList::add);
            }
        }
        return performanceMetricsList;
    }

    // *************************************************************
    // 全局配置
    // *************************************************************

    /**
     * 是否允许自动降级
     */
    public static Boolean enableAutoFallback() {
        if (enableAutoFallback == null) {
            enableAutoFallback = PropertyUtils.getRemoteSyncLocalConfig("global.autoFallback", Boolean.class, true);
        }
        return enableAutoFallback;
    }

    // *************************************************************
    // 高频hook限流相关配置
    // *************************************************************

    /**
     * 高频hook限流-每秒获得令牌数
     */
    public static Double getHookLimitTokenPerSecond(Properties cfg) {
        if (hookLimitTokenPerSecond == null) {
            hookLimitTokenPerSecond = PropertyUtils.getRemoteSyncLocalConfig("hookLimit.tokenPerSecond", Double.class, 5000.0, cfg);
        }
        return hookLimitTokenPerSecond;
    }

    /**
     * 高频hook限流-初始预放置令牌时间
     */
    public static double getHookLimitInitBurstSeconds(Properties cfg) {
        if (hookLimitInitBurstSeconds == null) {
            hookLimitInitBurstSeconds = PropertyUtils.getRemoteSyncLocalConfig("hookLimit.initBurstSeconds", Double.class, 10.0, cfg);
        }
        return hookLimitInitBurstSeconds;
    }

    // *************************************************************
    // 性能熔断阈值相关配置
    // *************************************************************

    /**
     * 性能断路器-统计窗口大小
     */
    public static Integer getPerformanceBreakerWindowSize(Properties cfg) {
        if (performanceBreakerWindowSize == null) {
            performanceBreakerWindowSize = PropertyUtils.getRemoteSyncLocalConfig("performanceLimit.performanceBreakerWindowSize",
                    Integer.class, 2, cfg);
        }
        return performanceBreakerWindowSize;
    }

    public static void setPerformanceBreakerWindowSize(Integer performanceBreakerWindowSize) {
        RemoteConfigUtils.performanceBreakerWindowSize = performanceBreakerWindowSize;
    }

    /**
     * 性能断路器-失败率阈值
     */
    public static Double getPerformanceBreakerFailureRate(Properties cfg) {
        if (performanceBreakerFailureRate == null) {
            performanceBreakerFailureRate = PropertyUtils.getRemoteSyncLocalConfig("performanceLimit.performanceBreakerFailureRate",
                    Double.class, 51.0, cfg);
        }
        return performanceBreakerFailureRate;
    }

    public static void setPerformanceBreakerFailureRate(Double performanceBreakerFailureRate) {
        RemoteConfigUtils.performanceBreakerFailureRate = performanceBreakerFailureRate;
    }

    /**
     * 性能断路器-自动转半开的等待时间(单位:秒)
     */
    public static Integer getPerformanceBreakerWaitDuration(Properties cfg) {
        if (performanceBreakerWaitDuration == null) {
            performanceBreakerWaitDuration = PropertyUtils.getRemoteSyncLocalConfig("performanceLimit.performanceBreakerWaitDuration",
                    Integer.class, 40, cfg);
        }
        return performanceBreakerWaitDuration;
    }

    public static void setPerformanceBreakerWaitDuration(Integer performanceBreakerWaitDuration) {
        RemoteConfigUtils.performanceBreakerWaitDuration = performanceBreakerWaitDuration;
    }

    /**
     * 性能断路器-不允许超过风险阈值的指标数量(0为不限制，达到阈值数时熔断)
     */
    public static Integer getMaxRiskMetricsCount(Properties cfg) {
        if (maxRiskMetricsCount == null) {
            maxRiskMetricsCount = PropertyUtils.getRemoteSyncLocalConfig("performanceLimit.maxRiskMetricsCount", Integer.class, 3, cfg);
        }
        return maxRiskMetricsCount;
    }

    public static void setMaxRiskMetricsCount(Integer maxRiskMetricsCount) {
        RemoteConfigUtils.maxRiskMetricsCount = maxRiskMetricsCount;
    }

    /**
     * 性能断路器-风险阈值配置
     */
    public static List<PerformanceMetrics> getPerformanceLimitRiskThreshold(Properties cfg) {
        if (performanceLimitRiskThreshold == null) {
            performanceLimitRiskThreshold = buildPerformanceMetrics("performanceLimit.riskThreshold", cfg);
        }
        return performanceLimitRiskThreshold;
    }

    /**
     * 性能断路器-最大阈值配置
     */
    public static List<PerformanceMetrics> getPerformanceLimitMaxThreshold(Properties cfg) {
        if (performanceLimitMaxThreshold == null) {
            performanceLimitMaxThreshold = buildPerformanceMetrics("performanceLimit.maxThreshold", cfg);
        }
        return performanceLimitMaxThreshold;
    }

    /**
     * 从配置文件中构建性能指标
     *
     * @param configPrefix 配置前缀
     * @param cfg          配置
     * @return {@link List}<{@link PerformanceMetrics}> 性能指标列表
     */
    private static List<PerformanceMetrics> buildPerformanceMetrics(String configPrefix, Properties cfg) {
        List<PerformanceMetrics> performanceMetricsList = new ArrayList<>();
        for (MetricsKey each : MetricsKey.values()) {
            final Object metricsValue = PropertyUtils.getRemoteSyncLocalConfig(String.format("%s.%s", configPrefix, each.getKey()),
                    Object.class, null, cfg);
            final PerformanceMetrics metrics = new PerformanceMetrics();
            metrics.setMetricsKey(each);
            if (metricsValue instanceof String) {
                try {
                    final Object bean = GsonUtils.toObject((String) metricsValue, each.getValueType());
                    if (bean != null) {
                        metrics.setMetricsValue(bean);
                        performanceMetricsList.add(metrics);
                    }
                } catch (Exception e) {
                    DongTaiLog.warn("invalid metrics value config,msg:{}", e.getMessage());
                }
            }
        }
        return performanceMetricsList;
    }
}
