/*
 * Copyright (c) 2025 My Company. All Rights Reserved
 */
package com.top_logic.ai.mcp.server;

import java.io.IOException;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;

import com.top_logic.base.services.simpleajax.HTMLFragment;
import com.top_logic.basic.config.AbstractConfiguredInstance;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.io.binary.BinaryDataSource;
import com.top_logic.basic.json.JSON;
import com.top_logic.basic.thread.ThreadContextManager;
import com.top_logic.basic.xml.TagWriter;
import com.top_logic.model.search.expr.config.dom.Expr;
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

	private final QueryExecutor _compiledFunction;

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
		_compiledFunction = compileFunction(config.getContent(), _uriPattern.getParameterNames());
	}

	/**
	 * Compiles the user's TL-Script expression into a function that declares the URI parameters.
	 *
	 * <p>
	 * Wraps the user's expression in nested functions, one for each parameter from the URI
	 * template. Each parameter is declared using
	 * {@link com.top_logic.model.search.expr.config.dom.Expr.Define#create(String, Expr)}.
	 * </p>
	 *
	 * @param bodyExpr
	 *        The user's expression to use as the function body.
	 * @param parameterNames
	 *        The parameter names from the URI template, in order.
	 * @return The compiled function.
	 */
	private static QueryExecutor compileFunction(Expr bodyExpr, List<String> parameterNames) {
		// Wrap the body expression in nested functions, one for each parameter
		// Build from the inside out: for params [a, b], create: a -> (b -> body)
		Expr wrappedExpr = bodyExpr;
		for (int i = parameterNames.size() - 1; i >= 0; i--) {
			String paramName = parameterNames.get(i);
			wrappedExpr = Expr.Define.create(paramName, wrappedExpr);
		}
		return QueryExecutor.compile(wrappedExpr);
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

		// Build argument array in the order of parameter names
		List<String> parameterNames = _uriPattern.getParameterNames();
		Object[] arguments = new Object[parameterNames.size()];
		for (int i = 0; i < parameterNames.size(); i++) {
			String paramName = parameterNames.get(i);
			arguments[i] = parameters.get(paramName);
		}

		// Execute compiled function with parameters as arguments
		Object result = _compiledFunction.execute(arguments);

		// Handle different result types
		McpSchema.ResourceContents contents = createResourceContents(uri, result);

		return new McpSchema.ReadResourceResult(List.of(contents));
	}

	/**
	 * Creates resource contents from the script result, handling different return types.
	 *
	 * @param uri
	 *        The resource URI.
	 * @param result
	 *        The result from executing the TL-Script expression.
	 * @return The resource contents with appropriate content type.
	 */
	private McpSchema.ResourceContents createResourceContents(String uri, Object result) {
		// Handle null result
		if (result == null) {
			String mimeType = getConfiguredMimeType("text/plain");
			return new McpSchema.TextResourceContents(uri, mimeType, "");
		}

		// Handle BinaryDataSource - use its content type
		if (result instanceof BinaryDataSource binaryData) {
			try {
				String content = binaryData.toString(); // Read as string for MCP text protocol
				String mimeType = getConfiguredMimeType(binaryData.getContentType());
				return new McpSchema.TextResourceContents(uri, mimeType, content);
			} catch (Exception ex) {
				throw new RuntimeException("Failed to read binary content: " + ex.getMessage(), ex);
			}
		}

		// Handle Map - serialize as JSON
		if (result instanceof Map) {
			String content = serializeMapAsJson((Map<?, ?>) result);
			String mimeType = getConfiguredMimeType("application/json");
			return new McpSchema.TextResourceContents(uri, mimeType, content);
		}

		// Handle HTMLFragment - render to HTML string
		if (result instanceof HTMLFragment htmlFragment) {
			String content = renderHtmlFragment(htmlFragment);
			String mimeType = getConfiguredMimeType("text/html");
			return new McpSchema.TextResourceContents(uri, mimeType, content);
		}

		// Default: convert to string using toString()
		String content = result.toString();
		String mimeType = getConfiguredMimeType("text/plain");
		return new McpSchema.TextResourceContents(uri, mimeType, content);
	}

	/**
	 * Returns the configured MIME type, or the provided default if none is configured.
	 *
	 * @param defaultMimeType
	 *        The default MIME type to use if none is configured.
	 * @return The MIME type to use.
	 */
	private String getConfiguredMimeType(String defaultMimeType) {
		String configured = getConfig().getMimeType();
		return configured != null ? configured : defaultMimeType;
	}

	/**
	 * Serializes a Map as JSON.
	 *
	 * @param map
	 *        The map to serialize.
	 * @return The JSON string representation.
	 */
	private String serializeMapAsJson(Map<?, ?> map) {
		try {
			return JSON.toString(map);
		} catch (Exception ex) {
			throw new RuntimeException("Failed to serialize map as JSON: " + ex.getMessage(), ex);
		}
	}

	/**
	 * Renders an HTMLFragment to an HTML string.
	 *
	 * @param fragment
	 *        The HTML fragment to render.
	 * @return The rendered HTML string.
	 */
	private String renderHtmlFragment(HTMLFragment fragment) {
		StringWriter buffer = new StringWriter();
		try (TagWriter writer = new TagWriter(buffer)) {
			fragment.write(null, writer);
		} catch (IOException ex) {
			throw new RuntimeException("Failed to render HTML fragment: " + ex.getMessage(), ex);
		}
		return buffer.toString();
	}
}
