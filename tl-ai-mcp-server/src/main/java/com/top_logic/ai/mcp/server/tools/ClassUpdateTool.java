/*
 * SPDX-FileCopyrightText: 2025 (c) Business Operation Systems GmbH <info@top-logic.com>
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-BOS-TopLogic-1.0
 */
package com.top_logic.ai.mcp.server.tools;

import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.top_logic.ai.mcp.server.util.JsonResponseBuilder;
import com.top_logic.basic.thread.ThreadContextManager;
import com.top_logic.basic.util.ResKey;
import com.top_logic.common.json.gstream.JsonWriter;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLModel;
import com.top_logic.model.TLModule;
import com.top_logic.model.TLType;
import com.top_logic.model.util.TLModelNamingConvention;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.model.ModelService;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;

/**
 * Tool for updating properties of existing TopLogic classes.
 *
 * This tool allows updating the internationalized labels and descriptions,
 * abstract/final flags, and inheritance relationships of classes.
 */
public class ClassUpdateTool {

	/** Tool name for the class update functionality. */
	public static final String TOOL_NAME = "update-class";

	/** Tool description. */
	private static final String DESCRIPTION =
		"Update properties, flags, and inheritance of an existing TopLogic class";

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
		      "description": "Name of the class to update"
		    },
		    "abstract": {
		      "type": "boolean",
		      "description": "Whether the class is abstract (cannot be instantiated directly)"
		    },
		    "final": {
		      "type": "boolean",
		      "description": "Whether the class is final (cannot be specialized)"
		    },
		    "generalizations": {
		      "type": "array",
		      "description": "Names of the classes this class directly extends",
		      "items": {
		        "type": "string"
		      }
		    },
		    "label": {
		      "type": "object",
		      "properties": {
		        "en": {
		          "type": "string",
		          "description": "English label for the class"
		        },
		        "de": {
		          "type": "string",
		          "description": "German label for the class"
		        }
		      },
		      "additionalProperties": false
		    },
		    "description": {
		      "type": "object",
		      "properties": {
		        "en": {
		          "type": "string",
		          "description": "English description for the class"
		        },
		        "de": {
		          "type": "string",
		          "description": "German description for the class"
		        }
		      },
		      "additionalProperties": false
		    }
		  },
		  "required": ["moduleName", "className"],
		  "additionalProperties": false
		}
		""";

	/**
	 * Creates the MCP tool specification for the class update functionality.
	 *
	 * @return The tool specification that can be registered with the MCP server.
	 */
	public static McpServerFeatures.SyncToolSpecification createSpecification() {
		McpJsonMapper jsonMapper = McpJsonMapper.getDefault();

		McpSchema.Tool tool = McpSchema.Tool.builder()
			.name(TOOL_NAME)
			.title("Update TopLogic Class")
			.description(DESCRIPTION)
			.inputSchema(jsonMapper, INPUT_SCHEMA_JSON)
			.build();

		return McpServerFeatures.SyncToolSpecification.builder()
			.tool(tool)
			.callHandler(ClassUpdateTool::handleToolRequest)
			.build();
	}

	/**
	 * Handles the tool request for updating a class.
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
			ClassUpdateTool.class,
			() -> updateClass(arguments));
	}

	/**
	 * Updates the specified class with the provided properties.
	 *
	 * @param arguments The tool arguments containing class name and update data.
	 * @return The tool result with updated class information.
	 */
	private static McpSchema.CallToolResult updateClass(Map<String, Object> arguments) {
		// Validate and extract module and class names
		final String moduleName;
		final String className;
		try {
			moduleName = ToolArgumentUtil.requireNonEmptyString(
				arguments,
				"moduleName",
				"Module name");
			className = ToolArgumentUtil.requireNonEmptyString(
				arguments,
				"className",
				"Class name");
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

		// Extract optional boolean flags
		Boolean isAbstract = null;
		Boolean isFinal = null;
		if (arguments.containsKey("abstract")) {
			isAbstract = ToolArgumentUtil.getBooleanArgument(arguments, "abstract", false);
		}
		if (arguments.containsKey("final")) {
			isFinal = ToolArgumentUtil.getBooleanArgument(arguments, "final", false);
		}

		// Validate flag consistency
		String flagValidationError = validateFlagConsistency(isAbstract, isFinal);
		if (flagValidationError != null) {
			return createErrorResult(flagValidationError);
		}

		// Extract and resolve generalizations
		List<TLClass> newGeneralizations = null;
		if (arguments.containsKey("generalizations")) {
			List<String> generalizationNames = ToolArgumentUtil.getOptionalStringList(arguments, "generalizations");
			try {
				newGeneralizations = resolveGeneralizations(model, generalizationNames);
			} catch (ToolArgumentUtil.ToolInputException e) {
				return createErrorResult(e.getMessage());
			}

			// Validate inheritance for cycles
			try {
				validateInheritanceCycle(tlClass, newGeneralizations);
			} catch (ToolArgumentUtil.ToolInputException e) {
				return createErrorResult(e.getMessage());
			}
		}

		// Extract I18N data
		ToolI18NUtil.LocalizedTexts i18n = ToolI18NUtil.extractFromArguments(arguments);

		// Check if there are any updates to apply
		boolean hasUpdates = (isAbstract != null) || (isFinal != null)
			|| (newGeneralizations != null) || i18n.hasAny();

		if (!hasUpdates) {
			return createErrorResult("No update data provided. At least one of 'abstract', 'final', 'generalizations', 'label', or 'description' must be specified");
		}

		// Apply updates in transaction
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction(
				ResKey.text("Update Class"))) {

			// Update flags if provided
			if (isAbstract != null) {
				tlClass.setAbstract(isAbstract);
			}
			if (isFinal != null) {
				tlClass.setFinal(isFinal);
			}

			// Update generalizations if provided
			if (newGeneralizations != null) {
				tlClass.getGeneralizations().clear();
				tlClass.getGeneralizations().addAll(newGeneralizations);
			}

			// Apply I18N updates
			ToolI18NUtil.applyIfPresent(tlClass, i18n);

			tx.commit();

			String jsonResponse = buildSuccessJson(tlClass);
			return new McpSchema.CallToolResult(jsonResponse, false);

		} catch (Exception ex) {
			return createErrorResult("Failed to update class: " + ex.getMessage());
		}
	}

	/**
	 * Validates the consistency between abstract and final flags.
	 *
	 * @param isAbstract The abstract flag value (may be null if not being updated).
	 * @param isFinal The final flag value (may be null if not being updated).
	 * @return Error message if inconsistent, null if valid.
	 */
	private static String validateFlagConsistency(Boolean isAbstract, Boolean isFinal) {
		// Only validate if both flags are being set
		if (isAbstract != null && isFinal != null && isAbstract && isFinal) {
			return "Cannot set both abstract=true and final=true - these flags are mutually exclusive";
		}
		return null;
	}

	/**
	 * Validates that adding new generalizations won't create inheritance cycles.
	 *
	 * @param targetClass The class being updated.
	 * @param newGeneralizations The list of new parent classes.
	 * @throws ToolArgumentUtil.ToolInputException if a cycle is detected.
	 */
	private static void validateInheritanceCycle(TLClass targetClass, List<TLClass> newGeneralizations)
			throws ToolArgumentUtil.ToolInputException {
		Set<TLClass> visited = new HashSet<>();
		visited.add(targetClass); // Start with the class being updated

		for (TLClass parent : newGeneralizations) {
			checkForCycle(parent, visited);
		}
	}

	/**
	 * Recursively checks for inheritance cycles.
	 *
	 * @param currentClass The current class to check.
	 * @param visited The set of already visited classes in the inheritance chain.
	 * @throws ToolArgumentUtil.ToolInputException if a cycle is detected.
	 */
	private static void checkForCycle(TLClass currentClass, Set<TLClass> visited)
			throws ToolArgumentUtil.ToolInputException {
		if (visited.contains(currentClass)) {
			throw new ToolArgumentUtil.ToolInputException(
				"Inheritance cycle detected: '" + currentClass.getName() +
				"' is already in the inheritance chain");
		}

		visited.add(currentClass);

		// Recursively check all generalizations
		for (TLClass parent : currentClass.getGeneralizations()) {
			checkForCycle(parent, new HashSet<>(visited));
		}
	}

	/**
	 * Resolves generalization class names to TLClass objects.
	 * Reuses the logic from ClassCreationTool.
	 *
	 * @param model The TLModel to search in.
	 * @param generalizationNames The list of class names to resolve.
	 * @return List of resolved TLClass objects.
	 * @throws ToolArgumentUtil.ToolInputException if any class cannot be found.
	 */
	private static List<TLClass> resolveGeneralizations(TLModel model, List<String> generalizationNames)
			throws ToolArgumentUtil.ToolInputException {
		List<TLClass> generalizations = new ArrayList<>();

		if (generalizationNames != null) {
			for (String genName : generalizationNames) {
				TLType genType = TLModelUtil.findType(model, genName);
				if (genType == null) {
					throw new ToolArgumentUtil.ToolInputException(
						"Generalization class '" + genName + "' does not exist");
				}
				if (!(genType instanceof TLClass)) {
					throw new ToolArgumentUtil.ToolInputException(
						"Generalization '" + genName + "' is not a class");
				}
				generalizations.add((TLClass) genType);
			}
		}

		return generalizations;
	}

	/**
	 * Builds a JSON response for successful class updates.
	 *
	 * @param tlClass The updated class.
	 * @return JSON string with success information and updated class details.
	 */
	private static String buildSuccessJson(TLClass tlClass) throws IOException {
		StringWriter buffer = new StringWriter();
		try (JsonWriter json = JsonResponseBuilder.createWriter(buffer)) {
			json.beginObject();

			json.name("success").value(true);
			json.name("updated").value(true);

			json.name("class").beginObject();
			json.name("name").value(tlClass.getName());
			json.name("tid").value(tlClass.tId().asString());
			json.name("module").value(tlClass.getModule().getName());
			json.name("abstract").value(tlClass.isAbstract());
			json.name("final").value(tlClass.isFinal());

			// Add generalizations
			json.name("generalizations").beginArray();
			for (TLClass gen : tlClass.getGeneralizations()) {
				json.value(gen.getName());
			}
			json.endArray();

			// Add label and description
			JsonResponseBuilder.writeLabelAndDescription(json, TLModelNamingConvention.getTypeLabelKey(tlClass));

			json.endObject();

			json.name("message").value("Class '" + tlClass.getName() + "' updated successfully");

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