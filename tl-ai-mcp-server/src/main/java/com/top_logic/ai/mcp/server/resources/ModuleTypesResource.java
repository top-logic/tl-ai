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
import com.top_logic.model.TLType;
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

	/**
	 * Pattern for extracting module name from URI.
	 *
	 * <p>
	 * Derived from {@link #URI_TEMPLATE} by replacing template variables with capture groups.
	 * </p>
	 */
	private static final Pattern URI_PATTERN = createUriPattern(URI_TEMPLATE);

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

		// Get all types in the module, excluding associations (implementation details)
		List<TLType> types = module.getTypes().stream()
			.filter(type -> type.getModelKind() != ModelKind.ASSOCIATION)
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

				// Add meta-type (class, primitive, enum, etc.)
				ModelKind modelKind = type.getModelKind();
				json.name("metaType").value(modelKind.name().toLowerCase());

				// Add part count (for structured types that have parts)
				if (type instanceof TLStructuredType) {
					TLStructuredType structuredType = (TLStructuredType) type;
					int partCount = structuredType.getLocalParts().size();
					json.name("partCount").value(partCount);
				}

				// Get the resource key for the type (checks annotation and handles defaults)
				ResKey typeKey = TLModelNamingConvention.getTypeLabelKey(type);
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
	 * Creates a regex pattern from a URI template by replacing template variables with capture
	 * groups.
	 *
	 * <p>
	 * Template variables in the format {@code {variableName}} are replaced with the regex
	 * {@code ([^/]+)} which captures one or more non-slash characters.
	 * </p>
	 *
	 * @param template
	 *        The URI template string (e.g., "toplogic://model/modules/{moduleName}/types").
	 * @return A compiled Pattern that can match and extract variables from URIs.
	 */
	static Pattern createUriPattern(String template) {
		// Split the template into literal parts and variable parts
		// Build the pattern by quoting literals and replacing variables with capture groups
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

			// Add capture group for the variable
			patternBuilder.append("([^/]+)");

			// Move past the variable
			pos = varEnd + 1;
		}

		return Pattern.compile(patternBuilder.toString());
	}

	/**
	 * Extracts the module name from a URI like {@code toplogic://model/modules/tl.core/types}.
	 *
	 * @param uri
	 *        The full URI.
	 * @return The extracted module name.
	 */
	private static String extractModuleName(String uri) {
		Matcher matcher = URI_PATTERN.matcher(uri);
		if (!matcher.matches()) {
			throw new IllegalArgumentException("Invalid module types URI: " + uri);
		}
		return matcher.group(1);
	}

}
