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

