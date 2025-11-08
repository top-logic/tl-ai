/*
 * Copyright (c) 2025 My Company. All Rights Reserved
 */
package com.top_logic.ai.mcp.server;

import java.io.IOException;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Servlet wrapper for the MCP (Model Context Protocol) server transport.
 *
 * <p>
 * This servlet acts as a bridge between the servlet container and the {@link MCPServerService}.
 * It delegates all HTTP requests to the underlying MCP transport servlet provided by the service.
 * </p>
 *
 * <p>
 * This servlet should be registered in web.xml to handle MCP protocol endpoints:
 * </p>
 * <pre>
 * &lt;servlet&gt;
 *   &lt;servlet-name&gt;MCPServlet&lt;/servlet-name&gt;
 *   &lt;servlet-class&gt;com.top_logic.ai.mcp.server.MCPServlet&lt;/servlet-class&gt;
 *   &lt;async-supported&gt;true&lt;/async-supported&gt;
 * &lt;/servlet&gt;
 * &lt;servlet-mapping&gt;
 *   &lt;servlet-name&gt;MCPServlet&lt;/servlet-name&gt;
 *   &lt;url-pattern&gt;/mcp/*&lt;/url-pattern&gt;
 * &lt;/servlet-mapping&gt;
 * </pre>
 *
 * @author Bernhard Haumacher
 */
public class MCPServlet extends HttpServlet {

	@Override
	protected void service(HttpServletRequest req, HttpServletResponse resp)
			throws ServletException, IOException {

		// Get the MCP server service instance
		MCPServerService service = MCPServerService.getInstance();

		if (service == null || !service.isStarted()) {
			resp.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE,
				"MCP Server service is not available");
			return;
		}

		// Delegate to the transport servlet
		jakarta.servlet.Servlet transportServlet = service.getTransportServlet();
		if (transportServlet == null) {
			resp.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE,
				"MCP transport is not initialized");
			return;
		}

		// Forward request to the MCP transport servlet
		transportServlet.service(req, resp);
	}
}
