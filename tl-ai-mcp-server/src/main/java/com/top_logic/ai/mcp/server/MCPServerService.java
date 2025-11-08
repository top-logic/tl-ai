/*
 * Copyright (c) 2025 My Company. All Rights Reserved
 */
package com.top_logic.ai.mcp.server;

import java.time.Duration;

import jakarta.servlet.Servlet;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.annotation.Name;
import com.top_logic.basic.config.annotation.defaults.LongDefault;
import com.top_logic.basic.config.annotation.defaults.StringDefault;
import com.top_logic.basic.module.ConfiguredManagedClass;
import com.top_logic.basic.module.ServiceDependencies;
import com.top_logic.basic.module.services.ServletContextService;

import com.top_logic.ai.mcp.server.resources.ModelModulesResource;
import com.top_logic.ai.mcp.server.resources.ModuleTypesResource;
import com.top_logic.ai.mcp.server.resources.TypePartsResource;

import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider;

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
 * The service exposes two HTTP endpoints (registered in web-fragment.xml under /mcp/*):
 * <ul>
 * <li>SSE endpoint: /mcp/sse - For server-to-client event streaming</li>
 * <li>Message endpoint: /mcp/message - For client-to-server messages</li>
 * </ul>
 * </p>
 *
 * <p>
 * The service uses {@link ServletContextService} to obtain the application's context path
 * at startup, ensuring that endpoint URLs sent to clients include the correct context path
 * regardless of the deployment configuration.
 * </p>
 *
 * @author Bernhard Haumacher
 */
@ServiceDependencies({
	ServletContextService.Module.class
})
public class MCPServerService extends ConfiguredManagedClass<MCPServerService.Config<?>> {

	/**
	 * Base URL prefix for MCP endpoints.
	 *
	 * <p>
	 * Set to empty string because the full base URL (including context path) is
	 * constructed dynamically at runtime using {@link ServletContextService}.
	 * </p>
	 */
	private static final String BASE_URL = "";

	/** Path for the SSE endpoint (must match web.xml servlet mapping /mcp/*). */
	private static final String SSE_ENDPOINT = "/mcp/sse";

	/** Path for the message endpoint (must match web.xml servlet mapping /mcp/*). */
	private static final String MESSAGE_ENDPOINT = "/mcp/message";

	/**
	 * Configuration interface for {@link MCPServerService}.
	 */
	public interface Config<I extends MCPServerService> extends ConfiguredManagedClass.Config<I> {

		/**
		 * Configuration property name for server name.
		 *
		 * @see #getServerName()
		 */
		String SERVER_NAME = "server-name";

		/**
		 * Configuration property name for server version.
		 *
		 * @see #getServerVersion()
		 */
		String SERVER_VERSION = "server-version";

		/**
		 * Configuration property name for keep-alive interval in seconds.
		 *
		 * @see #getKeepAliveInterval()
		 */
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

			// Get the application context path from ServletContextService
			String contextPath = ServletContextService.getInstance().getServletContext().getContextPath();

			// Create HTTP SSE transport provider with fixed endpoints matching web-fragment.xml
			// The baseUrl includes the context path so clients receive the correct absolute endpoint URLs
			_transportProvider = HttpServletSseServerTransportProvider.builder()
				.jsonMapper(new JacksonMcpJsonMapper(new ObjectMapper()))
				.baseUrl(contextPath + BASE_URL)
				.messageEndpoint(MESSAGE_ENDPOINT)
				.sseEndpoint(SSE_ENDPOINT)
				.keepAliveInterval(Duration.ofSeconds(config.getKeepAliveInterval()))
				.build();

			// Initialize the servlet - required for HttpServlet-based transport
			try {
				_transportProvider.init(new ServletConfigAdapter());
			} catch (ServletException ex) {
				throw new RuntimeException("Failed to initialize MCP transport servlet: " + ex.getMessage(), ex);
			}

			// Create MCP server instance with sync API
			_mcpServer = McpServer.sync(_transportProvider)
				.serverInfo(config.getServerName(), config.getServerVersion())
				.build();

			// Configure the server with application-specific capabilities
			configureServer(_mcpServer);

		} catch (RuntimeException ex) {
			throw ex;
		} catch (Exception ex) {
			throw new RuntimeException("Failed to start MCP server: " + ex.getMessage(), ex);
		}
	}

	/**
	 * Configures the MCP server with application-specific tools, resources, and prompts.
	 *
	 * <p>
	 * Override or extend this method to register custom MCP capabilities for your application.
	 * </p>
	 *
	 * <p>
	 * Default implementation registers resources for TopLogic model exploration:
	 * </p>
	 * <ul>
	 * <li>{@link ModelModulesResource} - Lists available data model modules</li>
	 * <li>{@link ModuleTypesResource} - Lists types within a specific module (dynamic resource template)</li>
	 * <li>{@link TypePartsResource} - Lists parts within a specific type (dynamic resource template)</li>
	 * </ul>
	 *
	 * @param server
	 *        The MCP server to configure.
	 */
	protected void configureServer(McpSyncServer server) {
		// Register resource for listing TopLogic model modules
		server.addResource(ModelModulesResource.createSpecification());

		// Register resource template for listing types in a specific module
		server.addResourceTemplate(ModuleTypesResource.createSpecification());

		// Register resource template for listing parts in a specific type
		server.addResourceTemplate(TypePartsResource.createSpecification());
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
