# 关于embedded-tomcat的学习

## spring-boot启动过程

spring-boot可以在3中环境中运行,即无web环境/servlet-web环境/reactive-web环境.

SpringApplication.run启动初始阶段时,会基于classpath的信息来确定使用哪种运行环境(即WebApplicationType)
```text
org.springframework.boot.SpringApplication.SpringApplication(org.springframework.core.io.ResourceLoader, java.lang.Class<?>...)
public SpringApplication(ResourceLoader resourceLoader, Class<?>... primarySources) {
    // ...
    this.webApplicationType = WebApplicationType.deduceFromClasspath();
    // ...
}

org.springframework.boot.WebApplicationType.deduceFromClasspath
static WebApplicationType deduceFromClasspath() {
    if (ClassUtils.isPresent(WEBFLUX_INDICATOR_CLASS, null) && !ClassUtils.isPresent(WEBMVC_INDICATOR_CLASS, null)
            && !ClassUtils.isPresent(JERSEY_INDICATOR_CLASS, null)) {
        return WebApplicationType.REACTIVE;
    }
    for (String className : SERVLET_INDICATOR_CLASSES) {
        if (!ClassUtils.isPresent(className, null)) {
            return WebApplicationType.NONE;
        }
    }
    return WebApplicationType.SERVLET;
}
```
当判定运行环境为servlet-web环境时(即WebApplicationType.SERVLET),spring-boot的主类SpringApplication的主要属性也会做响应的调整.
SpringApplication将AnnotationConfigServletWebServerApplicationContext作为spring容器,用来容纳所需的bean.
```text
ConfigurableEnvironment --> StandardServletEnvironment
ConfigurableApplicationContext --> AnnotationConfigServletWebServerApplicationContext
```

### AnnotationConfigServletWebServerApplicationContext

AnnotationConfigServletWebServerApplicationContext是ServletWebServerApplicationContext的子类.
ServletWebServerApplicationContext扩展了普通的容器接口ConfigurableApplicationContext
```text
// refresh执行中创建WebServer
protected void onRefresh() {
    super.onRefresh();
    try {
        createWebServer();
    }
    catch (Throwable ex) {
        throw new ApplicationContextException("Unable to start web server", ex);
    }
}

private void createWebServer() {
    WebServer webServer = this.webServer;
    ServletContext servletContext = getServletContext();
    // 如果没有指定webServer
    if (webServer == null && servletContext == null) {
        StartupStep createWebServer = this.getApplicationStartup().start("spring.boot.webserver.create");
        ServletWebServerFactory factory = getWebServerFactory();
        createWebServer.tag("factory", factory.getClass().toString());
        // 创建webServer时,预留了initializer类做初始化定制
        this.webServer = factory.getWebServer(getSelfInitializer());
        createWebServer.end();
        // 向spring容器中注册WebServerGracefulShutdownLifecycle,这样在spring容器关闭时回调SmartLifecycle.stop()方法进而调用webServer.stop()
        getBeanFactory().registerSingleton("webServerGracefulShutdown", new WebServerGracefulShutdownLifecycle(this.webServer));
        // 向spring容器中注册WebServerStartStopLifecycle,这样容器启动后自动调用回调SmartLifecycle.start()方法进而调用webServer.start()从而触发tomcat的启动
        getBeanFactory().registerSingleton("webServerStartStop", new WebServerStartStopLifecycle(this, this.webServer));
    } else if (servletContext != null) { //如果指定了webServer且假定已经做好了webServer的初始化工作
        try {
            // 将spring容器中与servlet容器相关的servlets, filters, listeners context-params and attributes necessary for initialization.
            // 具体实现类有RegistrationBean/SessionConfiguringInitializer
            getSelfInitializer().onStartup(servletContext);
        } catch (ServletException ex) {
            throw new ApplicationContextException("Cannot initialize servlet context", ex);
        }
    }
    // 将容器中的servlet相关的占位符替换为真实的servlet值
    initPropertySources();
}

public final void refresh() throws BeansException, IllegalStateException {
    try {
        // 正常的refresh执行流程
        super.refresh();
    }
    catch (RuntimeException ex) {
        // refresh执行异常,要将webServer停掉
        WebServer webServer = this.webServer;
        if (webServer != null) {
            webServer.stop();
        }
        throw ex;
    }
}
```

为了方便记忆, 简化AbstractApplicationContext.refresh的执行逻辑
```text
public void refresh() throws BeansException, IllegalStateException {
		// ...

		// Initialize other special beans in specific context subclasses.
		onRefresh();
		
		// ...
		
		// Instantiate all remaining (non-lazy-init) singletons. (这里会将容器中的singleton实例化并初始化)
		finishBeanFactoryInitialization(beanFactory);

		// Last step: publish corresponding event.
		// 这里会发布ContextRefreshedEvent事件,
		// 还会通知调用LifecycleProcessor.onRefresh方法(基于方法名来通知事件),LifecycleProcessor将会调用所有Lifecycle对象的start方法
		finishRefresh();
}
```

### TomcatWebServer

#### TomcatServletWebServerFactory

