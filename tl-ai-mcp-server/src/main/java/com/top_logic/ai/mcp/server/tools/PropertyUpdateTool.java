package com.top_logic.ai.mcp.server.tools;

import java.io.IOException;
import java.io.StringWriter;
import java.util.Map;

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
import com.top_logic.model.TLStructuredTypePart;
import com.top_logic.model.TLType;
import com.top_logic.model.util.TLModelNamingConvention;
import com.top_logic.util.model.ModelService;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;

/**
 * Tool for updating attributes of existing TopLogic class properties.
 *
 * This tool allows updating the property cardinality flags (mandatory, multiple, ordered, bag),
 * abstract flag, and internationalized labels and descriptions.
 */
public class PropertyUpdateTool {

	/** Tool name for the property update functionality. */
	public static final String TOOL_NAME = "update-property";

	/** Tool description. */
	private static final String DESCRIPTION =
		"Update attributes and internationalization of an existing TopLogic class property";

	/** JSON Schema for the tool input arguments. */
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
		      "description": "Name of the class containing the property"
		    },
		    "propertyName": {
		      "type": "string",
		      "description": "Name of the property to update"
		    },
		    "mandatory": {
		      "type": "boolean",
		      "description": "Whether the property is required"
		    },
		    "multiple": {
		      "type": "boolean",
		      "description": "Whether the property can hold multiple values"
		    },
		    "ordered": {
		      "type": "boolean",
		      "description": "Whether order matters for multiple-valued properties"
		    },
		    "bag": {
		      "type": "boolean",
		      "description": "Whether duplicates are allowed for multiple-valued properties"
		    },
		    "abstract": {
		      "type": "boolean",
		      "description": "Whether this attribute must be overridden in specific classes"
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
		      "additionalProperties": false
		    }
		  },
		  "required": ["moduleName", "className", "propertyName"],
		  "additionalProperties": false
		}
		""";

	/**
	 * Creates the MCP tool specification for the property update functionality.
	 *
	 * @return The tool specification that can be registered with the MCP server.
	 */
	public static McpServerFeatures.SyncToolSpecification createSpecification() {
		McpJsonMapper jsonMapper = McpJsonMapper.getDefault();

		McpSchema.Tool tool = McpSchema.Tool.builder()
			.name(TOOL_NAME)
			.title("Update TopLogic Property")
			.description(DESCRIPTION)
			.inputSchema(jsonMapper, INPUT_SCHEMA_JSON)
			.build();

		return McpServerFeatures.SyncToolSpecification.builder()
			.tool(tool)
			.callHandler(PropertyUpdateTool::handleToolRequest)
			.build();
	}

	/**
	 * Handles the tool request for updating a property.
	 *
	 * @param exchange The MCP server exchange context.
	 * @param request The tool request containing the arguments.
	 * @return The tool result with success/error information.
	 */
	private static McpSchema.CallToolResult handleToolRequest(
			McpSyncServerExchange exchange,
			CallToolRequest request) {

		Map<String, Object> arguments = request.arguments();

		return ThreadContextManager.inSystemInteraction(
			PropertyUpdateTool.class,
			() -> updateProperty(arguments));
	}

	/**
	 * Updates the specified property with the provided attributes.
	 *
	 * @param arguments The tool arguments containing property name and update data.
	 * @return The tool result with updated property information.
	 */
	private static McpSchema.CallToolResult updateProperty(Map<String, Object> arguments) {
		// Validate and extract required arguments
		final String moduleName;
		final String className;
		final String propertyName;
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
		} catch (ToolArgumentUtil.ToolInputException e) {
			return createErrorResult(e.getMessage());
		}

		// Retrieve module and validate existence
		TLModel model = ModelService.getApplicationModel();
		TLModule module = model.getModule(moduleName);
		if (module == null) {
			return createErrorResult("Module '" + moduleName + "' not found");
		}

		// Retrieve class and validate existence
		TLType existingType = module.getType(className);
		if (!(existingType instanceof TLClass)) {
			if (existingType == null) {
				return createErrorResult("Class '" + className + "' not found in module '" + moduleName + "'");
			} else {
				return createErrorResult("Type '" + className + "' exists but is not a class");
			}
		}
		TLClass tlClass = (TLClass) existingType;

		// Retrieve property and validate existence
		TLStructuredTypePart existingPart = tlClass.getPart(propertyName);
		if (existingPart == null) {
			return createErrorResult("Property '" + propertyName + "' not found in class '" + className + "'");
		}
		if (!(existingPart instanceof TLClassProperty)) {
			return createErrorResult("'" + propertyName + "' exists but is not a class property");
		}
		TLClassProperty property = (TLClassProperty) existingPart;

		// Extract optional attributes
		Boolean mandatory = arguments.containsKey("mandatory") ?
			ToolArgumentUtil.getBooleanArgument(arguments, "mandatory", false) : null;
		Boolean multiple = arguments.containsKey("multiple") ?
			ToolArgumentUtil.getBooleanArgument(arguments, "multiple", false) : null;
		Boolean ordered = arguments.containsKey("ordered") ?
			ToolArgumentUtil.getBooleanArgument(arguments, "ordered", false) : null;
		Boolean bag = arguments.containsKey("bag") ?
			ToolArgumentUtil.getBooleanArgument(arguments, "bag", false) : null;
		Boolean isAbstract = arguments.containsKey("abstract") ?
			ToolArgumentUtil.getBooleanArgument(arguments, "abstract", false) : null;

		// Validate attribute consistency
		String validationError = validateAttributeConsistency(mandatory, multiple, ordered, bag);
		if (validationError != null) {
			return createErrorResult(validationError);
		}

		// Extract I18N data
		ToolI18NUtil.LocalizedTexts i18n = ToolI18NUtil.extractFromArguments(arguments);

		// Check if there are any updates to apply
		boolean hasUpdates = (mandatory != null) || (multiple != null) ||
			(ordered != null) || (bag != null) || (isAbstract != null) || i18n.hasAny();

		if (!hasUpdates) {
			return createErrorResult("No update data provided. At least one attribute or I18N field must be specified");
		}

		// Apply updates in transaction
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction(
				ResKey.text("Update Property"))) {

			// Update attributes if provided
			if (mandatory != null) {
				property.setMandatory(mandatory);
			}
			if (multiple != null) {
				property.setMultiple(multiple);
			}
			if (ordered != null) {
				property.setOrdered(ordered);
			}
			if (bag != null) {
				property.setBag(bag);
			}
			if (isAbstract != null) {
				property.setAbstract(isAbstract);
			}

			tx.commit();

			// Apply I18N updates after successful transaction
			ToolI18NUtil.applyIfPresent(property, i18n);

			String jsonResponse = buildSuccessJson(property);
			return new McpSchema.CallToolResult(jsonResponse, false);

		} catch (Exception ex) {
			return createErrorResult("Failed to update property: " + ex.getMessage());
		}
	}

	/**
	 * Validates property attribute consistency.
	 *
	 * @param mandatory The mandatory flag value (may be null if not being updated).
	 * @param multiple The multiple flag value (may be null if not being updated).
	 * @param ordered The ordered flag value (may be null if not being updated).
	 * @param bag The bag flag value (may be null if not being updated).
	 * @return Error message if inconsistent, null if valid.
	 */
	private static String validateAttributeConsistency(
			Boolean mandatory, Boolean multiple, Boolean ordered, Boolean bag) {

		// If multiple=false, ordered and bag must be false or null
		if (multiple != null && !multiple) {
			if (ordered != null && ordered) {
				return "Property cannot be ordered when multiple=false. Ordered flag only applies to multi-valued properties.";
			}
			if (bag != null && bag) {
				return "Property cannot allow duplicates when multiple=false. Bag flag only applies to multi-valued properties.";
			}
		}

		// Note: We don't validate the combination of ordered=true and bag=false
		// as this is a valid configuration (ordered set)

		// Abstract properties make sense mainly when the type is abstract
		// This is not an error, but a consideration for the user
		// The validation is left to the user's discretion

		return null; // No validation errors
	}

	/**
	 * Builds a JSON response for successful property updates.
	 *
	 * @param property The updated property.
	 * @return JSON string with success information and updated property details.
	 */
	private static String buildSuccessJson(TLClassProperty property) throws IOException {
		StringWriter buffer = new StringWriter();
		try (JsonWriter json = JsonResponseBuilder.createWriter(buffer)) {
			json.beginObject();

			json.name("success").value(true);
			json.name("updated").value(true);

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

			// Add label and description
			JsonResponseBuilder.writeLabelAndDescription(json, TLModelNamingConvention.resourceKey(property));

			json.endObject();

			json.name("message").value("Property '" + property.getName()
				+ "' updated successfully in class '" + property.getOwner().getName() + "'");

			json.endObject();
		}
		return buffer.toString();
	}

	/**
	 * Creates a standardized error response.
	 *
	 * @param errorMessage The error message to include in the response.
	 * @return A CallToolResult containing the error information.
	 */
	private static McpSchema.CallToolResult createErrorResult(String errorMessage) {
		String errorJson = JsonResponseBuilder.buildToolErrorJson(errorMessage);
		return new McpSchema.CallToolResult(errorJson, false);
	}
}