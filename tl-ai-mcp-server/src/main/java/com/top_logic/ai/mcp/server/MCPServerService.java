/*
 * Copyright (c) 2025 My Company. All Rights Reserved
 */
package com.top_logic.ai.mcp.server;

import java.time.Duration;

import com.top_logic.basic.ConfigurationError;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.annotation.Name;
import com.top_logic.basic.config.annotation.defaults.IntDefault;
import com.top_logic.basic.config.annotation.defaults.LongDefault;
import com.top_logic.basic.config.annotation.defaults.StringDefault;
import com.top_logic.basic.module.ConfiguredManagedClass;

import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider;
import io.modelcontextprotocol.spec.McpServerTransportProvider;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.Servlet;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;

/**
 * TopLogic service that provides an MCP (Model Context Protocol) server for exposing
 * application-specific APIs to AI models via HTTP/SSE transport.
 *
 * <p>
 * This service initializes an MCP server during application startup using HTTP Server-Sent
 * Events (SSE) transport and ensures proper cleanup during shutdown. The server can be
 * configured through the TopLogic application configuration.
 * </p>
 *
 * <p>
 * The service exposes two HTTP endpoints:
 * <ul>
 * <li>SSE endpoint (default: /mcp/sse) - For server-to-client event streaming</li>
 * <li>Message endpoint (default: /mcp/message) - For client-to-server messages</li>
 * </ul>
 * </p>
 *
 * @author Bernhard Haumacher
 */
public class MCPServerService extends ConfiguredManagedClass<MCPServerService.Config<?>> {

	/**
	 * Configuration interface for {@link MCPServerService}.
	 */
	public interface Config<I extends MCPServerService> extends ConfiguredManagedClass.Config<I> {

		/** Configuration property name for server name. */
		String SERVER_NAME = "server-name";

		/** Configuration property name for server version. */
		String SERVER_VERSION = "server-version";

		/** Configuration property name for base URL. */
		String BASE_URL = "base-url";

		/** Configuration property name for message endpoint path. */
		String MESSAGE_ENDPOINT = "message-endpoint";

		/** Configuration property name for SSE endpoint path. */
		String SSE_ENDPOINT = "sse-endpoint";

		/** Configuration property name for keep-alive interval in seconds. */
		String KEEP_ALIVE_INTERVAL = "keep-alive-interval";

		/**
		 * The name of the MCP server.
		 */
		@Name(SERVER_NAME)
		@StringDefault("TopLogic MCP Server")
		String getServerName();

		/**
		 * The version of the MCP server.
		 */
		@Name(SERVER_VERSION)
		@StringDefault("1.0.0")
		String getServerVersion();

		/**
		 * The base URL for the MCP server endpoints.
		 *
		 * <p>Default: "" (empty, uses servlet context path)</p>
		 */
		@Name(BASE_URL)
		@StringDefault("")
		String getBaseUrl();

		/**
		 * The path for the message endpoint (client-to-server messages).
		 *
		 * <p>Default: "/mcp/message"</p>
		 */
		@Name(MESSAGE_ENDPOINT)
		@StringDefault("/mcp/message")
		String getMessageEndpoint();

		/**
		 * The path for the SSE endpoint (server-to-client events).
		 *
		 * <p>Default: "/mcp/sse"</p>
		 */
		@Name(SSE_ENDPOINT)
		@StringDefault("/mcp/sse")
		String getSseEndpoint();

		/**
		 * Keep-alive interval in seconds for SSE connections.
		 *
		 * <p>Default: 30 seconds</p>
		 */
		@Name(KEEP_ALIVE_INTERVAL)
		@LongDefault(30)
		long getKeepAliveInterval();
	}

	private static volatile MCPServerService _instance;

	private McpSyncServer _mcpServer;

	private HttpServletSseServerTransportProvider _transportProvider;

	/**
	 * Creates a new {@link MCPServerService} from configuration.
	 *
	 * @param context
	 *        The instantiation context for error reporting.
	 * @param config
	 *        The service configuration.
	 */
	public MCPServerService(InstantiationContext context, Config<?> config) {
		super(context, config);
	}

