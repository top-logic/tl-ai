/*
 * Copyright (c) 2025 TopLogic. All Rights Reserved
 */
package com.top_logic.ai.mcp.server.tools;

import java.io.IOException;
import java.io.StringWriter;
import java.util.Map;
import java.util.regex.Pattern;

import com.top_logic.ai.mcp.server.util.JsonResponseBuilder;
import com.top_logic.basic.thread.ThreadContextManager;
import com.top_logic.basic.util.ResKey;
import com.top_logic.common.json.gstream.JsonWriter;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLClassProperty;
import com.top_logic.model.TLModel;
import com.top_logic.model.TLModule;
import com.top_logic.model.TLPrimitive;
import com.top_logic.model.TLStructuredTypePart;
import com.top_logic.model.TLType;
import com.top_logic.model.builtin.TLCore;
import com.top_logic.model.util.TLModelNamingConvention;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.model.ModelService;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;

/**
 * MCP tool for creating properties on TopLogic classes.
 * <p>
 * This tool provides functionality to create new properties on existing classes in TopLogic
 * application model using {@link TLModelUtil#addProperty(TLClass, String, TLType)}. The tool
 * supports primitive types only in this initial version.
 * </p>
 */
public class PropertyCreationTool {

	/** Tool name for the property creation functionality. */
	public static final String TOOL_NAME = "create-property";

	/** Tool description. */
	private static final String DESCRIPTION =
		"Create a new property on a TopLogic class with primitive types (STRING, INT, BOOLEAN, FLOAT, DATE, TRISTATE, BINARY, CUSTOM), "
			+ "or return an existing one by name.";

	/** JSON Schema for the tool input. */
	private static final String INPUT_SCHEMA_JSON = """
			{
			  "type": "object",
			  "properties": {
			    "moduleName": {
			      "type": "string",
			      "description": "Name of the module containing the class"
			    },
			    "className": {
			      "type": "string",
			      "description": "Name of the class to add the property to"
			    },
			    "propertyName": {
				  "type": "string",
				  "description": "Name of the property. Must start with a letter or underscore, may contain only letters (including umlauts), digits, dots, underscores, and minus signs, and must not end with a dot."
				},
			    "propertyType": {
			      "type": "string",
			      "description": "Primitive type kind (e.g., STRING, INT, BOOLEAN, FLOAT, DATE, TRISTATE, BINARY, CUSTOM). Case-insensitive."
			    },
			    "mandatory": {
			      "type": "boolean",
			      "description": "Whether the property is required (default: false)"
			    },
			    "multiple": {
			      "type": "boolean",
			      "description": "Whether the property can hold multiple values (default: false)"
			    },
			    "ordered": {
			      "type": "boolean",
			      "description": "Whether order matters for multiple-valued properties (default: false)"
			    },
			    "bag": {
			      "type": "boolean",
			      "description": "Whether duplicates are allowed for multiple-valued properties (default: false)"
			    },
			    "abstract": {
			      "type": "boolean",
			      "description": "Whether this attribute must be overridden in specific classes. Only relevant if type is abstract. (default: false)"
			    },
			    "label": {
			      "type": "object",
			      "properties": {
			        "en": {
			          "type": "string",
			          "description": "English label for the property"
			        },
			        "de": {
			          "type": "string",
			          "description": "German label for the property"
			        }
			      },
			      "required": ["en", "de"],
			      "additionalProperties": false
			    },
			    "description": {
			      "type": "object",
			      "properties": {
			        "en": {
			          "type": "string",
			          "description": "English description for the property"
			        },
			        "de": {
			          "type": "string",
			          "description": "German description for the property"
			        }
			      },
			      "required": ["en", "de"],
			      "additionalProperties": false
			    }
			  },
			  "required": ["moduleName", "className", "propertyName", "propertyType"],
			  "additionalProperties": false
			}
			""";

	private static final Pattern PROPERTY_NAME_PATTERN = Pattern.compile(
		"^[\\p{L}_](?:[\\p{L}0-9._-]*[\\p{L}0-9_-])?$");

	/**
	 * Creates the MCP tool specification for property creation.
	 *
	 * @return The tool specification that can be registered with the MCP server.
	 */
	public static McpServerFeatures.SyncToolSpecification createSpecification() {
		// Get (or lazily create) the default JSON mapper from the MCP JSON module
		McpJsonMapper jsonMapper = McpJsonMapper.getDefault();

		McpSchema.Tool tool = McpSchema.Tool.builder()
			.name(TOOL_NAME)
			.title("Create TopLogic property")
			.description(DESCRIPTION)
			.inputSchema(jsonMapper, INPUT_SCHEMA_JSON)
			.build();

		return McpServerFeatures.SyncToolSpecification.builder()
			.tool(tool)
			.callHandler(PropertyCreationTool::handleToolRequest)
			.build();
	}

