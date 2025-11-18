/*
 * Copyright (c) 2025 My Company. All Rights Reserved
 */
package com.top_logic.ai.mcp.server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.top_logic.basic.thread.ThreadContextManager;
import com.top_logic.model.search.expr.query.QueryExecutor;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * Handler for configurable MCP resource templates that use TL-Script expressions
 * to dynamically generate resource content.
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
public class ConfigurableResourceTemplate {

	private final ResourceTemplateConfig _config;

	private final Pattern _uriPattern;

	private final List<String> _parameterNames;

	/**
	 * Creates a {@link ConfigurableResourceTemplate}.
	 *
	 * @param config
	 *        The resource template configuration.
	 */
	public ConfigurableResourceTemplate(ResourceTemplateConfig config) {
		_config = config;
		_parameterNames = new ArrayList<>();
		_uriPattern = createUriPattern(config.getUriTemplate(), _parameterNames);
	}

	/**
	 * Creates the MCP resource template specification for this configurable resource.
	 *
	 * @return The resource template specification that can be registered with the MCP server.
	 */
	public McpServerFeatures.SyncResourceTemplateSpecification createSpecification() {
		McpSchema.ResourceTemplate.Builder builder = McpSchema.ResourceTemplate.builder()
			.uriTemplate(_config.getUriTemplate())
			.name(_config.getName());

		// Add optional metadata
		if (_config.getTitle() != null) {
			builder.name(_config.getTitle());
		}

		if (_config.getDescription() != null) {
			builder.description(_config.getDescription());
		}

		if (_config.getMimeType() != null) {
			builder.mimeType(_config.getMimeType());
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
		Map<String, Object> parameters = extractParameters(uri);

		// Execute TL-Script expression with parameters
		Object result = QueryExecutor.compile(_config.getContent()).execute(parameters.values().toArray());

		// Convert result to string
		String content = result != null ? result.toString() : "";

		// Determine MIME type
		String mimeType = _config.getMimeType();
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

	/**
	 * Extracts parameter values from a URI based on the configured URI template.
	 *
	 * @param uri
	 *        The full URI (e.g., "myapp://data/12345").
	 * @return A map of parameter names to their values.
	 */
	private Map<String, Object> extractParameters(String uri) {
		Matcher matcher = _uriPattern.matcher(uri);
		if (!matcher.matches()) {
			throw new IllegalArgumentException("URI does not match template: " + uri);
		}

		Map<String, Object> parameters = new HashMap<>();
		for (int i = 0; i < _parameterNames.size(); i++) {
			String paramName = _parameterNames.get(i);
			String paramValue = matcher.group(i + 1);
			parameters.put(paramName, paramValue);
		}

		return parameters;
	}

	/**
	 * Creates a regex pattern from a URI template by replacing template variables with capture
	 * groups and collecting parameter names.
	 *
	 * <p>
	 * Template variables in the format {@code {variableName}} are replaced with the regex
	 * {@code ([^/]+)} which captures one or more non-slash characters. The variable names
	 * are collected in order into the provided list.
	 * </p>
	 *
	 * @param template
	 *        The URI template string (e.g., "myapp://data/{itemId}").
	 * @param parameterNames
	 *        Output list that will be filled with parameter names in order.
	 * @return A compiled Pattern that can match and extract variables from URIs.
	 */
	private static Pattern createUriPattern(String template, List<String> parameterNames) {
		StringBuilder patternBuilder = new StringBuilder();
		int pos = 0;

		while (pos < template.length()) {
			int varStart = template.indexOf('{', pos);

			if (varStart == -1) {
				// No more variables, quote the remaining literal part
				patternBuilder.append(Pattern.quote(template.substring(pos)));
				break;
			}

			// Quote the literal part before the variable
			if (varStart > pos) {
				patternBuilder.append(Pattern.quote(template.substring(pos, varStart)));
			}

			// Find the end of the variable
			int varEnd = template.indexOf('}', varStart);
			if (varEnd == -1) {
				throw new IllegalArgumentException("Unclosed variable in template: " + template);
			}

			// Extract variable name
			String varName = template.substring(varStart + 1, varEnd);
			parameterNames.add(varName);

			// Add capture group for the variable
			patternBuilder.append("([^/]+)");

			// Move past the variable
			pos = varEnd + 1;
		}

		return Pattern.compile(patternBuilder.toString());
	}
}
