/*
 * Copyright (c) 2025 My Company. All Rights Reserved
 */
package com.top_logic.ai.mcp.server.resources;

import java.io.IOException;
import java.io.StringWriter;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.top_logic.common.json.gstream.JsonWriter;
import com.top_logic.model.TLModel;
import com.top_logic.model.TLType;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.model.ModelService;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * MCP resource that finds usages of a specific TopLogic type.
 *
 * <p>
 * This resource uses a URI template to find where a type is used in the model. The resource URI
 * pattern is {@code toplogic://model/types/{qualifiedTypeName}/usages}, where
 * {@code {qualifiedTypeName}} is the fully qualified name of the type (e.g., "tl.core:TLObject").
 * </p>
 *
 * <p>
 * For example:
 * <ul>
 * <li>{@code toplogic://model/types/tl.core:TLObject/usages} - Find usages of TLObject</li>
 * <li>{@code toplogic://model/types/myapp.model:Person/usages} - Find usages of Person type</li>
 * </ul>
 * </p>
 *
 * <p>
 * The resource returns a JSON object with two arrays:
 * <ul>
 * <li>{@code properties} - Properties that use this type as their value type</li>
 * <li>{@code subclasses} - Classes that use this type as a generalization (supertype)</li>
 * </ul>
 * </p>
 *
 * @author Bernhard Haumacher
 */
public class TypeUsagesResource {

	/** URI template for type usages resource. */
	public static final String URI_TEMPLATE = "toplogic://model/types/{qualifiedTypeName}/usages";

	/**
	 * Pattern for extracting qualified type name from URI.
	 *
	 * <p>
	 * Derived from {@link #URI_TEMPLATE} by replacing template variables with capture groups.
	 * Note: The qualified name contains a colon (module:typeName), so we capture [^/]+ to allow
	 * any characters except slashes.
	 * </p>
	 */
	private static final Pattern URI_PATTERN = ModuleTypesResource.createUriPattern(URI_TEMPLATE);

	/** Resource name template. */
	private static final String NAME_TEMPLATE = "type-usages-{qualifiedTypeName}";

	/** Resource description. */
	private static final String DESCRIPTION = "Find usages of a TopLogic type (properties and subclasses)";

	/** MIME type for JSON content. */
	private static final String MIME_TYPE = "application/json";

	/**
	 * Creates the MCP resource template specification for finding type usages.
	 *
	 * <p>
	 * This creates a resource template that can handle requests for any type's usages by
	 * extracting the qualified type name from the URI.
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
			TypeUsagesResource::handleReadRequest
		);
	}

	/**
	 * Handles requests to read a type's usages resource.
	 *
	 * @param exchange
	 *        The MCP server exchange for interacting with the client.
	 * @param request
	 *        The read resource request containing the URI with the qualified type name.
	 * @return The resource content with the list of usages.
	 */
	private static McpSchema.ReadResourceResult handleReadRequest(
			McpSyncServerExchange exchange,
			McpSchema.ReadResourceRequest request) {

		// Extract qualified type name from URI
		String uri = request.uri();
		String qualifiedTypeName = extractQualifiedTypeName(uri);

		// Get the application model and find the requested type
		TLModel model = ModelService.getApplicationModel();
		TLType type = TLModelUtil.findType(model, qualifiedTypeName);

		if (type == null) {
			throw new IllegalArgumentException("Type not found: " + qualifiedTypeName);
		}

		// TODO: Implement real usage discovery
		// For now, return stub data

		// Build JSON object with stub usage information using JsonWriter
		StringWriter buffer = new StringWriter();
		try (JsonWriter json = new JsonWriter(buffer)) {
			json.setIndent("  ");
			json.beginObject();

			// Stub: Properties that use this type as value type
			json.name("properties").beginArray();
			json.beginObject();
			json.name("owner").value("stub.module:StubClass");
			json.name("property").value("stubProperty");
			json.name("usage").value("value type");
			json.endObject();
			json.endArray();

			// Stub: Classes that use this type as generalization (supertype)
			json.name("subclasses").beginArray();
			json.beginObject();
			json.name("class").value("stub.module:StubSubclass");
			json.name("usage").value("generalization");
			json.endObject();
			json.endArray();

			json.endObject();
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
	 * Extracts the qualified type name from a URI like
	 * {@code toplogic://model/types/tl.core:TLObject/usages}.
	 *
	 * @param uri
	 *        The full URI.
	 * @return The qualified type name (e.g., "tl.core:TLObject").
	 */
	private static String extractQualifiedTypeName(String uri) {
		Matcher matcher = URI_PATTERN.matcher(uri);
		if (!matcher.matches()) {
			throw new IllegalArgumentException("Invalid type usages URI: " + uri);
		}
		return matcher.group(1);
	}

}
