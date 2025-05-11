# 关于tomcat的学习

## tomcat中的概念

## org.apache.catalina.Container

A Container is an object that can execute requests received from a client, and return responses based on those requests.
A Container may optionally support a pipeline of Valves that process the request in an order configured at runtime, by implementing the Pipeline interface as well.

Containers will exist at several conceptual levels within Catalina. The following examples represent common cases:
1. Engine - Representation of the entire Catalina servlet engine, most likely containing one or more subcontainers that are either Host or Context implementations, or other custom groups.
2. Host - Representation of a virtual host containing a number of Contexts.
3. Context - Representation of a single ServletContext, which will typically contain one or more Wrappers for the supported servlets.
4. Wrapper - Representation of an individual servlet definition (which may support multiple servlet instances if the servlet itself implements SingleThreadModel).

备注:
1. 概念的层级关系是Engine->Host->Context->Wrapper
2. 在$tomcatHome/conf/server.xml中可以找到Engine/Host概念对应的元素
3. Context对应应用的web.xml文件
4. 还有一些概念(比如:Server/Service/Connector/Realm/Host/Valve)都可以在$tomcatHome/conf/server.xml中直观的看到



## tomcat调用主线

NioEndpoint会创建接收请求和处理请求的线程

```text
org.apache.tomcat.util.net.NioEndpoint

public void startInternal() throws Exception {

    if (!running) {
        // ...

        // 启动轮询线程,用来接收已经建立连接的socket的新数据
        poller = new Poller();
        Thread pollerThread = new Thread(poller, getName() + "-ClientPoller");
        pollerThread.setPriority(threadPriority);
        pollerThread.setDaemon(true);
        pollerThread.start();
        
        // 启动Accept线程,用来接收新的网络连接
        startAcceptorThread();
    }
}
```

```text
org.apache.tomcat.util.net.Acceptor

public void run() {

    // ...

    // Loop until we receive a shutdown command
    while (!stopCalled) {

        // Loop if endpoint is paused
        while (endpoint.isPaused() && !stopCalled) {
            state = AcceptorState.PAUSED;
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                // Ignore
            }
        }

        if (stopCalled) {
            break;
        }
        state = AcceptorState.RUNNING;

        try {
            //if we have reached max connections, wait
            endpoint.countUpOrAwaitConnection();

            // Endpoint might have been paused while waiting for latch
            // If that is the case, don't accept new connections
            if (endpoint.isPaused()) {
                continue;
            }

            U socket = null;
            try {
                // Accept the next incoming connection from the server socket (ServerSock.accept方法被封装到NioEndpoint类里了)
                socket = endpoint.serverSocketAccept();
            } catch (Exception ioe) {
                // ...
            }
            // Successful accept, reset the error delay
            errorDelay = 0;

            // Configure the socket
            if (!stopCalled && !endpoint.isPaused()) {
                // setSocketOptions() will hand the socket off to an appropriate processor if successful (将新建立连接socket让Poller处理)
                if (!endpoint.setSocketOptions(socket)) {
                    endpoint.closeSocket(socket);
                }
            } else {
                endpoint.destroySocket(socket);
            }
        } catch (Throwable t) {
            // ...
        }
    }
    // ...
}
```