	/**
	 * Returns the singleton instance of the MCP server service.
	 *
	 * @return The service instance, or {@code null} if not yet started.
	 */
	public static MCPServerService getInstance() {
		return _instance;
	}

	@Override
	protected void startUp() {
		super.startUp();

		try {
			// Register singleton instance
			_instance = this;

			Config<?> config = getConfig();

			// Create HTTP SSE transport provider
			_transportProvider = HttpServletSseServerTransportProvider.builder()
				.jsonMapper(new JacksonMcpJsonMapper(new ObjectMapper()))
				.baseUrl(config.getBaseUrl())
				.messageEndpoint(config.getMessageEndpoint())
				.sseEndpoint(config.getSseEndpoint())
				.keepAliveInterval(Duration.ofSeconds(config.getKeepAliveInterval()))
				.build();

			// Initialize the servlet - required for HttpServlet-based transport
			try {
				_transportProvider.init(new ServletConfigAdapter());
			} catch (ServletException ex) {
				throw new ConfigurationError("Failed to initialize MCP transport servlet: " + ex.getMessage(), ex);
			}

			// Create MCP server instance with sync API
			_mcpServer = McpServer.sync(_transportProvider)
				.serverInfo(config.getServerName(), config.getServerVersion())
				.build();

			// Configure the server with application-specific capabilities
			configureServer(_mcpServer);

		} catch (Exception ex) {
			throw new ConfigurationError("Failed to start MCP server: " + ex.getMessage(), ex);
		}
	}

	/**
	 * Configures the MCP server with application-specific tools, resources, and prompts.
	 *
	 * <p>
	 * Override or extend this method to register custom MCP capabilities for your application.
	 * </p>
	 *
	 * @param server
	 *        The MCP server to configure.
	 */
	protected void configureServer(McpSyncServer server) {
		// Default implementation - to be extended by subclasses or application code
		// Example using McpServerFeatures:
		// var toolSpec = new McpServerFeatures.SyncToolSpecification(
		//     new Tool("myTool", "Description", schema),
		//     (exchange, arguments) -> new CallToolResult(result, false)
		// );
		// server.addTool(toolSpec);
	}

	@Override
	protected void shutDown() {
		try {
			// Unregister singleton instance
			_instance = null;

			// Clean up MCP server resources
			if (_mcpServer != null) {
				try {
					_mcpServer.close();
				} catch (Exception ex) {
					// Log but don't fail shutdown
					System.err.println("Error closing MCP server: " + ex.getMessage());
				}
				_mcpServer = null;
			}

			// Clean up transport provider
			if (_transportProvider != null) {
				try {
					_transportProvider.destroy();
				} catch (Exception ex) {
					// Log but don't fail shutdown
					System.err.println("Error destroying MCP transport: " + ex.getMessage());
				}
				_transportProvider = null;
			}
		} finally {
			super.shutDown();
		}
	}

	/**
	 * Returns the MCP server instance.
	 *
	 * @return The MCP server, or {@code null} if not started.
	 */
	public McpSyncServer getMCPServer() {
		return _mcpServer;
	}

	/**
	 * Returns the HTTP servlet transport provider.
	 *
	 * <p>
	 * The transport provider is an HttpServlet that handles MCP protocol communication.
	 * This can be registered with the servlet container to expose the MCP endpoints.
	 * </p>
	 *
	 * @return The transport provider servlet, or {@code null} if not started.
	 */
	public Servlet getTransportServlet() {
		return _transportProvider;
	}

	/**
	 * Simple ServletConfig adapter for initializing the MCP transport servlet.
	 */
	private static class ServletConfigAdapter implements ServletConfig {

		@Override
		public String getServletName() {
			return "MCPServerTransport";
		}

		@Override
		public jakarta.servlet.ServletContext getServletContext() {
			// Not needed for basic initialization
			return null;
		}

		@Override
		public String getInitParameter(String name) {
			return null;
		}

		@Override
		public java.util.Enumeration<String> getInitParameterNames() {
			return java.util.Collections.emptyEnumeration();
		}
	}
}
