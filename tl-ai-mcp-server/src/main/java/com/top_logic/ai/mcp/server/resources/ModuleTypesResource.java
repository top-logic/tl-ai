/*
 * Copyright (c) 2025 My Company. All Rights Reserved
 */
package com.top_logic.ai.mcp.server.resources;

import java.io.IOException;
import java.io.StringWriter;
import java.util.List;
import java.util.stream.Collectors;

import com.top_logic.basic.util.ResKey;
import com.top_logic.common.json.gstream.JsonWriter;
import com.top_logic.knowledge.gui.AbstractTLItemResourceProvider;
import com.top_logic.model.TLModel;
import com.top_logic.model.TLModule;
import com.top_logic.model.TLType;
import com.top_logic.model.annotate.TLI18NKey;
import com.top_logic.model.util.TLModelNamingConvention;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.Resources;
import com.top_logic.util.model.ModelService;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * MCP resource that provides the list of types within a specific TopLogic module.
 *
 * <p>
 * This resource uses a URI template to dynamically access types for any module in the system.
 * The resource URI pattern is {@code toplogic://model/modules/{moduleName}/types}, where
 * {@code {moduleName}} is replaced with the actual module name.
 * </p>
 *
 * <p>
 * For example:
 * <ul>
 * <li>{@code toplogic://model/modules/tl.core/types} - Lists types in the tl.core module</li>
 * <li>{@code toplogic://model/modules/myapp.model/types} - Lists types in myapp.model module</li>
 * </ul>
 * </p>
 *
 * <p>
 * The resource returns a JSON array of type objects, each containing the type's qualified name,
 * label, and description (if available).
 * </p>
 *
 * @author Bernhard Haumacher
 */
public class ModuleTypesResource {

	/** URI template for module types resource. */
	public static final String URI_TEMPLATE = "toplogic://model/modules/{moduleName}/types";

	/** Resource name template. */
	private static final String NAME_TEMPLATE = "module-types-{moduleName}";

	/** Resource description. */
	private static final String DESCRIPTION = "List of types in a TopLogic module";

	/** MIME type for JSON content. */
	private static final String MIME_TYPE = "application/json";

	/**
	 * Creates the MCP resource template specification for listing module types.
	 *
	 * <p>
	 * This creates a resource template that can handle requests for any module's types
	 * by extracting the module name from the URI.
	 * </p>
	 *
	 * @return The resource template specification that can be registered with the MCP server.
	 */
	public static McpServerFeatures.SyncResourceTemplateSpecification createSpecification() {
		McpSchema.ResourceTemplate template = McpSchema.ResourceTemplate.builder()
			.uriTemplate(URI_TEMPLATE)
			.name(NAME_TEMPLATE)
			.description(DESCRIPTION)
			.mimeType(MIME_TYPE)
			.build();

		return new McpServerFeatures.SyncResourceTemplateSpecification(
			template,
			ModuleTypesResource::handleReadRequest
		);
	}

	/**
	 * Handles requests to read a module's types resource.
	 *
	 * @param exchange
	 *        The MCP server exchange for interacting with the client.
	 * @param request
	 *        The read resource request containing the URI with the module name.
	 * @return The resource content with the list of types in the module.
	 */
	private static McpSchema.ReadResourceResult handleReadRequest(
			McpSyncServerExchange exchange,
			McpSchema.ReadResourceRequest request) {

		// Extract module name from URI
		String uri = request.uri();
		String moduleName = extractModuleName(uri);

		// Get the application model and find the requested module
		TLModel model = ModelService.getApplicationModel();
		TLModule module = model.getModule(moduleName);

		if (module == null) {
			throw new IllegalArgumentException("Module not found: " + moduleName);
		}

		// Get all types in the module, sorted by name
		List<TLType> types = module.getTypes().stream()
			.sorted((t1, t2) -> t1.getName().compareTo(t2.getName()))
			.collect(Collectors.toList());

		// Build JSON array with type information using JsonWriter
		StringWriter buffer = new StringWriter();
		try (JsonWriter json = new JsonWriter(buffer)) {
			json.setIndent("  ");
			json.beginArray();

			for (TLType type : types) {
				json.beginObject();
				json.name("name").value(type.getName());

				// Use TLModelUtil to create the qualified name
				json.name("qualifiedName").value(TLModelUtil.qualifiedName(type));

				// Get the resource key for the type (handles defaults if no annotation)
				ResKey typeKey = getTypeResourceKey(type);
				Resources resources = Resources.getInstance();

				// Add label from the resource key (optional)
				String label = resources.getString(typeKey, null);
				if (label != null && !label.isEmpty()) {
					json.name("label").value(label);
				}

				// Add description from tooltip sub-key (optional)
				ResKey tooltipKey = typeKey.tooltip();
				String description = resources.getString(tooltipKey, null);
				if (description != null && !description.isEmpty()) {
					json.name("description").value(description);
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

	/**
	 * Extracts the module name from a URI like {@code toplogic://model/modules/tl.core/types}.
	 *
	 * @param uri
	 *        The full URI.
	 * @return The extracted module name.
	 */
	private static String extractModuleName(String uri) {
		// URI format: toplogic://model/modules/{moduleName}/types
		String prefix = "toplogic://model/modules/";
		String suffix = "/types";

		if (!uri.startsWith(prefix) || !uri.endsWith(suffix)) {
			throw new IllegalArgumentException("Invalid module types URI: " + uri);
		}

		return uri.substring(prefix.length(), uri.length() - suffix.length());
	}

	/**
	 * Gets the resource key for a type's label.
	 *
	 * <p>
	 * Similar to {@link com.top_logic.model.visit.LabelVisitor#getModuleResourceKey(TLModule)},
	 * this method checks for TLI18NKey annotation and falls back to default naming convention.
	 * </p>
	 *
	 * @param type
	 *        The type.
	 * @return The resource key for the type's label.
	 */
	private static ResKey getTypeResourceKey(TLType type) {
		TLI18NKey annotation = type.getAnnotation(TLI18NKey.class);
		if (annotation != null) {
			return annotation.getValue();
		}

		return TLModelNamingConvention.getTypeLabelKey(type);
	}

}