```text
org.apache.tomcat.util.net.NioEndpoint.Poller


public void run() {
    // Loop until destroy() is called
    while (true) {

        boolean hasEvents = false;

        try {
            if (!close) {
                // 新建立连接socket,会以PollerEvent的形式添加到待处理队列里
                hasEvents = events();
                if (wakeupCounter.getAndSet(-1) > 0) {
                    // If we are here, means we have other stuff to do
                    // Do a non blocking select
                    keyCount = selector.selectNow();
                } else {
                    keyCount = selector.select(selectorTimeout);
                }
                wakeupCounter.set(0);
            }
            if (close) {
                events();
                timeout(0, false);
                try {
                    selector.close();
                } catch (IOException ioe) {
                    log.error(sm.getString("endpoint.nio.selectorCloseFail"), ioe);
                }
                break;
            }
            // Either we timed out or we woke up, process events first
            if (keyCount == 0) {
                hasEvents = (hasEvents | events());
            }
        } catch (Throwable x) {
            ExceptionUtils.handleThrowable(x);
            log.error(sm.getString("endpoint.nio.selectorLoopError"), x);
            continue;
        }

        Iterator<SelectionKey> iterator = keyCount > 0 ? selector.selectedKeys().iterator() : null;
        // Walk through the collection of ready keys and dispatch any active event.
        while (iterator != null && iterator.hasNext()) {
            SelectionKey sk = iterator.next();
            iterator.remove();
            NioSocketWrapper socketWrapper = (NioSocketWrapper) sk.attachment();
            // Attachment may be null if another thread has called cancelledKey()
            if (socketWrapper != null) {
                processKey(sk, socketWrapper);
            }
        }

        // Process timeouts
        timeout(keyCount,hasEvents);
    }

    getStopLatch().countDown();
}


protected void processKey(SelectionKey sk, NioSocketWrapper socketWrapper) {
    try {
        if (close) {
            cancelledKey(sk, socketWrapper);
        } else if (sk.isValid() && socketWrapper != null) {
            if (sk.isReadable() || sk.isWritable()) {
                if (socketWrapper.getSendfileData() != null) {
                    processSendfile(sk, socketWrapper, false);
                } else {
                    // This is a must, so that we don't have multiple threads messing with the socket
                    unreg(sk, socketWrapper, sk.readyOps());
                    boolean closeSocket = false;
                    // Read goes before write
                    if (sk.isReadable()) {
                        if (socketWrapper.readOperation != null) {
                            if (!socketWrapper.readOperation.process()) {
                                closeSocket = true;
                            }
                        } else if (!processSocket(socketWrapper, SocketEvent.OPEN_READ, true)) { // NioEndpoint.Poller调用AbstractEndpoint的统一接口
                            closeSocket = true;
                        }
                    }
                    if (!closeSocket && sk.isWritable()) {
                        if (socketWrapper.writeOperation != null) {
                            if (!socketWrapper.writeOperation.process()) {
                                closeSocket = true;
                            }
                        } else if (!processSocket(socketWrapper, SocketEvent.OPEN_WRITE, true)) {
                            closeSocket = true;
                        }
                    }
                    if (closeSocket) {
                        cancelledKey(sk, socketWrapper);
                    }
                }
            }
        } else {
            // Invalid key
            cancelledKey(sk, socketWrapper);
        }
    } catch (CancelledKeyException ckx) {
        cancelledKey(sk, socketWrapper);
    } catch (Throwable t) {
        ExceptionUtils.handleThrowable(t);
        log.error(sm.getString("endpoint.nio.keyProcessingError"), t);
    }
}
```


```text
org.apache.tomcat.util.net.AbstractEndpoint


public boolean processSocket(SocketWrapperBase<S> socketWrapper, SocketEvent event, boolean dispatch) {
    try {
        if (socketWrapper == null) {
            return false;
        }
        SocketProcessorBase<S> sc = null;
        if (processorCache != null) {
            sc = processorCache.pop();
        }
        // 如果不能复用,则创建新的SocketProcessorBase
        if (sc == null) {
            sc = createSocketProcessor(socketWrapper, event);
        } else {
            sc.reset(socketWrapper, event);
        }
        Executor executor = getExecutor();
        if (dispatch && executor != null) {
            // 通过线程池来处理socket请求
            executor.execute(sc);
        } else {
            sc.run();
        }
    } catch (RejectedExecutionException ree) {
        getLog().warn(sm.getString("endpoint.executor.fail", socketWrapper) , ree);
        return false;
    } catch (Throwable t) {
        ExceptionUtils.handleThrowable(t);
        // This means we got an OOM or similar creating a thread, or that the pool and its queue are full
        getLog().error(sm.getString("endpoint.process.fail"), t);
        return false;
    }
    return true;
}

```


