/*
 * Copyright (c) 2025 TopLogic. All Rights Reserved
 */
package com.top_logic.ai.mcp.server.tools;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.top_logic.ai.mcp.server.util.JsonResponseBuilder;
import com.top_logic.basic.exception.I18NRuntimeException;
import com.top_logic.basic.thread.ThreadContextManager;
import com.top_logic.basic.util.ResKey;
import com.top_logic.common.json.gstream.JsonWriter;
import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.model.TLModel;
import com.top_logic.model.TLObject;
import com.top_logic.model.search.expr.SearchExpression;
import com.top_logic.model.search.expr.config.SearchBuilder;
import com.top_logic.model.search.expr.config.dom.Expr;
import com.top_logic.model.search.expr.parser.ParseException;
import com.top_logic.model.search.expr.parser.SearchExpressionParser;
import com.top_logic.model.search.expr.parser.SearchExpressionParserConstants;
import com.top_logic.model.search.expr.parser.Token;
import com.top_logic.model.search.expr.parser.TokenMgrError;
import com.top_logic.model.search.expr.query.Args;
import com.top_logic.model.search.expr.query.QueryExecutor;
import com.top_logic.model.search.ui.ModelReferenceChecker;
import com.top_logic.util.Resources;
import com.top_logic.util.model.ModelService;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;

/**
 * MCP tool for executing TLScript code and returning results.
 * <p>
 * This tool performs complete TLScript validation and execution:
 * <ul>
 * <li>Syntax validation (parser errors)</li>
 * <li>Model reference validation (undefined classes/properties/types)</li>
 * <li>Type checking (type compatibility)</li>
 * <li>Script execution with configurable transaction control</li>
 * <li>Result serialization (primitives, collections, TLObjects)</li>
 * </ul>
 * </p>
 * <p>
 * By default, scripts execute in read-only mode where any modifications are discarded.
 * Set {@code withCommit: true} to execute within a transaction and persist changes.
 * </p>
 */
public class TLScriptExecutionTool {

	/** Tool name for the TLScript execution functionality. */
	public static final String TOOL_NAME = "execute-tlscript";

	/** Tool description. */
	private static final String DESCRIPTION =
		"Execute TLScript code and return results. " +
			"Validates script (syntax, model references, types) before execution. " +
			"Read-only by default; set withCommit=true to persist modifications.";

	/** JSON Schema for the tool input. */
	private static final String INPUT_SCHEMA_JSON = """
			{
			  "type": "object",
			  "properties": {
			    "script": {
			      "type": "string",
			      "description": "TLScript code to execute. Can be a single expression or multiple expressions wrapped in {}."
			    },
			    "withCommit": {
			      "type": "boolean",
			      "description": "If true, execute within transaction and commit changes. If false (default), discard modifications.",
			      "default": false
			    },
			    "args": {
			      "type": "array",
			      "description": "Optional arguments to pass to the script ($arg1, $arg2, ...)",
			      "items": {
			        "anyOf": [
			          {"type": "string"},
			          {"type": "number"},
			          {"type": "boolean"},
			          {"type": "null"}
			        ]
			      }
			    }
			  },
			  "required": ["script"],
			  "additionalProperties": false
			}
			""";

	/**
	 * Creates the MCP tool specification for TLScript execution.
	 *
	 * @return The tool specification that can be registered with the MCP server.
	 */
	public static McpServerFeatures.SyncToolSpecification createSpecification() {
		McpJsonMapper jsonMapper = McpJsonMapper.getDefault();

		McpSchema.Tool tool = McpSchema.Tool.builder()
			.name(TOOL_NAME)
			.title("Execute TLScript")
			.description(DESCRIPTION)
			.inputSchema(jsonMapper, INPUT_SCHEMA_JSON)
			.build();

		return McpServerFeatures.SyncToolSpecification.builder()
			.tool(tool)
			.callHandler(TLScriptExecutionTool::handleToolRequest)
			.build();
	}