	/**
	 * Handles tool requests for creating properties.
	 *
	 * @param exchange
	 *        The MCP server exchange for interacting with the client.
	 * @param request
	 *        The tool call request (includes tool name, arguments, etc.).
	 * @return The result of the property creation operation.
	 */
	private static McpSchema.CallToolResult handleToolRequest(
			McpSyncServerExchange exchange,
			CallToolRequest request) {

		Map<String, Object> arguments = request.arguments();

		return ThreadContextManager.inSystemInteraction(
			PropertyCreationTool.class,
			() -> createProperty(arguments));
	}

	/**
	 * Creates a property based on the given arguments.
	 *
	 * @param arguments
	 *        The tool call arguments containing module name, class name, property name, and
	 *        configuration.
	 * @return The result indicating success or failure with property details.
	 */
	private static McpSchema.CallToolResult createProperty(Map<String, Object> arguments) {
		// Extract and validate required arguments
		final String moduleName;
		final String className;
		final String propertyName;
		final String propertyTypeKind;

		try {
			moduleName = ToolArgumentUtil.requireNonEmptyString(
				arguments,
				"moduleName",
				"Module name");

			className = ToolArgumentUtil.requireNonEmptyString(
				arguments,
				"className",
				"Class name");

			propertyName = ToolArgumentUtil.requireNonEmptyString(
				arguments,
				"propertyName",
				"Property name");
			validatePropertyName(propertyName);

			propertyTypeKind = ToolArgumentUtil.requireNonEmptyString(
				arguments,
				"propertyType",
				"Property type");

		} catch (ToolArgumentUtil.ToolInputException e) {
			return createErrorResult(e.getMessage());
		}


		// Extract optional flags
		boolean mandatory = ToolArgumentUtil.getBooleanArgument(arguments, "mandatory", false);
		boolean multiple = ToolArgumentUtil.getBooleanArgument(arguments, "multiple", false);
		boolean ordered = ToolArgumentUtil.getBooleanArgument(arguments, "ordered", false);
		boolean bag = ToolArgumentUtil.getBooleanArgument(arguments, "bag", false);
		boolean isAbstract = ToolArgumentUtil.getBooleanArgument(arguments, "abstract", false);

		// Extract I18N once, for potential use if creating new property
		ToolI18NUtil.LocalizedTexts i18n = ToolI18NUtil.extractFromArguments(arguments);

		// Look up module
		TLModel model = ModelService.getApplicationModel();
		TLModule module = model.getModule(moduleName);
		if (module == null) {
			return createErrorResult("Module '" + moduleName + "' does not exist");
		}

		// Look up class
		TLType existingType = module.getType(className);
		if (existingType == null) {
			return createErrorResult("Class '" + className + "' does not exist in module '" + moduleName + "'");
		}
		if (!(existingType instanceof TLClass)) {
			return createErrorResult(
				"Type '" + className + "' exists in module '" + moduleName + "' but is not a class");
		}
		TLClass tlClass = (TLClass) existingType;

		// Check if property already exists
		TLStructuredTypePart existingPart = tlClass.getPart(propertyName);
		TLClassProperty property;
		boolean created = false;

		if (existingPart != null) {
			// Already exists: ensure it is actually a TLClassProperty
			if (!(existingPart instanceof TLClassProperty)) {
				return createErrorResult(
					"Structured type part '" + propertyName + "' already exists on class '" + className
						+ "' but is not a property");
			}
			property = (TLClassProperty) existingPart;
			// Variant A: return existing property without modifying it
		} else {
			// Resolve primitive type
			final TLType propertyType;
			try {
				propertyType = resolvePrimitiveType(model, propertyTypeKind);
			} catch (ToolArgumentUtil.ToolInputException e) {
				return createErrorResult(e.getMessage());
			}

			// Create property within transaction
			try (Transaction tx =
				PersistencyLayer.getKnowledgeBase().beginTransaction(ResKey.text("Create Property"))) {

				// Create the property
				property = TLModelUtil.addProperty(tlClass, propertyName, propertyType);
				created = true;

				// Configure cardinality and flags
				property.setMandatory(mandatory);
				property.setMultiple(multiple);
				property.setOrdered(ordered);
				property.setBag(bag);
				property.setAbstract(isAbstract);

				tx.commit();

			} catch (Exception ex) {
				return createErrorResult("Failed to create property: " + ex.getMessage());
			}

			// Apply i18n only after successful creation (not for existing properties)
			ToolI18NUtil.applyIfPresent(property, i18n);
		}

		try {
			String jsonResponse = buildSuccessJson(property, created);
			return new McpSchema.CallToolResult(jsonResponse, false);
		} catch (Exception ex) {
			return createErrorResult("Failed to build success response: " + ex.getMessage());
		}
	}