```text
org.apache.coyote.http11.Http11Processor.service


public SocketState service(SocketWrapperBase<?> socketWrapper)
    throws IOException {
    RequestInfo rp = request.getRequestProcessor();
    rp.setStage(org.apache.coyote.Constants.STAGE_PARSE);

    // Setting up the I/O
    setSocketWrapper(socketWrapper);

    // Flags
    keepAlive = true;
    openSocket = false;
    readComplete = true;
    boolean keptAlive = false;
    SendfileState sendfileState = SendfileState.DONE;

    while (!getErrorState().isError() && keepAlive && !isAsync() && upgradeToken == null && sendfileState == SendfileState.DONE && !protocol.isPaused()) {

        // Parsing the request header
        try {
            if (!inputBuffer.parseRequestLine(keptAlive, protocol.getConnectionTimeout(),
                    protocol.getKeepAliveTimeout())) {
                if (inputBuffer.getParsingRequestLinePhase() == -1) {
                    return SocketState.UPGRADING;
                } else if (handleIncompleteRequestLineRead()) {
                    break;
                }
            }

            // Process the Protocol component of the request line
            // Need to know if this is an HTTP 0.9 request before trying to parse headers.
            prepareRequestProtocol();

            if (protocol.isPaused()) {
                // 503 - Service unavailable
                response.setStatus(503);
                setErrorState(ErrorState.CLOSE_CLEAN, null);
            } else {
                keptAlive = true;
                // Set this every time in case limit has been changed via JMX
                request.getMimeHeaders().setLimit(protocol.getMaxHeaderCount());
                // Don't parse headers for HTTP/0.9
                if (!http09 && !inputBuffer.parseHeaders()) {
                    // We've read part of the request, don't recycle it
                    // instead associate it with the socket
                    openSocket = true;
                    readComplete = false;
                    break;
                }
                if (!protocol.getDisableUploadTimeout()) {
                    socketWrapper.setReadTimeout(protocol.getConnectionUploadTimeout());
                }
            }
        } catch (IOException e) {
            if (log.isDebugEnabled()) {
                log.debug(sm.getString("http11processor.header.parse"), e);
            }
            setErrorState(ErrorState.CLOSE_CONNECTION_NOW, e);
            break;
        } catch (Throwable t) {
            ExceptionUtils.handleThrowable(t);
            UserDataHelper.Mode logMode = userDataHelper.getNextMode();
            if (logMode != null) {
                String message = sm.getString("http11processor.header.parse");
                switch (logMode) {
                    case INFO_THEN_DEBUG:
                        message += sm.getString("http11processor.fallToDebug");
                        //$FALL-THROUGH$
                    case INFO:
                        log.info(message, t);
                        break;
                    case DEBUG:
                        log.debug(message, t);
                }
            }
            // 400 - Bad Request
            response.setStatus(400);
            setErrorState(ErrorState.CLOSE_CLEAN, t);
        }

        // Has an upgrade been requested?
        if (isConnectionToken(request.getMimeHeaders(), "upgrade")) {
            // Check the protocol
            String requestedProtocol = request.getHeader("Upgrade");

            UpgradeProtocol upgradeProtocol = protocol.getUpgradeProtocol(requestedProtocol);
            if (upgradeProtocol != null) {
                if (upgradeProtocol.accept(request)) {
                    response.setStatus(HttpServletResponse.SC_SWITCHING_PROTOCOLS);
                    response.setHeader("Connection", "Upgrade");
                    response.setHeader("Upgrade", requestedProtocol);
                    action(ActionCode.CLOSE,  null);
                    getAdapter().log(request, response, 0);

                    InternalHttpUpgradeHandler upgradeHandler = upgradeProtocol.getInternalUpgradeHandler(socketWrapper, getAdapter(), cloneRequest(request));
                    UpgradeToken upgradeToken = new UpgradeToken(upgradeHandler, null, null, requestedProtocol);
                    action(ActionCode.UPGRADE, upgradeToken);
                    return SocketState.UPGRADING;
                }
            }
        }

        if (getErrorState().isIoAllowed()) {
            // Setting up filters, and parse some request headers
            rp.setStage(org.apache.coyote.Constants.STAGE_PREPARE);
            try {
                prepareRequest();
            } catch (Throwable t) {
                ExceptionUtils.handleThrowable(t);
                if (log.isDebugEnabled()) {
                    log.debug(sm.getString("http11processor.request.prepare"), t);
                }
                // 500 - Internal Server Error
                response.setStatus(500);
                setErrorState(ErrorState.CLOSE_CLEAN, t);
            }
        }

        int maxKeepAliveRequests = protocol.getMaxKeepAliveRequests();
        if (maxKeepAliveRequests == 1) {
            keepAlive = false;
        } else if (maxKeepAliveRequests > 0 &&
                socketWrapper.decrementKeepAlive() <= 0) {
            keepAlive = false;
        }

        // Process the request in the adapter
        if (getErrorState().isIoAllowed()) {
            try {
                rp.setStage(org.apache.coyote.Constants.STAGE_SERVICE);
                getAdapter().service(request, response);
                // Handle when the response was committed before a serious error occurred.  Throwing a ServletException should both set the status to 500 and set the errorException.
                // If we fail here, then the response is likely already committed, so we can't try and set headers.
                if(keepAlive && !getErrorState().isError() && !isAsync() &&
                        statusDropsConnection(response.getStatus())) {
                    setErrorState(ErrorState.CLOSE_CLEAN, null);
                }
            } catch (InterruptedIOException e) {
                setErrorState(ErrorState.CLOSE_CONNECTION_NOW, e);
            } catch (HeadersTooLargeException e) {
                log.error(sm.getString("http11processor.request.process"), e);
                // The response should not have been committed but check it anyway to be safe
                if (response.isCommitted()) {
                    setErrorState(ErrorState.CLOSE_NOW, e);
                } else {
                    response.reset();
                    response.setStatus(500);
                    setErrorState(ErrorState.CLOSE_CLEAN, e);
                    response.setHeader("Connection", "close"); // TODO: Remove
                }
            } catch (Throwable t) {
                ExceptionUtils.handleThrowable(t);
                log.error(sm.getString("http11processor.request.process"), t);
                // 500 - Internal Server Error
                response.setStatus(500);
                setErrorState(ErrorState.CLOSE_CLEAN, t);
                getAdapter().log(request, response, 0);
            }
        }

        // Finish the handling of the request
        rp.setStage(org.apache.coyote.Constants.STAGE_ENDINPUT);
        if (!isAsync()) {
            // If this is an async request then the request ends when it has been completed. The AsyncContext is responsible for calling endRequest() in that case.
            endRequest();
        }
        rp.setStage(org.apache.coyote.Constants.STAGE_ENDOUTPUT);

        // If there was an error, make sure the request is counted as and error, and update the statistics counter
        if (getErrorState().isError()) {
            response.setStatus(500);
        }

        if (!isAsync() || getErrorState().isError()) {
            request.updateCounters();
            if (getErrorState().isIoAllowed()) {
                inputBuffer.nextRequest();
                outputBuffer.nextRequest();
            }
        }

        if (!protocol.getDisableUploadTimeout()) {
            int connectionTimeout = protocol.getConnectionTimeout();
            if(connectionTimeout > 0) {
                socketWrapper.setReadTimeout(connectionTimeout);
            } else {
                socketWrapper.setReadTimeout(0);
            }
        }

        rp.setStage(org.apache.coyote.Constants.STAGE_KEEPALIVE);

        sendfileState = processSendfile(socketWrapper);
    }

    rp.setStage(org.apache.coyote.Constants.STAGE_ENDED);

    if (getErrorState().isError() || (protocol.isPaused() && !isAsync())) {
        return SocketState.CLOSED;
    } else if (isAsync()) {
        return SocketState.LONG;
    } else if (isUpgrade()) {
        return SocketState.UPGRADING;
    } else {
        if (sendfileState == SendfileState.PENDING) {
            return SocketState.SENDFILE;
        } else {
            if (openSocket) {
                if (readComplete) {
                    return SocketState.OPEN;
                } else {
                    return SocketState.LONG;
                }
            } else {
                return SocketState.CLOSED;
            }
        }
    }
}
```