	/**
	 * Handles tool requests for executing TLScript code.
	 *
	 * @param exchange
	 *        The MCP server exchange for interacting with the client.
	 * @param request
	 *        The tool call request (includes tool name, arguments, etc.).
	 * @return The result of the execution operation.
	 */
	private static McpSchema.CallToolResult handleToolRequest(
			McpSyncServerExchange exchange,
			CallToolRequest request) {

		Map<String, Object> arguments = request.arguments();

		return ThreadContextManager.inSystemInteraction(
			TLScriptExecutionTool.class,
			() -> executeScript(arguments));
	}

	/**
	 * Executes TLScript code based on the given arguments.
	 *
	 * @param arguments
	 *        The tool call arguments containing the script and options.
	 * @return The result indicating execution success or failure with results/errors.
	 */
	private static McpSchema.CallToolResult executeScript(Map<String, Object> arguments) {
		// Extract parameters
		final String script;
		try {
			script = ToolArgumentUtil.requireNonEmptyString(
				arguments,
				"script",
				"TLScript code");
		} catch (ToolArgumentUtil.ToolInputException e) {
			return createToolErrorResult(e.getMessage());
		}

		boolean withCommit = ToolArgumentUtil.getBooleanArgument(arguments, "withCommit", false);
		List<?> argsList = ToolArgumentUtil.getOptionalObjectList(arguments, "args");

		// Parse and validate
		SearchExpressionParser parser = new SearchExpressionParser(new StringReader(script));
		Expr expr;
		try {
			// Step 1: Parse (syntax validation)
			expr = parser.expr();

			// Step 2: Check for trailing content (garbage after valid expression)
			Token currentToken = parser.token;
			if (currentToken.kind != SearchExpressionParserConstants.EOF) {
				currentToken = parser.getNextToken();
			}
			if (currentToken.kind != SearchExpressionParserConstants.EOF) {
				// Trailing garbage found
				int line = currentToken.beginLine;
				int column = currentToken.beginColumn;
				return buildValidationErrorResponse("SYNTAX_ERROR", line, column,
					"Unexpected content after expression");
			}

			// Step 3: Validate model references and types (compilation check)
			ModelReferenceChecker.checkModelElements(expr);

		} catch (ParseException ex) {
			// Syntax error with location information
			Token errorToken = ex.currentToken.next;
			int line = errorToken.beginLine;
			int column = errorToken.beginColumn;
			String message = ex.getMessage();
			return buildValidationErrorResponse("SYNTAX_ERROR", line, column, message);

		} catch (TokenMgrError ex) {
			// Lexical error (invalid characters, malformed tokens)
			return buildValidationErrorResponse("LEXICAL_ERROR", 0, 0, ex.getMessage());

		} catch (I18NRuntimeException ex) {
			// Model reference or type checking error during compilation
			String message = Resources.getInstance().getString(ex.getErrorKey());
			return buildValidationErrorResponse("VALIDATION_ERROR", 0, 0, message);

		} catch (Exception ex) {
			// Unexpected validation error
			return createToolErrorResult("Validation failed: " + ex.getMessage());
		}

		// Build and compile expression
		try {
			TLModel model = ModelService.getApplicationModel();
			SearchExpression searchExpr = SearchBuilder.toSearchExpression(model, expr);

			KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
			QueryExecutor executor = QueryExecutor.compile(kb, model, searchExpr);

			// Prepare script arguments
			Args scriptArgs = prepareArgs(argsList);

			// Execute with transaction control
			Object result = executeWithTransactionControl(executor, scriptArgs, withCommit, kb);

			// Serialize and return result
			return buildSuccessResponse(result);

		} catch (I18NRuntimeException ex) {
			// Execution error (runtime)
			String message = Resources.getInstance().getString(ex.getErrorKey());
			return buildExecutionErrorResponse(message);

		} catch (Exception ex) {
			// Unexpected execution error
			return buildExecutionErrorResponse(ex.getMessage());
		}
	}

