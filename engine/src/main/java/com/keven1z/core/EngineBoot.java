package com.keven1z.core;


import com.keven1z.JarFileHelper;
import com.keven1z.core.model.ApplicationModel;
import com.keven1z.core.monitor.MonitorManager;
import com.keven1z.core.utils.ClassUtils;
import com.keven1z.core.utils.IASTHttpClient;
import net.bytebuddy.agent.VirtualMachine;
import org.apache.commons.cli.*;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.util.Objects;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

import static com.keven1z.Agent.*;

/**
 * @author keven1z
 * @since 2023/02/21
 */
public class EngineBoot {
    EngineController engineController = null;

    public void start(Instrumentation inst, String appName, Boolean enableDetailedLogging, String projectVersion) {

        try {
            addShutdownHook();
            engineController = new EngineController();
            engineController.start(inst, appName, enableDetailedLogging, projectVersion);
        } catch (Exception e) {
            throw new RuntimeException("Engine load error," + e.getMessage());
        }
    }

    /**
     * 关闭agent
     */
    public void shutdown() {
        Logger logger = Logger.getLogger(getClass());
        try {
            if (!EngineController.context.isOfflineEnabled()) {
                if (IASTHttpClient.getClient().deregister()) {
                    logger.info("Agent deregister successfully,hostName:" + ApplicationModel.getHostName() + ",id:" + ApplicationModel.getAgentId());
                } else {
                    logger.warn("Agent deregister failed,hostName:" + ApplicationModel.getHostName() + ",id:" + ApplicationModel.getAgentId());
                }
                IASTHttpClient.getClient().close();
            }
            ApplicationModel.stop();
            logger.info("[SimpleIAST] Stop Hook Successfully");
            EngineController.context.clear();
            logger.info("[SimpleIAST] Clear Cache Successfully");
            MonitorManager.clear();
            ClassUtils.closeSimpleIASTClassLoader();
        } catch (Exception e) {
            System.err.println("[SimpleIAST] Failed to Stop SimpleIAST ,Reason:" + e.getMessage());
        }
        System.out.println("[SimpleIAST] SimpleIAST Stop Successfully");

    }

    private void addShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread("SimpleIAST-Deregister-Thread") {
            @Override
            public void run() {
                shutdown();
            }
        });
    }

    /**
     * @param targetJvmPid 目标进程id
     * @param agentJarPath agent jar包路径
     * @param mode         运行模式
     */
    private static void attachAgent(final String targetJvmPid,
                                    final String agentJarPath,
                                    final String mode,
                                    final String app,
                                    final String isDebug) throws Exception {

        VirtualMachine machine = null;
        try {
            String stringBuilder = mode + "," + app + "," + isDebug;
            machine = VirtualMachine.ForHotSpot.attach(targetJvmPid);
            machine.loadAgent(agentJarPath, stringBuilder);
        } finally {
            if (null != machine) {
                machine.detach();
            }
        }
        if (INSTALL.equals(mode)) {
            System.out.printf("[SimpleIAST] Agent installation initiated. Action: %s. Target JVM PID: %s. Proceeding with installation.%n", mode, targetJvmPid);
        } else {
            System.out.printf("[SimpleIAST] Agent uninstallation initiated. Target JVM PID: %s. Proceeding with uninstallation.%n",targetJvmPid);
        }
    }

    private static final String INSTALL = "install";
    private static final String UNINSTALL = "uninstall";

    public static void main(String[] args) {
        try {
            Options options = createOptions();
            HelpFormatter helpFormatter = new HelpFormatter();
            CommandLineParser parser = new DefaultParser();
            CommandLine cmd = parser.parse(options, args);
            if (cmd.hasOption("v")) {
                readVersion();
                System.out.printf("Version:       %s%nBuild Time:    %s%nGit Commit ID: %s%n", projectVersion, buildTime, gitCommit);

            } else if (cmd.hasOption("h")) {
                helpFormatter.printHelp("java -jar iast-engine.jar", options, true);
            } else if (cmd.hasOption("m")) {
                String mode = cmd.getOptionValue("m");
                checkMode(mode);
                String agentPath = JarFileHelper.getAgentPath();
                if (System.getProperty("os.name").startsWith("Windows")) {
                    agentPath = agentPath.substring(1);
                    agentPath = agentPath.replace("/", "\\");
                }
                String app = cmd.getOptionValue("a") == null ? "default" : cmd.getOptionValue("a");
                String isDebug = cmd.hasOption("d") ? "true" : "false";
                attachAgent(cmd.getOptionValue("p"), agentPath, mode, app, isDebug);
            } else {
                helpFormatter.printHelp("java -jar iast-engine.jar", options, true);
            }
        } catch (Throwable e) {
            System.err.println("Failed to Attach,reason:" + e.getMessage());
        }
    }

    private static Options createOptions() {
        Options options = new Options();
        options.addOption("h", "help", false, "print options information");
        options.addOption("v", "version", false, "print the version of iast");
        options.addOption("m", "mode", true, "使用模式: install 和 uninstall");
        options.addOption("p", "pid", true, "attach jvm pid");
        options.addOption("a", "app", true, "Application of agent binding,default app name is default");
        options.addOption("d", "debug", false, "Whether to start debug or not");
        return options;
    }

    /**
     * 检测mode参数是否为 install 或者 uninstall
     *
     * @param mode 运行模式
     */
    private static void checkMode(String mode) {
        if (!INSTALL.equals(mode) && !UNINSTALL.equals(mode)) {
            throw new RuntimeException("Illegal parameter mode，Please please add the parameter -m/--mode,only install or uninstall");
        }
    }

    public static void readVersion() throws IOException {
        Class<?> clazz = EngineBoot.class;
        String className = clazz.getSimpleName() + ".class";
        String classPath = Objects.requireNonNull(clazz.getResource(className)).toString();
        String manifestPath = classPath.substring(0, classPath.lastIndexOf("!") + 1) + "/META-INF/MANIFEST.MF";
        Manifest manifest = new Manifest(new URL(manifestPath).openStream());
        Attributes attr = manifest.getMainAttributes();
        projectVersion = attr.getValue("Project-Version");
        buildTime = attr.getValue("Build-Time");
        gitCommit = attr.getValue("Git-Commit");

        projectVersion = (projectVersion == null ? "UNKNOWN" : projectVersion);
        buildTime = (buildTime == null ? "UNKNOWN" : buildTime);
        gitCommit = (gitCommit == null ? "UNKNOWN" : gitCommit);
    }

}
