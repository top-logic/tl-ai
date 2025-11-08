/*
 * Copyright (c) 2025 My Company. All Rights Reserved
 */
package com.top_logic.ai.mcp.server;

import com.top_logic.basic.ConfigurationError;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.PolymorphicConfiguration;
import com.top_logic.basic.config.annotation.Name;
import com.top_logic.basic.config.annotation.defaults.StringDefault;
import com.top_logic.basic.module.ConfiguredManagedClass;

import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpServerTransportProvider;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * TopLogic service that provides an MCP (Model Context Protocol) server for exposing
 * application-specific APIs to AI models.
 *
 * <p>
 * This service initializes an MCP server during application startup and ensures proper
 * cleanup during shutdown. The server can be configured through the TopLogic application
 * configuration.
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
	}

	private McpSyncServer _mcpServer;

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

	@Override
	protected void startUp() {
		super.startUp();

		try {
			Config<?> config = getConfig();

			// Create transport provider (stdio mode)
			McpServerTransportProvider transportProvider =
				new StdioServerTransportProvider(new JacksonMcpJsonMapper(new ObjectMapper()));

			// Create MCP server instance with sync API
			_mcpServer = McpServer.sync(transportProvider)
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
}