	/**
	 * Prepares script arguments from the request.
	 *
	 * @param argsList
	 *        The list of arguments from the request (may be null).
	 * @return Args structure for script execution.
	 */
	private static Args prepareArgs(List<?> argsList) {
		if (argsList == null || argsList.isEmpty()) {
			return Args.none();
		}
		return Args.some(argsList.toArray());
	}

	/**
	 * Executes the script with transaction control based on withCommit flag.
	 *
	 * @param executor
	 *        The compiled query executor.
	 * @param scriptArgs
	 *        The arguments to pass to the script.
	 * @param withCommit
	 *        If true, execute within transaction and commit changes; otherwise discard.
	 * @param kb
	 *        The knowledge base for transaction management.
	 * @return The execution result.
	 */
	private static Object executeWithTransactionControl(QueryExecutor executor, Args scriptArgs,
			boolean withCommit, KnowledgeBase kb) {
		if (withCommit) {
			// Execute within transaction - commit changes
			try (Transaction tx = kb.beginTransaction(ResKey.text("Execute TLScript"))) {
				Object result = executor.executeWith(
					executor.context(true, null, null),
					scriptArgs);
				tx.commit();
				return result;
			}
		} else {
			// Execute read-only - discard modifications
			return kb.withoutModifications(() ->
				executor.executeWith(
					executor.context(true, null, null),
					scriptArgs));
		}
	}

	/**
	 * Serializes a TLScript execution result to a JSON-compatible structure.
	 *
	 * @param result
	 *        The result to serialize (may be null).
	 * @return JSON-compatible object (primitives, Lists, Maps).
	 */
	private static Object serializeResult(Object result) {
		if (result == null) {
			return null;
		}

		// Primitives - return as-is
		if (result instanceof String || result instanceof Number || result instanceof Boolean) {
			return result;
		}

		// Collections - recursively serialize elements
		if (result instanceof Collection<?>) {
			List<Object> serialized = new ArrayList<>();
			for (Object item : (Collection<?>) result) {
				serialized.add(serializeResult(item));
			}
			return serialized;
		}

		// TLObject (model instances) - minimal serialization
		if (result instanceof TLObject) {
			TLObject obj = (TLObject) result;
			Map<String, Object> serialized = new LinkedHashMap<>();
			serialized.put("_tid", obj.tId().asString());
			serialized.put("_type", obj.tType().getName());
			serialized.put("_display", String.valueOf(obj));
			return serialized;
		}

		// Arrays - convert to list and serialize
		if (result.getClass().isArray()) {
			Object[] array = (Object[]) result;
			return serializeResult(Arrays.asList(array));
		}

		// Fallback: toString representation
		return String.valueOf(result);
	}

	/**
	 * Determines the result type for JSON response.
	 *
	 * @param result
	 *        The result object.
	 * @return Type name: "null", "string", "number", "boolean", "collection", "object".
	 */
	private static String getResultType(Object result) {
		if (result == null) {
			return "null";
		}
		if (result instanceof String) {
			return "string";
		}
		if (result instanceof Number) {
			return "number";
		}
		if (result instanceof Boolean) {
			return "boolean";
		}
		if (result instanceof Collection<?> || result.getClass().isArray()) {
			return "collection";
		}
		return "object";
	}

	/**
	 * Builds a JSON response for successful execution.
	 *
	 * @param result
	 *        The execution result to serialize.
	 * @return JSON string containing the success response.
	 */
	private static McpSchema.CallToolResult buildSuccessResponse(Object result) {
		try {
			Object serializedResult = serializeResult(result);
			String resultType = getResultType(result);

			StringWriter buffer = new StringWriter();
			try (JsonWriter json = JsonResponseBuilder.createWriter(buffer)) {
				json.beginObject();
				json.name("success").value(true);
				json.name("executed").value(true);

				// Write result (using raw JSON writing for complex objects)
				json.name("result");
				writeJsonValue(json, serializedResult);

				json.name("resultType").value(resultType);
				json.name("message").value("Script executed successfully");
				json.endObject();
			}
			return new McpSchema.CallToolResult(buffer.toString(), false);
		} catch (IOException ex) {
			return createToolErrorResult("Failed to build success response: " + ex.getMessage());
		}
	}

