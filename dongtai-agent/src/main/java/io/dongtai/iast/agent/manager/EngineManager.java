package io.dongtai.iast.agent.manager;

import io.dongtai.iast.agent.IastClassLoader;
import io.dongtai.iast.agent.IastProperties;
import io.dongtai.iast.agent.report.AgentRegisterReport;
import io.dongtai.iast.agent.util.FileUtils;
import io.dongtai.iast.agent.util.JavaVersionUtils;
import io.dongtai.iast.agent.util.http.HttpClientUtils;
import io.dongtai.log.DongTaiLog;
import org.json.JSONObject;

import java.io.*;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationTargetException;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.util.jar.JarFile;

/**
 * 引擎管理类，负责engine模块的完整生命周期，包括：下载、安装、启动、停止、重启、卸载
 *
 * @author dongzhiyong@huoxian.cn
 */
public class EngineManager {

    private static final String ENGINE_ENTRYPOINT_CLASS = "com.secnium.iast.core.AgentEngine";
    private static final String PERFORMANCE_BREAKER_DEFAULT = "io.dongtai.iast.core.bytecode.enhance.plugin.limiter.breaker.DefaultPerformanceBreaker";
    private static final String PERFORMANCE_BREAKER_NOP = "io.dongtai.iast.core.bytecode.enhance.plugin.limiter.breaker.NopPerformanceBreaker";
    private static final String REMOTE_CONFIG_UTIL = "io.dongtai.iast.core.utils.RemoteConfigUtils";
    private static final String INJECT_PACKAGE_REMOTE_URI = "/api/v1/engine/download?engineName=dongtai-spy";
    private static final String ENGINE_PACKAGE_REMOTE_URI = "/api/v1/engine/download?engineName=dongtai-core";
    private static final String API_PACKAGE_REMOTE_URI = "/api/v1/engine/download?engineName=dongtai-api";
    private static IastClassLoader IAST_CLASS_LOADER;
    private static EngineManager INSTANCE;
    private static String PID;

    private final Instrumentation inst;
    private int runningStatus;
    private final IastProperties properties;
    private final String launchMode;
    private Class<?> classOfEngine;

    /**
     * 获取IAST引擎的启动状态
     *
     * @return 启动状态
     */
    public int getRunningStatus() {
        return runningStatus;
    }

    /**
     * 设置IAST引擎的启动状态
     *
     * @param runningStatus 启动状态
     */
    public void setRunningStatus(int runningStatus) {
        this.runningStatus = runningStatus;
    }

    /**
     * 获取IAST引擎管理器的单例对象
     *
     * @param inst       instrumentation接口实例化对象
     * @param launchMode IAST引擎的启动模式，attach、premain两种
     * @param ppid       IAST引擎运行的进程ID，用于后续进行热更新
     * @return IAST引擎管理器的实例化对象
     */
    public static EngineManager getInstance(Instrumentation inst, String launchMode, String ppid) {
        if (INSTANCE == null) {
            INSTANCE = new EngineManager(inst, launchMode, ppid);
        }
        return INSTANCE;
    }

    /**
     * 在核心包中加载并获取性能断路器类
     *
     * @return {@link Class}<{@link ?}>
     * @throws ClassNotFoundException 未找到类异常
     */
    public static Class<?> getPerformanceBreaker() throws ClassNotFoundException {
        if (IAST_CLASS_LOADER == null) {
            return null;
        }
        String clazz;
        if (JavaVersionUtils.isJava6() || JavaVersionUtils.isJava7()) {
            clazz = PERFORMANCE_BREAKER_NOP;
        } else {
            clazz = PERFORMANCE_BREAKER_DEFAULT;
        }
        return IAST_CLASS_LOADER.loadClass(clazz);
    }

    /**
     * 在核心包中加载并获取远端配置工具类
     *
     * @return
     * @throws ClassNotFoundException
     */
    public static Class<?> getRemoteConfigUtils() throws ClassNotFoundException{
        if (IAST_CLASS_LOADER == null) {
            return null;
        }
        return IAST_CLASS_LOADER.loadClass(REMOTE_CONFIG_UTIL);
    }

    /**
     * 获取IAST引擎管理器的单例对象
     *
     * @return IAST引擎管理器的实例化对象
     */
    public static EngineManager getInstance() {
        return INSTANCE;
    }

    public EngineManager(Instrumentation inst, String launchMode, String ppid) {
        this.inst = inst;
        this.runningStatus = 0;
        this.launchMode = launchMode;
        this.properties = IastProperties.getInstance();
    }

    /**
     * 获取IAST检测引擎本地保存的临时路径，用于后续从本地目录加载Jar包
     *
     * @return engine包的本地保存路径
     */
    private static String getEnginePackageCachePath() {
        return System.getProperty("java.io.tmpdir") + File.separator + "iast" + File.separator + "dongtai-core.jar";
    }