	/**
	 * Resolves a primitive type from the given type kind string.
	 *
	 * @param model
	 *        The TopLogic model to look up types in.
	 * @param typeKind
	 *        The primitive type kind name (e.g., "STRING", "INT", "BOOLEAN").
	 * @return The resolved TLType.
	 * @throws ToolArgumentUtil.ToolInputException
	 *         If the type kind is not a valid primitive kind.
	 */
	private static TLType resolvePrimitiveType(TLModel model, String typeKind)
			throws ToolArgumentUtil.ToolInputException {
		try {
			TLPrimitive.Kind kind = TLPrimitive.Kind.valueOf(typeKind.toUpperCase());
			return TLCore.getPrimitiveType(model, kind);
		} catch (IllegalArgumentException e) {
			throw new ToolArgumentUtil.ToolInputException(
				"Invalid primitive type kind: '" + typeKind + "'. " +
					"Valid kinds are: STRING, INT, BOOLEAN, FLOAT, DATE, TRISTATE, BINARY, CUSTOM");
		}
	}

	/**
	 * Validates that the property name meets the naming requirements.
	 * <p>
	 * The name must start with a letter or underscore. It may only consist of letters (including
	 * umlauts), digits, dots, underscores, and minus signs and must not end with a dot.
	 * <p>
	 * Naming convention (not enforced): The name should start with a lower-case letter and only
	 * consist of letters, digits, and underscores.
	 */
	private static void validatePropertyName(String propertyName)
			throws ToolArgumentUtil.ToolInputException {

		if (!PROPERTY_NAME_PATTERN.matcher(propertyName).matches()) {
			throw new ToolArgumentUtil.ToolInputException(
				"Property name must start with a letter or underscore, may only contain letters (including umlauts), "
					+ "digits, dots, underscores, and minus signs, and must not end with a dot.");
		}
	}

	/**
	 * Builds a JSON response for successful property creation.
	 *
	 * @param property
	 *        The created or retrieved property.
	 * @param newlyCreated
	 *        Whether the property was newly created or already existed.
	 */
	private static String buildSuccessJson(TLClassProperty property, boolean newlyCreated) throws IOException {
		StringWriter buffer = new StringWriter();
		try (JsonWriter json = JsonResponseBuilder.createWriter(buffer)) {
			json.beginObject();

			json.name("success").value(true);
			json.name("property").beginObject();
			json.name("name").value(property.getName());
			json.name("tid").value(property.tId().asString());
			json.name("owner")
				.value(property.getOwner().getModule().getName() + ":" + property.getOwner().getName());
			json.name("type").value(property.getType().getName());
			json.name("mandatory").value(property.isMandatory());
			json.name("multiple").value(property.isMultiple());
			json.name("ordered").value(property.isOrdered());
			json.name("bag").value(property.isBag());
			json.name("abstract").value(property.isAbstract());

			// Add label and description from property resource key (optional)
			JsonResponseBuilder.writeLabelAndDescription(json,
				TLModelNamingConvention.resourceKey(property));

			json.endObject();
			json.name("created").value(newlyCreated);

			if (newlyCreated) {
				json.name("message").value("Property '" + property.getName()
					+ "' created successfully in class '" + property.getOwner().getName() + "'");
			} else {
				json.name("message").value("Property '" + property.getName()
					+ "' already existed in class '" + property.getOwner().getName() + "'");
			}

			json.endObject();
		}
		return buffer.toString();
	}

	/**
	 * Creates an error response.
	 *
	 * @param errorMessage
	 *        The error message to return.
	 * @return CallToolResult containing the error.
	 */
	private static McpSchema.CallToolResult createErrorResult(String errorMessage) {
		String errorJson = JsonResponseBuilder.buildToolErrorJson(errorMessage);
		return new McpSchema.CallToolResult(errorJson, true);
	}
}
