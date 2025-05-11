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

        Iterator<SelectionKey> iterator =
            keyCount > 0 ? selector.selectedKeys().iterator() : null;
        // Walk through the collection of ready keys and dispatch
        // any active event.
        while (iterator != null && iterator.hasNext()) {
            SelectionKey sk = iterator.next();
            iterator.remove();
            NioSocketWrapper socketWrapper = (NioSocketWrapper) sk.attachment();
            // Attachment may be null if another thread has called
            // cancelledKey()
            if (socketWrapper != null) {
                processKey(sk, socketWrapper);
            }
        }

        // Process timeouts
        timeout(keyCount,hasEvents);
    }

    getStopLatch().countDown();
}
```

```text
java.lang.Thread.run
    -->> org.apache.tomcat.util.net.NioEndpoint.Poller.run
        -->> org.apache.tomcat.util.net.NioEndpoint.Poller.timeout
            -->> org.apache.tomcat.util.net.AbstractEndpoint.processSocket
                -->> org.apache.tomcat.util.net.SocketProcessorBase.run
                    -->> org.apache.tomcat.util.net.NioEndpoint.SocketProcessor.doRun
                        -->> org.apache.tomcat.util.net.AbstractEndpoint.Handler.process
                            -->> org.apache.coyote.Processor.process
```


## tomcat解析http报文

form-data的解析,主要是这个类: FileItemIteratorImpl