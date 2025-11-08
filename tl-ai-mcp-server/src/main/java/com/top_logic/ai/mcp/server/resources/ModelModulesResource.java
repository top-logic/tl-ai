/*
 * Copyright (c) 2025 My Company. All Rights Reserved
 */
package com.top_logic.ai.mcp.server.resources;

import java.io.IOException;
import java.io.StringWriter;
import java.util.List;
import java.util.stream.Collectors;

import com.top_logic.common.json.gstream.JsonWriter;
import com.top_logic.model.TLModel;
import com.top_logic.model.TLModule;
import com.top_logic.model.annotate.TLI18NKey;
import com.top_logic.util.Resources;
import com.top_logic.util.model.ModelService;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * MCP resource that provides a list of TopLogic data model modules.
 *
 * <p>
 * This resource exposes the structure of TopLogic type systems and modules, allowing AI agents
 * to browse and understand the data model structure of the application. Each module contains
 * type definitions that define the application's domain model.
 * </p>
 *
 * <p>
 * Resource URI: {@code toplogic://model/modules}
 * </p>
 *
 * <p>
 * The resource returns a JSON list of module names with their descriptions. This is the entry
 * point for model exploration - clients can use this to discover available modules and then
 * request details about specific modules or types.
 * </p>
 *
 * @author Bernhard Haumacher
 */
public class ModelModulesResource {

	/** URI for the model modules list resource. */
	public static final String URI = "toplogic://model/modules";

	/** Resource name. */
	private static final String NAME = "model-modules";

	/** Resource description. */
	private static final String DESCRIPTION = "List of TopLogic data model modules";

	/** MIME type for JSON content. */
	private static final String MIME_TYPE = "application/json";

	/**
	 * Creates the MCP resource specification for listing model modules.
	 *
	 * @return The resource specification that can be registered with the MCP server.
	 */
	public static McpServerFeatures.SyncResourceSpecification createSpecification() {
		McpSchema.Resource resource = McpSchema.Resource.builder()
			.uri(URI)
			.name(NAME)
			.description(DESCRIPTION)
			.mimeType(MIME_TYPE)
			.build();

		return new McpServerFeatures.SyncResourceSpecification(
			resource,
			ModelModulesResource::handleReadRequest
		);
	}

	/**
	 * Handles requests to read the model modules resource.
	 *
	 * @param exchange
	 *        The MCP server exchange for interacting with the client.
	 * @param request
	 *        The read resource request containing the URI.
	 * @return The resource content with the list of modules.
	 */
	private static McpSchema.ReadResourceResult handleReadRequest(
			McpSyncServerExchange exchange,
			McpSchema.ReadResourceRequest request) {

		// Get the application model and retrieve all modules
		TLModel model = ModelService.getApplicationModel();
		List<TLModule> modules = model.getModules().stream()
			.sorted((m1, m2) -> m1.getName().compareTo(m2.getName()))
			.collect(Collectors.toList());

		// Build JSON array with module information using JsonWriter
		StringWriter buffer = new StringWriter();
		try (JsonWriter json = new JsonWriter(buffer)) {
			json.setIndent("  ");
			json.beginArray();

			for (TLModule module : modules) {
				json.beginObject();
				json.name("name").value(module.getName());
				json.name("typeCount").value(module.getTypes().size());

				// Add description from TLI18NKey annotation if available
				TLI18NKey i18nKey = module.getAnnotation(TLI18NKey.class);
				if (i18nKey != null) {
					String description = Resources.getInstance().getString(i18nKey.getValue());
					if (description != null && !description.isEmpty()) {
						json.name("description").value(description);
					}
				}

				json.endObject();
			}

			json.endArray();
		} catch (IOException ex) {
			throw new RuntimeException("Failed to generate JSON: " + ex.getMessage(), ex);
		}

		McpSchema.TextResourceContents contents = new McpSchema.TextResourceContents(
			request.uri(),
			MIME_TYPE,
			buffer.toString()
		);

		return new McpSchema.ReadResourceResult(List.of(contents));
	}
}
