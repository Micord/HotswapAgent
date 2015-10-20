package org.hotswap.agent.plugin.geminiblueprint;

import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.net.URL;
import java.security.ProtectionDomain;
import java.util.Enumeration;

import org.hotswap.agent.annotation.Init;
import org.hotswap.agent.annotation.OnClassLoadEvent;
import org.hotswap.agent.annotation.Plugin;
import org.hotswap.agent.command.Scheduler;
import org.hotswap.agent.javassist.*;
import org.hotswap.agent.logging.AgentLogger;
import org.hotswap.agent.plugin.geminiblueprint.scanner.ClassPathBeanDefinitionScannerTransformer;
import org.hotswap.agent.plugin.geminiblueprint.scanner.ClassPathBeanRefreshCommand;
import org.hotswap.agent.plugin.geminiblueprint.scanner.ProxyReplacerTransformer;
import org.hotswap.agent.plugin.spring.SpringChangesAnalyzer;
import org.hotswap.agent.util.HotswapTransformer;
import org.hotswap.agent.util.IOUtils;
import org.hotswap.agent.util.PluginManagerInvoker;
import org.hotswap.agent.util.classloader.ClassLoaderHelper;
import org.hotswap.agent.watch.WatchEventListener;
import org.hotswap.agent.watch.WatchFileEvent;
import org.hotswap.agent.watch.Watcher;

/**
 * @author Abdulin
 */
@Plugin(name = "GeminiBlueprint", description = "Reload gemini blueprint spring configuration",
    testedVersions = {"Gemini 2.0.0 version"}, expectedVersions = {"2x"}, supportClass = {ClassPathBeanDefinitionScannerTransformer.class, ProxyReplacerTransformer.class})
public class GeminiBlueprintPlugin {

  private static AgentLogger LOGGER = AgentLogger.getLogger(GeminiBlueprintPlugin.class);


  /**
   * If a class is modified in IDE, sequence of multiple events is generated -
   * class file DELETE, CREATE, MODIFY, than Hotswap transformer is invoked.
   * ClassPathBeanRefreshCommand tries to merge these events into single command.
   * Wait this this timeout after class file event.
   */
  private static final int WAIT_ON_CREATE = 600;

  @Init
  HotswapTransformer hotswapTransformer;

  @Init
  Watcher watcher;

  @Init
  Scheduler scheduler;

  @Init
  ClassLoader appClassLoader;

  public void init() {
    LOGGER.info("GeminiBlueprint plugin initialized");
  }

  @OnClassLoadEvent(classNameRegexp = "org.eclipse.gemini.blueprint.context.support.AbstractOsgiBundleApplicationContext")
  public static void register(CtClass clazz) throws NotFoundException, CannotCompileException {
    StringBuilder src = new StringBuilder("{");
    src.append(PluginManagerInvoker.buildInitializePlugin(GeminiBlueprintPlugin.class, "getClassLoader()"));

    src.append(PluginManagerInvoker.buildCallPluginMethod("getClassLoader()", GeminiBlueprintPlugin.class, "init"));
    src.append("}");

    CtMethod method = clazz.getDeclaredMethod("setBundleContext");
    method.insertAfter(src.toString());
  }

  /**
   * Register both hotswap transformer AND watcher - in case of new file the file is not known
   * to JVM and hence no hotswap is called. The file may even exist, but until is loaded by Spring
   * it will not be known by the JVM. File events are processed only if the class is not known to the
   * classloader yet.
   *
   * @param basePackage only files in a basePackage
   */
  public void registerComponentScanBasePackage(final String basePackage, final Object scannerAgent) {
    final SpringChangesAnalyzer analyzer = new SpringChangesAnalyzer(appClassLoader);
    hotswapTransformer.registerTransformer(appClassLoader, basePackage + ".*", new ClassFileTransformer() {
      @Override
      public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) throws IllegalClassFormatException {
        if (classBeingRedefined != null) {
          boolean reloadNeeded = analyzer.isReloadNeeded(classBeingRedefined, classfileBuffer);
//          if (reloadNeeded) {
          scheduler.scheduleCommand(new ClassPathBeanRefreshCommand(classBeingRedefined.getClassLoader(), scannerAgent,
              basePackage, className, classfileBuffer));
//          }
        }
        return classfileBuffer;
      }
    });