```text
org.apache.catalina.connector.CoyoteAdapter


public void service(org.apache.coyote.Request req, org.apache.coyote.Response res) throws Exception {

    Request request = (Request) req.getNote(ADAPTER_NOTES);
    Response response = (Response) res.getNote(ADAPTER_NOTES);

    if (request == null) {
        // Create objects
        request = connector.createRequest();
        request.setCoyoteRequest(req);
        response = connector.createResponse();
        response.setCoyoteResponse(res);

        // Link objects
        request.setResponse(response);
        response.setRequest(request);

        // Set as notes
        req.setNote(ADAPTER_NOTES, request);
        res.setNote(ADAPTER_NOTES, response);

        // Set query string encoding
        req.getParameters().setQueryStringCharset(connector.getURICharset());
    }

    if (connector.getXpoweredBy()) {
        response.addHeader("X-Powered-By", POWERED_BY);
    }

    boolean async = false;
    boolean postParseSuccess = false;

    req.getRequestProcessor().setWorkerThreadName(THREAD_NAME.get());

    try {
        // Parse and set Catalina and configuration specific request parameters
        postParseSuccess = postParseRequest(req, request, res, response);
        if (postParseSuccess) {
            //check valves if we support async
            request.setAsyncSupported(connector.getService().getContainer().getPipeline().isAsyncSupported());
            // Calling the container (>>>>>>> 入口)
            connector.getService().getContainer().getPipeline().getFirst().invoke(request, response);
        }
        if (request.isAsync()) {
            async = true;
            ReadListener readListener = req.getReadListener();
            if (readListener != null && request.isFinished()) {
                // Possible the all data may have been read during service() method so this needs to be checked here
                ClassLoader oldCL = null;
                try {
                    oldCL = request.getContext().bind(false, null);
                    if (req.sendAllDataReadEvent()) {
                        req.getReadListener().onAllDataRead();
                    }
                } finally {
                    request.getContext().unbind(false, oldCL);
                }
            }

            Throwable throwable = (Throwable) request.getAttribute(RequestDispatcher.ERROR_EXCEPTION);

            // If an async request was started, is not going to end once this container thread finishes and an error occurred, trigger the async error process
            if (!request.isAsyncCompleting() && throwable != null) {
                request.getAsyncContextInternal().setErrorState(throwable, true);
            }
        } else {
            request.finishRequest();
            response.finishResponse();
        }

    } catch (IOException e) {
        // Ignore
    } finally {
        AtomicBoolean error = new AtomicBoolean(false);
        res.action(ActionCode.IS_ERROR, error);

        if (request.isAsyncCompleting() && error.get()) {
            // Connection will be forcibly closed which will prevent completion happening at the usual point. Need to trigger call to onComplete() here.
            res.action(ActionCode.ASYNC_POST_PROCESS,  null);
            async = false;
        }

        // Access log
        if (!async && postParseSuccess) {
            // Log only if processing was invoked. If postParseRequest() failed, it has already logged it.
            Context context = request.getContext();
            Host host = request.getHost();
            // If the context is null, it is likely that the endpoint was shutdown, this connection closed and the request recycled in
            // a different thread. That thread will have updated the access log so it is OK not to update the access log here in that case.
            // The other possibility is that an error occurred early in processing and the request could not be mapped to a Context.
            // Log via the host or engine in that case.
            long time = System.currentTimeMillis() - req.getStartTime();
            if (context != null) {
                context.logAccess(request, response, time, false);
            } else if (response.isError()) {
                if (host != null) {
                    host.logAccess(request, response, time, false);
                } else {
                    connector.getService().getContainer().logAccess(
                            request, response, time, false);
                }
            }
        }

        req.getRequestProcessor().setWorkerThreadName(null);

        // Recycle the wrapper request and response
        if (!async) {
            updateWrapperErrorCount(request, response);
            request.recycle();
            response.recycle();
        }
    }
}
```