TomcatServletWebServerFactory是关于tomcat的WebServerFactory实现类
```text
public WebServer getWebServer(ServletContextInitializer... initializers) {
    if (this.disableMBeanRegistry) {
        Registry.disableRegistry();
    }
    Tomcat tomcat = new Tomcat();
    File baseDir = (this.baseDirectory != null) ? this.baseDirectory : createTempDir("tomcat");
    tomcat.setBaseDir(baseDir.getAbsolutePath());
    Connector connector = new Connector(this.protocol);
    connector.setThrowOnFailure(true);
    tomcat.getService().addConnector(connector);
    customizeConnector(connector);
    tomcat.setConnector(connector);
    tomcat.getHost().setAutoDeploy(false);
    configureEngine(tomcat.getEngine());
    for (Connector additionalConnector : this.additionalTomcatConnectors) {
        tomcat.getService().addConnector(additionalConnector);
    }
    // 关于tomcat的用户定制化逻辑
    prepareContext(tomcat.getHost(), initializers);
    return getTomcatWebServer(tomcat);
}

protected TomcatWebServer getTomcatWebServer(Tomcat tomcat) {
    return new TomcatWebServer(tomcat, getPort() >= 0, getShutdown());
}
```
#### TomcatWebServer

如果spring容器还未初始化完成,此时WebServer就开始接收网络请求,那么网络请求就无法得到合理的处理.
如何让WebServer对象实例化后根据需要,适时地接收网络请求?
这里的适时指的应该是spring容器完成初始化后.
答案就是看情况,根据具体的实现类来完成hack.

TomcatWebServer对象在创建后,此时tomcat已经初始化完成.
此时TomcatWebServer对象将tomcat的Service的Connector移除了,导致无法tomcat无法接收网络请求;
在WebServer.start方法被调用时,再将Connector归还.

```text
public TomcatWebServer(Tomcat tomcat, boolean autoStart, Shutdown shutdown) {
    Assert.notNull(tomcat, "Tomcat Server must not be null");
    this.tomcat = tomcat;
    this.autoStart = autoStart;
    this.gracefulShutdown = (shutdown == Shutdown.GRACEFUL) ? new GracefulShutdown(tomcat) : null;
    initialize();
}

// 防止Tomcat.start被调用完且此时spring容器未完成启动就提前接收网络请求,这里先临时移除connectors
private void initialize() throws WebServerException {
    synchronized (this.monitor) {
        try {
            addInstanceIdToEngineName();
            Context context = findContext();
            context.addLifecycleListener((event) -> {
                if (context.equals(event.getSource()) && Lifecycle.START_EVENT.equals(event.getType())) {
                    // Remove service connectors so that protocol binding doesn't happen when the service is started.
                    // 防止Tomcat.start被调用完且此时spring容器未完成启动就提前接收网络请求,这里先临时移除connectors
                    removeServiceConnectors();
                }
            });

            // Start the server to trigger initialization listeners
            this.tomcat.start();

            // We can re-throw failure exception directly in the main thread
            rethrowDeferredStartupExceptions();

            try {
                ContextBindings.bindClassLoader(context, context.getNamingToken(), getClass().getClassLoader());
            } catch (NamingException ex) {
                // Naming is not enabled. Continue
            }

            // Unlike Jetty, all Tomcat threads are daemon threads. We create a blocking non-daemon to stop immediate shutdown
            startDaemonAwaitThread();
        }
        catch (Exception ex) {
            stopSilently();
            destroySilently();
            throw new WebServerException("Unable to start embedded Tomcat", ex);
        }
    }
}

private void removeServiceConnectors() {
    for (Service service : this.tomcat.getServer().findServices()) {
        Connector[] connectors = service.findConnectors().clone();
        // Connector被临时保存起来
        this.serviceConnectors.put(service, connectors);
        for (Connector connector : connectors) {
            service.removeConnector(connector);
        }
    }
}

// WebServer启动时,恢复之前被移除且临时保存起来的Connector,这样就可以接收网络请求了.
public void start() throws WebServerException {
    synchronized (this.monitor) {
        if (this.started) {
            return;
        }
        try {
            // 恢复之前被移除且临时保存起来的Connector
            addPreviouslyRemovedConnectors();
            Connector connector = this.tomcat.getConnector();
            if (connector != null && this.autoStart) {
                performDeferredLoadOnStartup();
            }
            checkThatConnectorsHaveStarted();
            this.started = true;
            logger.info("Tomcat started on port(s): " + getPortsDescription(true) + " with context path '"
                    + getContextPath() + "'");
        } catch (ConnectorStartFailedException ex) {
            stopSilently();
            throw ex;
        } catch (Exception ex) {
            PortInUseException.throwIfPortBindingException(ex, () -> this.tomcat.getConnector().getPort());
            throw new WebServerException("Unable to start embedded Tomcat server", ex);
        } finally {
            Context context = findContext();
            ContextBindings.unbindClassLoader(context, context.getNamingToken(), getClass().getClassLoader());
        }
    }
}

private void addPreviouslyRemovedConnectors() {
    Service[] services = this.tomcat.getServer().findServices();
    for (Service service : services) {
        Connector[] connectors = this.serviceConnectors.get(service);
        if (connectors != null) {
            for (Connector connector : connectors) {
                service.addConnector(connector);
                if (!this.autoStart) {
                    stopProtocolHandler(connector);
                }
            }
            this.serviceConnectors.remove(service);
        }
    }
}
```