	/**
	 * Writes a JSON value (primitive, list, or map) to the JsonWriter.
	 *
	 * @param json
	 *        The JSON writer.
	 * @param value
	 *        The value to write.
	 * @throws IOException
	 *         If writing fails.
	 */
	private static void writeJsonValue(JsonWriter json, Object value) throws IOException {
		if (value == null) {
			json.nullValue();
		} else if (value instanceof String) {
			json.value((String) value);
		} else if (value instanceof Number) {
			json.value((Number) value);
		} else if (value instanceof Boolean) {
			json.value((Boolean) value);
		} else if (value instanceof List<?>) {
			json.beginArray();
			for (Object item : (List<?>) value) {
				writeJsonValue(json, item);
			}
			json.endArray();
		} else if (value instanceof Map<?, ?>) {
			json.beginObject();
			for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
				json.name(String.valueOf(entry.getKey()));
				writeJsonValue(json, entry.getValue());
			}
			json.endObject();
		} else {
			json.value(String.valueOf(value));
		}
	}

	/**
	 * Builds a JSON response for validation errors (before execution).
	 *
	 * @param errorType
	 *        The type of validation error (SYNTAX_ERROR, LEXICAL_ERROR, VALIDATION_ERROR).
	 * @param line
	 *        The line number where the error occurred (0 if not applicable).
	 * @param column
	 *        The column number where the error occurred (0 if not applicable).
	 * @param message
	 *        The error message.
	 * @return JSON string containing the error response.
	 */
	private static McpSchema.CallToolResult buildValidationErrorResponse(String errorType, int line, int column,
			String message) {
		try {
			StringWriter buffer = new StringWriter();
			try (JsonWriter json = JsonResponseBuilder.createWriter(buffer)) {
				json.beginObject();
				json.name("success").value(true);
				json.name("executed").value(false);
				json.name("error").beginObject();
				json.name("type").value(errorType);
				json.name("message").value(message);
				if (line > 0) {
					json.name("line").value(line);
				}
				if (column > 0) {
					json.name("column").value(column);
				}
				json.endObject();
				json.endObject();
			}
			return new McpSchema.CallToolResult(buffer.toString(), false);
		} catch (IOException ex) {
			return createToolErrorResult("Failed to build error response: " + ex.getMessage());
		}
	}

	/**
	 * Builds a JSON response for execution errors (runtime errors during execution).
	 *
	 * @param message
	 *        The error message.
	 * @return JSON string containing the error response.
	 */
	private static McpSchema.CallToolResult buildExecutionErrorResponse(String message) {
		try {
			StringWriter buffer = new StringWriter();
			try (JsonWriter json = JsonResponseBuilder.createWriter(buffer)) {
				json.beginObject();
				json.name("success").value(true);
				json.name("executed").value(false);
				json.name("error").beginObject();
				json.name("type").value("EXECUTION_ERROR");
				json.name("message").value(message);
				json.endObject();
				json.endObject();
			}
			return new McpSchema.CallToolResult(buffer.toString(), false);
		} catch (IOException ex) {
			return createToolErrorResult("Failed to build error response: " + ex.getMessage());
		}
	}

	/**
	 * Creates an error result for tool execution failures.
	 *
	 * @param errorMessage
	 *        The error message describing the failure.
	 * @return The error result with isError flag set to true.
	 */
	private static McpSchema.CallToolResult createToolErrorResult(String errorMessage) {
		String errorJson = JsonResponseBuilder.buildToolErrorJson(errorMessage);
		return new McpSchema.CallToolResult(errorJson, true);
	}
}
