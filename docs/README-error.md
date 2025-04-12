# 关于tomcat的学习

## tomcat处理异常场景
org.apache.catalina.core.StandardHostValve#status

// 当http code码是正常的码,就不再处理
if (!response.isError()) {
return;
}
// 如果是异常码, 会找到默认的错误地址,即/error
if (errorPage == null) {
// Look for a default error page
errorPage = context.findErrorPage(0);
}


org.apache.catalina.core.StandardHostValve#custom

// 使用重定向调用错误地址的接口
RequestDispatcher rd = servletContext.getRequestDispatcher(errorPage.getLocation());

if (response.isCommitted()) {
// Response is committed - including the error page is the
// best we can do
rd.include(request.getRequest(), response.getResponse());
} else {
// Reset the response (keeping the real error code and message)
response.resetBuffer(true);
response.setContentLength(-1);

	rd.forward(request.getRequest(), response.getResponse());

	// If we forward, the response is suspended again
	response.setSuspended(false);
}