```text
java.lang.Thread.run
    -->> org.apache.tomcat.util.net.NioEndpoint.Poller.run
        -->> org.apache.tomcat.util.net.NioEndpoint.Poller.processKey
            -->> org.apache.tomcat.util.net.AbstractEndpoint.processSocket
                -->> org.apache.tomcat.util.net.SocketProcessorBase.run
                    -->> org.apache.tomcat.util.net.NioEndpoint.SocketProcessor.doRun
                        -->> org.apache.tomcat.util.net.AbstractEndpoint.Handler.process
                            -->> org.apache.coyote.AbstractProtocol.ConnectionHandler.process
                                -->> org.apache.coyote.AbstractProcessorLight.process(org.apache.coyote.Processor.process)
                                    -->> org.apache.coyote.http11.Http11Processor.service
                                        -->> org.apache.catalina.connector.CoyoteAdapter.service
                                            -->> connector.getService().getContainer().getPipeline().getFirst().invoke(request, response)                        
```

```text
org.apache.coyote.Adapter
    This represents the entry point in a coyote-based servlet container.
org.apache.catalina.connector.Connector
    Implementation of a Coyote connector.
org.apache.catalina.Service    
    A Service is a group of one or more Connectors that share a single Container to process their incoming requests. 
    This arrangement allows, for example, a non-SSL and SSL connector to share the same population of web apps.
```


