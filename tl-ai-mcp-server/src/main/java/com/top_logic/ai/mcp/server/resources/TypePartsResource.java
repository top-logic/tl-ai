/*
 * Copyright (c) 2025 My Company. All Rights Reserved
 */
package com.top_logic.ai.mcp.server.resources;

import java.io.IOException;
import java.io.StringWriter;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.top_logic.basic.util.ResKey;
import com.top_logic.common.json.gstream.JsonWriter;
import com.top_logic.model.ModelKind;
import com.top_logic.model.TLModel;
import com.top_logic.model.TLModule;
import com.top_logic.model.TLStructuredType;
import com.top_logic.model.TLStructuredTypePart;
import com.top_logic.model.TLType;
import com.top_logic.model.resources.TLTypePartResourceProvider;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.Resources;
import com.top_logic.util.model.ModelService;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * MCP resource that provides the list of parts (properties and references) within a specific
 * TopLogic type.
 *
 * <p>
 * This resource uses a URI template to dynamically access parts for any structured type in the
 * system. The resource URI pattern is
 * {@code toplogic://model/modules/{moduleName}/types/{typeName}/parts}, where {@code {moduleName}}
 * and {@code {typeName}} are replaced with actual values.
 * </p>
 *
 * <p>
 * For example:
 * <ul>
 * <li>{@code toplogic://model/modules/tl.core/types/TLObject/parts} - Lists parts in TLObject</li>
 * <li>{@code toplogic://model/modules/myapp.model/types/Person/parts} - Lists parts in Person</li>
 * </ul>
 * </p>
 *
 * <p>
 * The resource returns a JSON array of part objects, each containing the part's name, type, kind
 * (property/reference), and description (if available).
 * </p>
 *
 * @author Bernhard Haumacher
 */
public class TypePartsResource {

	/** URI template for type parts resource. */
	public static final String URI_TEMPLATE = "toplogic://model/modules/{moduleName}/types/{typeName}/parts";

	/**
	 * Pattern for extracting module name and type name from URI.
	 *
	 * <p>
	 * Derived from {@link #URI_TEMPLATE} by replacing template variables with capture groups.
	 * </p>
	 */
	private static final Pattern URI_PATTERN = ModuleTypesResource.createUriPattern(URI_TEMPLATE);

	/** Resource name template. */
	private static final String NAME_TEMPLATE = "type-parts-{moduleName}-{typeName}";

	/** Resource description. */
	private static final String DESCRIPTION = "List of parts (properties and references) in a TopLogic type";

	/** MIME type for JSON content. */
	private static final String MIME_TYPE = "application/json";

	/**
	 * Creates the MCP resource template specification for listing type parts.
	 *
	 * <p>
	 * This creates a resource template that can handle requests for any structured type's parts by
	 * extracting the module and type names from the URI.
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
			TypePartsResource::handleReadRequest
		);
	}

	/**
	 * Handles requests to read a type's parts resource.
	 *
	 * @param exchange
	 *        The MCP server exchange for interacting with the client.
	 * @param request
	 *        The read resource request containing the URI with the module and type names.
	 * @return The resource content with the list of parts in the type.
	 */
	private static McpSchema.ReadResourceResult handleReadRequest(
			McpSyncServerExchange exchange,
			McpSchema.ReadResourceRequest request) {

		// Extract module name and type name from URI
		String uri = request.uri();
		String[] names = extractNames(uri);
		String moduleName = names[0];
		String typeName = names[1];

		// Get the application model and find the requested module
		TLModel model = ModelService.getApplicationModel();
		TLModule module = model.getModule(moduleName);

		if (module == null) {
			throw new IllegalArgumentException("Module not found: " + moduleName);
		}

		// Find the type in the module
		TLType type = module.getType(typeName);

		if (type == null) {
			throw new IllegalArgumentException("Type not found: " + typeName + " in module " + moduleName);
		}

		// Check if the type is structured (has parts)
		if (!(type instanceof TLStructuredType)) {
			throw new IllegalArgumentException(
				"Type " + typeName + " is not a structured type and does not have parts");
		}

		TLStructuredType structuredType = (TLStructuredType) type;

		// Get all local parts in the type, sorted by name
		List<? extends TLStructuredTypePart> parts = structuredType.getLocalParts().stream()
			.sorted((p1, p2) -> p1.getName().compareTo(p2.getName()))
			.collect(Collectors.toList());

		// Build JSON array with part information using JsonWriter
		StringWriter buffer = new StringWriter();
		try (JsonWriter json = new JsonWriter(buffer)) {
			json.setIndent("  ");
			json.beginArray();

			for (TLStructuredTypePart part : parts) {
				json.beginObject();
				json.name("name").value(part.getName());

				// Add part kind (property/reference)
				ModelKind partKind = part.getModelKind();
				json.name("kind").value(partKind.name().toLowerCase());

				// Add type information
				TLType partType = part.getType();
				if (partType != null) {
					json.name("type").value(TLModelUtil.qualifiedName(partType));
				}

				// Add multiplicity information
				json.name("mandatory").value(part.isMandatory());
				json.name("multiple").value(part.isMultiple());

				// Get the resource key for the part (checks annotation and handles defaults)
				ResKey partKey = TLTypePartResourceProvider.labelKey(part);
				Resources resources = Resources.getInstance();

				// Add label from the resource key (optional)
				String label = resources.getString(partKey, null);
				if (label != null && !label.isEmpty()) {
					json.name("label").value(label);
				}

				// Add description from tooltip sub-key (optional)
				ResKey tooltipKey = partKey.tooltip();
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
	 * Extracts the module name and type name from a URI like
	 * {@code toplogic://model/modules/tl.core/types/TLObject/parts}.
	 *
	 * @param uri
	 *        The full URI.
	 * @return Array with [moduleName, typeName].
	 */
	private static String[] extractNames(String uri) {
		Matcher matcher = URI_PATTERN.matcher(uri);
		if (!matcher.matches()) {
			throw new IllegalArgumentException("Invalid type parts URI: " + uri);
		}
		return new String[] { matcher.group(1), matcher.group(2) };
	}

}
