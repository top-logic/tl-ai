/*
 * Copyright (c) 2025 My Company. All Rights Reserved
 */
package com.top_logic.ai.mcp.server;

import java.util.List;
import java.util.Map;

import com.top_logic.basic.config.AbstractConfiguredInstance;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.thread.ThreadContextManager;
import com.top_logic.model.search.expr.query.QueryExecutor;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * Implementation of {@link DynamicResource} that uses TL-Script expressions to dynamically
 * generate resource content.
 *
 * <p>
 * This class bridges the configuration-based resource template definition
 * ({@link ResourceTemplateConfig}) with the MCP server's resource handling mechanism.
 * It extracts parameters from the URI, passes them to the TL-Script expression,
 * and returns the computed content as an MCP resource.
 * </p>
 *
 * @author Bernhard Haumacher
 */
public class ConfigurableResourceTemplate extends AbstractConfiguredInstance<ResourceTemplateConfig>
		implements DynamicResource {

	private final UriPattern _uriPattern;

	/**
	 * Creates a {@link ConfigurableResourceTemplate}.
	 *
	 * @param context
	 *        The instantiation context for error reporting.
	 * @param config
	 *        The resource template configuration.
	 */
	public ConfigurableResourceTemplate(InstantiationContext context, ResourceTemplateConfig config) {
		super(context, config);
		_uriPattern = UriPattern.compile(config.getUriTemplate());
	}

	@Override
	public McpServerFeatures.SyncResourceTemplateSpecification createSpecification() {
		McpSchema.ResourceTemplate.Builder builder = McpSchema.ResourceTemplate.builder()
			.uriTemplate(getConfig().getUriTemplate())
			.name(getConfig().getName());

		// Add optional metadata
		if (getConfig().getTitle() != null) {
			builder.name(getConfig().getTitle());
		}

		if (getConfig().getDescription() != null) {
			builder.description(getConfig().getDescription());
		}

		if (getConfig().getMimeType() != null) {
			builder.mimeType(getConfig().getMimeType());
		}

		McpSchema.ResourceTemplate template = builder.build();

		return new McpServerFeatures.SyncResourceTemplateSpecification(
			template,
			this::handleReadRequest
		);
	}

	/**
	 * Handles requests to read this resource.
	 *
	 * <p>
	 * Sets up TopLogic thread context and delegates to {@link #readResource(McpSchema.ReadResourceRequest)}.
	 * </p>
	 *
	 * @param exchange
	 *        The MCP server exchange for interacting with the client.
	 * @param request
	 *        The read resource request containing the URI with parameters.
	 * @return The resource content computed by the TL-Script expression.
	 */
	private McpSchema.ReadResourceResult handleReadRequest(
			McpSyncServerExchange exchange,
			McpSchema.ReadResourceRequest request) {

		// Wrap in system interaction context to access TopLogic services
		return ThreadContextManager.inSystemInteraction(ConfigurableResourceTemplate.class,
			() -> readResource(request));
	}

	/**
	 * Reads the resource by executing the TL-Script expression with parameters from the URI.
	 *
	 * <p>
	 * This method must be called within a TopLogic thread context (see {@link #handleReadRequest}).
	 * </p>
	 *
	 * @param request
	 *        The read resource request containing the URI with parameters.
	 * @return The resource content as text.
	 */
	private McpSchema.ReadResourceResult readResource(McpSchema.ReadResourceRequest request) {
		String uri = request.uri();

		// Extract parameters from URI
		Map<String, String> parameters = _uriPattern.extractParameters(uri);

		// Execute TL-Script expression with parameters
		Object result = QueryExecutor.compile(getConfig().getContent()).execute(parameters.values().toArray());

		// Convert result to string
		String content = result != null ? result.toString() : "";

		// Determine MIME type
		String mimeType = getConfig().getMimeType();
		if (mimeType == null) {
			mimeType = "text/plain";
		}

		// Create resource contents
		McpSchema.TextResourceContents contents = new McpSchema.TextResourceContents(
			uri,
			mimeType,
			content
		);

		return new McpSchema.ReadResourceResult(List.of(contents));
	}
}