```text
StandardPipeline
Valve.invoke


StandardEngineValve
    Select the appropriate child Host to process this request, based on the requested server name.
AccessLogValve
ErrorReportValve
StandardHostValve
    Select the appropriate child Context to process this request, based on the specified request URI. 
NonLoginAuthenticator
StandardContextValve
    Select the appropriate child Wrapper to process this request, based on the specified request URI.
        A Wrapper is a Container that represents an individual servlet definition from the deployment descriptor of the web application.
        StandardWrapper(Standard implementation of the Wrapper interface that represents an individual servlet definition. No child Containers are allowed, and the parent Container must be a Context.)
StandardWrapperValve    
    
```


```text
org.apache.catalina.core.StandardWrapperValve.invoke

    public void invoke(Request request, Response response) throws IOException, ServletException {

        // Initialize local variables we may need
        boolean unavailable = false;
        Throwable throwable = null;
        // This should be a Request attribute...
        long t1 = System.currentTimeMillis();
        requestCount.incrementAndGet();
        StandardWrapper wrapper = (StandardWrapper) getContainer();
        Servlet servlet = null;
        Context context = (Context) wrapper.getParent();

        // Check for the application being marked unavailable
        if (!context.getState().isAvailable()) {
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, sm.getString("standardContext.isUnavailable"));
            unavailable = true;
        }

        // Check for the servlet being marked unavailable
        if (!unavailable && wrapper.isUnavailable()) {
            container.getLogger().info(sm.getString("standardWrapper.isUnavailable", wrapper.getName()));
            checkWrapperAvailable(response, wrapper);
            unavailable = true;
        }

        // Allocate a servlet instance to process this request
        try {
            if (!unavailable) {
                servlet = wrapper.allocate();
            }
        } catch (UnavailableException e) {
            container.getLogger().error(sm.getString("standardWrapper.allocateException", wrapper.getName()), e);
            checkWrapperAvailable(response, wrapper);
        } catch (ServletException e) {
            container.getLogger().error(sm.getString("standardWrapper.allocateException", wrapper.getName()), StandardWrapper.getRootCause(e));
            throwable = e;
            exception(request, response, e);
        } catch (Throwable e) {
            ExceptionUtils.handleThrowable(e);
            container.getLogger().error(sm.getString("standardWrapper.allocateException", wrapper.getName()), e);
            throwable = e;
            exception(request, response, e);
            servlet = null;
        }

        MessageBytes requestPathMB = request.getRequestPathMB();
        DispatcherType dispatcherType = DispatcherType.REQUEST;
        if (request.getDispatcherType() == DispatcherType.ASYNC) {
            dispatcherType = DispatcherType.ASYNC;
        }
        request.setAttribute(Globals.DISPATCHER_TYPE_ATTR, dispatcherType);
        request.setAttribute(Globals.DISPATCHER_REQUEST_PATH_ATTR, requestPathMB);
        // Create the filter chain for this request (注意!!!! 每次请求都new一个ApplicationFilterChain)
        ApplicationFilterChain filterChain = ApplicationFilterFactory.createFilterChain(request, wrapper, servlet);

        // Call the filter chain for this request
        // NOTE: This also calls the servlet's service() method
        Container container = this.container;
        try {
            if ((servlet != null) && (filterChain != null)) {
                // Swallow output if needed
                if (context.getSwallowOutput()) {
                    try {
                        SystemLogHandler.startCapture();
                        if (request.isAsyncDispatching()) {
                            request.getAsyncContextInternal().doInternalDispatch();
                        } else {
                            filterChain.doFilter(request.getRequest(), response.getResponse());
                        }
                    } finally {
                        String log = SystemLogHandler.stopCapture();
                        if (log != null && log.length() > 0) {
                            context.getLogger().info(log);
                        }
                    }
                } else {
                    if (request.isAsyncDispatching()) {
                        request.getAsyncContextInternal().doInternalDispatch();
                    } else {
                        // 执行filterChain逻辑
                        filterChain.doFilter(request.getRequest(), response.getResponse());
                    }
                }
            }
        } catch (BadRequestException e) {
            if (container.getLogger().isDebugEnabled()) {
                container.getLogger().debug(sm.getString("standardWrapper.serviceException", wrapper.getName(), context.getName()), e);
            }
            throwable = e;
            exception(request, response, e, HttpServletResponse.SC_BAD_REQUEST);
        } catch (CloseNowException e) {
            if (container.getLogger().isDebugEnabled()) {
                container.getLogger().debug(sm.getString("standardWrapper.serviceException", wrapper.getName(), context.getName()), e);
            }
            throwable = e;
            exception(request, response, e);
        } catch (IOException e) {
            container.getLogger().error(sm.getString("standardWrapper.serviceException", wrapper.getName(), context.getName()), e);
            throwable = e;
            exception(request, response, e);
        } catch (UnavailableException e) {
            container.getLogger().error(sm.getString("standardWrapper.serviceException", wrapper.getName(), context.getName()), e);
            wrapper.unavailable(e);
            checkWrapperAvailable(response, wrapper);
            // Do not save exception in 'throwable', because we do not want to do exception(request, response, e) processing
        } catch (ServletException e) {
            Throwable rootCause = StandardWrapper.getRootCause(e);
            if (!(rootCause instanceof BadRequestException)) {
                container.getLogger().error(sm.getString("standardWrapper.serviceExceptionRoot", wrapper.getName(), context.getName(), e.getMessage()), rootCause);
            }
            throwable = e;
            exception(request, response, e);
        } catch (Throwable e) {
            ExceptionUtils.handleThrowable(e);
            container.getLogger().error(sm.getString("standardWrapper.serviceException", wrapper.getName(), context.getName()), e);
            throwable = e;
            exception(request, response, e);
        } finally {
            // ...
    }
```



## tomcat解析http报文

form-data的解析,主要是这个类: FileItemIteratorImpl