    /**
     * 获取IAST间谍引擎本地保存的临时路径，用于后续从本地目录加载Jar包
     *
     * @return inject包的本地路径
     */
    private static String getInjectPackageCachePath() {
        return System.getProperty("java.io.tmpdir") + File.separator + "iast" + File.separator + "dongtai-spy.jar";
    }

    /**
     * 获取IAST间谍引擎本地保存的临时路径，用于后续从本地目录加载Jar包
     *
     * @return inject包的本地路径
     */
    private static String getApiPackagePath() {
        return System.getProperty("java.io.tmpdir") + File.separator + "iast" + File.separator + "dongtai-api.jar";
    }


    /**
     * 从远程URI下载jar包到指定的本地文件
     *
     * @param fileUrl  远程URI
     * @param fileName 本地文件路径
     * @return 下载结果，成功为true，失败为false
     */
    private boolean downloadJarPackageToCacheFromUrl(String fileUrl, String fileName) {
        boolean status = false;
        try {
            URL url = new URL(fileUrl);
            Proxy proxy = HttpClientUtils.loadProxy();
            HttpURLConnection connection = proxy == null ? (HttpURLConnection) url.openConnection()
                    : (HttpURLConnection) url.openConnection(proxy);

            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent", "DongTai-IAST-Agent");
            connection.setRequestProperty("Authorization", "Token " + properties.getIastServerToken());
            connection.setUseCaches(false);
            connection.setDoOutput(true);

            if (connection.getContentType().equals("application/json")) {
                BufferedReader streamReader = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"));
                StringBuilder responseStrBuilder = new StringBuilder();
                String inputStr;
                while ((inputStr = streamReader.readLine()) != null)
                    responseStrBuilder.append(inputStr);

                JSONObject jsonObject = new JSONObject(responseStrBuilder.toString());
                DongTaiLog.error("DongTai Core Package: {} download failed. response: {}", fileUrl, jsonObject);
                return false;
            } else {
                BufferedInputStream in = new BufferedInputStream(connection.getInputStream());
                final File classPath = new File(new File(fileName).getParent());

                if (!classPath.mkdirs() && !classPath.exists()) {
                    DongTaiLog.info("Check or create local file cache path, path is " + classPath);
                }
                FileOutputStream fileOutputStream = new FileOutputStream(fileName);
                byte[] dataBuffer = new byte[1024];
                int bytesRead;
                while ((bytesRead = in.read(dataBuffer, 0, 1024)) != -1) {
                    fileOutputStream.write(dataBuffer, 0, bytesRead);
                }
                in.close();
                fileOutputStream.close();
                DongTaiLog.info("The remote file " + fileUrl + " was successfully written to the local cache.");
                status = true;
            }
        } catch (Exception ignore) {
            DongTaiLog.error("The remote file " + fileUrl + " download failure, please check the dongtai-token.");
        }
        return status;
    }

    /**
     * 更新IAST引擎需要的jar包，用于启动时加载和热更新检测引擎 - iast-core.jar - iast-inject.jar
     *
     * @return 更新状态，成功为true，失败为false
     */
    public boolean downloadPackageFromServer() {
        String baseUrl = properties.getBaseUrl();
        return downloadJarPackageToCacheFromUrl(baseUrl + INJECT_PACKAGE_REMOTE_URI, getInjectPackageCachePath()) &&
                downloadJarPackageToCacheFromUrl(baseUrl + ENGINE_PACKAGE_REMOTE_URI, getEnginePackageCachePath()) &&
                downloadJarPackageToCacheFromUrl(baseUrl + API_PACKAGE_REMOTE_URI, getApiPackagePath());
    }

    /**
     * 从 dongtai-agent.jar 提取相关的jar包
     *
     * @return 提取结果，成功为true，失败为false
     */
    public boolean extractPackageFromAgent() {
        //
        try {
            return FileUtils.getResourceToFile("bin/dongtai-spy.jar", getInjectPackageCachePath()) &&
                    FileUtils.getResourceToFile("bin/dongtai-core.jar", getEnginePackageCachePath()) &&
                    FileUtils.getResourceToFile("bin/dongtai-api.jar", getApiPackagePath());
        } catch (IOException e) {
            e.printStackTrace();
        }
        return false;
    }


    public boolean extractPackage() {
        // 解析jar包到本地
        String spyPackage = getInjectPackageCachePath();
        String enginePackage = getEnginePackageCachePath();
        String apiPackage = getApiPackagePath();
        if (properties.isDebug()) {
            DongTaiLog.info("current mode: debug, try to read package from directory {}", System.getProperty("java.io.tmpdir"));
            if ((new File(spyPackage)).exists() && (new File(enginePackage)).exists() && (new File(apiPackage)).exists()) {
                return true;
            }
        }
        return extractPackageFromAgent() || downloadPackageFromServer();
    }