    Enumeration<URL> resourceUrls = null;
    try {
      resourceUrls = appClassLoader.getResources(basePackage.replace(".", "/"));
    }
    catch (IOException e) {
      LOGGER.error("Unable to resolve base package {} in classloader {}.", basePackage, appClassLoader);
      return;
    }

    // for all application resources watch for changes
    while (resourceUrls.hasMoreElements()) {
      URL basePackageURL = resourceUrls.nextElement();

      if (!IOUtils.isFileURL(basePackageURL)) {
        LOGGER.debug("Spring basePackage '{}' - unable to watch files on URL '{}' for changes (JAR file?), limited hotswap reload support. " +
            "Use extraClassPath configuration to locate class file on filesystem.", basePackage, basePackageURL);
        continue;
      }
      else {
        watcher.addEventListener(appClassLoader, basePackageURL, new WatchEventListener() {
          @Override
          public void onEvent(WatchFileEvent event) {
            if (event.isFile() && event.getURI().toString().endsWith(".class")) {
              // check that the class is not loaded by the classloader yet (avoid duplicate reload)
              String className;
              try {
                className = IOUtils.urlToClassName(event.getURI());
              }
              catch (IOException e) {
                LOGGER.trace("Watch event on resource '{}' skipped, probably Ok because of delete/create event sequence (compilation not finished yet).", e, event.getURI());
                return;
              }
              if (!ClassLoaderHelper.isClassLoaded(appClassLoader, className)) {
                // refresh spring only for new classes
                scheduler.scheduleCommand(new ClassPathBeanRefreshCommand(appClassLoader, scannerAgent,
                    basePackage, event), WAIT_ON_CREATE);
              }
            }
          }
        });
      }
    }
  }

  /**
   * Plugin initialization is after Spring has finished its startup and freezeConfiguration is called.
   * <p>
   * This will override freeze method to init plugin - plugin will be initialized and the configuration
   * remains unfrozen, so bean (re)definition may be done by the plugin.
   */
  @OnClassLoadEvent(classNameRegexp = "org.springframework.beans.factory.support.DefaultListableBeanFactory")
  public static void patchDefalutListableBeanFactory(CtClass clazz) throws NotFoundException, CannotCompileException {
    StringBuilder src = new StringBuilder("{");
    src.append("setCacheBeanMetadata(false);");
    src.append("}");

    for (CtConstructor constructor : clazz.getDeclaredConstructors()) {
      constructor.insertBeforeBody(src.toString());
    }

    // freezeConfiguration cannot be disabled because of performance degradation
    // instead call freezeConfiguration after each bean (re)definition and clear all caches.

    // WARNING - allowRawInjectionDespiteWrapping is not safe, however without this
    //   spring was not able to resolve circular references correctly.
    //   However, the code in AbstractAutowireCapableBeanFactory.doCreateBean() in debugger always
    //   showed that exposedObject == earlySingletonReference and hence everything is Ok.
    // 				if (exposedObject == bean) {
    //                  exposedObject = earlySingletonReference;
    //   The waring is because I am not sure what is going on here.

    CtMethod method = clazz.getDeclaredMethod("freezeConfiguration");
    method.insertBefore(
        "org.hotswap.agent.plugin.spring.ResetSpringStaticCaches.resetBeanNamesByType(this); " +
            "setAllowRawInjectionDespiteWrapping(true); ");
  }

  @OnClassLoadEvent(classNameRegexp = "org.springframework.aop.framework.CglibAopProxy")
  public static void cglibAopProxyDisableCache(CtClass ctClass) throws NotFoundException, CannotCompileException {
    CtMethod method = ctClass.getDeclaredMethod("createEnhancer");
    method.setBody("{" +
        "org.springframework.cglib.proxy.Enhancer enhancer = new org.springframework.cglib.proxy.Enhancer();" +
        "enhancer.setUseCache(false);" +
        "return enhancer;" +
        "}");

    LOGGER.debug("org.springframework.aop.framework.CglibAopProxy - cglib Enhancer cache disabled");
  }
}
