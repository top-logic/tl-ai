/*
 * Copyright (c) 2025 TopLogic. All Rights Reserved
 */
package com.top_logic.ai.mcp.server.tools;

import java.io.IOException;
import java.io.StringWriter;
import java.util.Map;

import com.top_logic.basic.thread.ThreadContextManager;
import com.top_logic.common.json.gstream.JsonWriter;
import com.top_logic.model.TLModel;
import com.top_logic.model.TLModule;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.model.ModelService;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;

/**
 * MCP tool for creating new TopLogic modules.
 * <p>
 * This tool provides functionality to create new modules in the TopLogic application model using
 * {@link TLModelUtil#makeModule(TLModel, String)}. If a module with the given name already exists,
 * the existing module is returned.
 * </p>
 */
public class ModuleCreationTool {

	/** Tool name for the module creation functionality. */
	public static final String TOOL_NAME = "create-module";

	/** Tool description. */
	private static final String DESCRIPTION = "Create a new TopLogic module or get an existing one by name";

	/** JSON Schema for the tool input. */
	private static final String INPUT_SCHEMA_JSON = """
			{
			  "type": "object",
			  "properties": {
			    "moduleName": {
			      "type": "string",
			      "description": "Name of the module to create"
			    }
			  },
			  "required": ["moduleName"]
			}
			""";

	/**
	 * Creates the MCP tool specification for module creation.
	 *
	 * @return The tool specification that can be registered with the MCP server.
	 */
	public static McpServerFeatures.SyncToolSpecification createSpecification() {
		System.out.println("[MCP] Registering tool " + TOOL_NAME);
		// Get (or lazily create) the default JSON mapper from the MCP JSON module
		McpJsonMapper jsonMapper = McpJsonMapper.getDefault();

		McpSchema.Tool tool = McpSchema.Tool.builder()
			.name(TOOL_NAME)
			.title("Create TopLogic module")
			.description(DESCRIPTION)
			.inputSchema(jsonMapper, INPUT_SCHEMA_JSON)
			.build();

		return McpServerFeatures.SyncToolSpecification.builder()
			.tool(tool)
			.callHandler(ModuleCreationTool::handleToolRequest)
			.build();
	}

	/**
	 * Handles tool requests for creating modules.
	 *
	 * @param exchange
	 *        The MCP server exchange for interacting with the client.
	 * @param request
	 *        The tool call request (includes tool name, arguments, etc.).
	 * @return The result of the module creation operation.
	 */
	private static McpSchema.CallToolResult handleToolRequest(
			McpSyncServerExchange exchange,
			CallToolRequest request) {

		Map<String, Object> arguments = request.arguments();

		return ThreadContextManager.inSystemInteraction(
			ModuleCreationTool.class,
			() -> createModule(arguments));
	}

	/**
	 * Creates or retrieves a module based on the given arguments.
	 *
	 * @param arguments
	 *        The tool call arguments containing the module name.
	 * @return The result indicating success or failure with module details.
	 */
	private static McpSchema.CallToolResult createModule(Map<String, Object> arguments) {
		try {
			// Extract module name from arguments
			if (arguments == null) {
				return createErrorResult("Module name is required");
			}

			Object moduleNameObj = arguments.get("moduleName");
			if (moduleNameObj == null) {
				return createErrorResult("Module name is required");
			}

			String moduleName = moduleNameObj.toString().trim();
			if (moduleName.isEmpty()) {
				return createErrorResult("Module name cannot be empty");
			}

			// Get the application model
			TLModel model = ModelService.getApplicationModel();

			// Check if module exists before calling makeModule
			boolean existedBefore = (model.getModule(moduleName) != null);

			// Create or get the module
			TLModule module = TLModelUtil.makeModule(model, moduleName);

			// Determine if newly created
			boolean newlyCreated = !existedBefore;

			// Build success response JSON
			String jsonResponse = buildSuccessJson(module, newlyCreated);

			// Use the (String, Boolean) convenience constructor
			return new McpSchema.CallToolResult(jsonResponse, false);

		} catch (Exception ex) {
			return createErrorResult("Failed to create module: " + ex.getMessage());
		}
	}

	/**
	 * Builds a JSON response for successful module creation.
	 *
	 * @param module
	 *        The created or retrieved module.
	 * @param newlyCreated
	 *        Whether the module was newly created or already existed.
	 * @return JSON string containing the success response.
	 * @throws IOException
	 *         If JSON writing fails.
	 */
	private static String buildSuccessJson(TLModule module, boolean newlyCreated) throws IOException {
		StringWriter buffer = new StringWriter();
		try (JsonWriter json = new JsonWriter(buffer)) {
			json.setIndent("  ");
			json.beginObject();

			json.name("success").value(true);
			json.name("module").beginObject();
			json.name("name").value(module.getName());
			json.name("tid").value(module.tId().asString());
			json.name("typeCount").value(module.getTypes().size());
			json.endObject();
			json.name("created").value(newlyCreated);

			if (newlyCreated) {
				json.name("message").value("Module '" + module.getName() + "' created successfully");
			} else {
				json.name("message").value("Module '" + module.getName() + "' already existed");
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
		try {
			StringWriter buffer = new StringWriter();
			try (JsonWriter json = new JsonWriter(buffer)) {
				json.setIndent("  ");
				json.beginObject();
				json.name("success").value(false);
				json.name("error").value(errorMessage);
				json.name("module").nullValue();
				json.name("created").value(false);
				json.endObject();
			}

			return new McpSchema.CallToolResult(buffer.toString(), true);
		} catch (IOException ex) {
			// Fallback to simple error message if JSON creation fails
			String fallback = "{\"success\": false, \"error\": \"" + errorMessage.replace("\"", "\\\"") + "\"}";
			return new McpSchema.CallToolResult(fallback, true);
		}
	}
}