    public boolean install() {
        String spyPackage = EngineManager.getInjectPackageCachePath();
        String corePackage = EngineManager.getEnginePackageCachePath();
        try {
            JarFile file = new JarFile(new File(spyPackage));
            inst.appendToBootstrapClassLoaderSearch(file);
            file.close();
            if (IAST_CLASS_LOADER == null) {
                IAST_CLASS_LOADER = new IastClassLoader(corePackage);
            }
            classOfEngine = IAST_CLASS_LOADER.loadClass(ENGINE_ENTRYPOINT_CLASS);
            String agentPath = this.getClass().getProtectionDomain().getCodeSource().getLocation().getFile();
            classOfEngine.getMethod("install", String.class, String.class, Integer.class, Instrumentation.class,
                    String.class)
                    .invoke(null, launchMode, this.properties.getPropertiesFilePath(),
                            AgentRegisterReport.getAgentFlag(), inst, agentPath);
            return true;
        } catch (IOException e) {
            DongTaiLog.error("DongTai engine start failed, Reason: dongtai-spy.jar or dongtai-core.jar open failed. path: \n\tdongtai-core.jar: " + corePackage + "\n\tdongtai-spy.jar: " + spyPackage);
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
            DongTaiLog.error("ClassNotFoundException: DongTai engine start failed, please contact staff for help.");
        } catch (Throwable throwable) {
            DongTaiLog.error("Throwable: DongTai engine start failed, please contact staff for help.");
            throwable.printStackTrace();
        }
        return false;
    }

    /**
     * 启动检测引擎
     */
    public boolean start() {
        // 将Spy注入到BootstrapClassLoader，todo: 异常卸载时，需要特定处理spy模块
        try {
            if (classOfEngine != null) {
                classOfEngine.getMethod("start").invoke(null);
                DongTaiLog.info("DongTai engine start successfully.");
                return true;
            }
            return false;
        } catch (InvocationTargetException e) {
            e.printStackTrace();
            DongTaiLog.error("DongTai engine start failed, please contact staff for help.");
        } catch (NoSuchMethodException e) {
            e.printStackTrace();
            DongTaiLog.error("DongTai engine start failed, please contact staff for help.");
        } catch (IllegalAccessException e) {
            e.printStackTrace();
            DongTaiLog.error("DongTai engine start failed, please contact staff for help.");
        } catch (Throwable throwable) {
            DongTaiLog.error("DongTai engine start failed, please contact staff for help.");
            throwable.printStackTrace();
        }
        return false;
    }

    /**
     * 停止检测引擎
     *
     * @return 布尔值，表示stop成功或失败
     */
    public boolean stop() {
        // 将Spy注入到BootstrapClassLoader，todo: 异常卸载时，需要特定处理spy模块
        try {
            if (classOfEngine != null) {
                classOfEngine.getMethod("stop").invoke(null);
                DongTaiLog.info("DongTai engine stop successfully.");
                return true;
            }
            return false;
        } catch (InvocationTargetException e) {
            DongTaiLog.error("DongTai engine stop failed, please contact staff for help.");
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            e.printStackTrace(pw);
            DongTaiLog.error(sw.toString());
        } catch (NoSuchMethodException e) {
            DongTaiLog.error("DongTai engine stop failed, please contact staff for help.");
        } catch (IllegalAccessException e) {
            DongTaiLog.error("DongTai engine stop failed, please contact staff for help.");
            e.printStackTrace();
        } catch (Throwable throwable) {
            DongTaiLog.error("DongTai engine stop failed, please contact staff for help.");
            throwable.printStackTrace();
        }
        return false;
    }

    /**
     * 卸载间谍包、检测引擎包
     *
     * @question: 通过 inst.appendToBootstrapClassLoaderSearch() 方法加入的jar包无法直接卸载；
     */
    public synchronized boolean uninstall() {
        if (null == IAST_CLASS_LOADER) {
            return true;
        }

        try {
            if (classOfEngine != null) {
                classOfEngine.getMethod("destroy", String.class, String.class, Instrumentation.class)
                        .invoke(null, launchMode, this.properties.getPropertiesFilePath(), inst);
            }
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        } catch (InvocationTargetException e) {
            e.printStackTrace();
        } catch (NoSuchMethodException e) {
            e.printStackTrace();
        }

        // 关闭SandboxClassLoader
        classOfEngine = null;
        IAST_CLASS_LOADER.closeIfPossible();
        IAST_CLASS_LOADER = null;
        return true;
    }


    public static String getPID() {
        if (PID == null) {
            PID = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
        }
        return PID;
    }
}
