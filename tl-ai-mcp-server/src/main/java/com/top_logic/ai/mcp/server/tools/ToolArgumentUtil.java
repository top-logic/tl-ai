package com.top_logic.ai.mcp.server.tools;

import java.util.Map;

public final class ToolArgumentUtil {

	private ToolArgumentUtil() {
	}

	public static String requireNonEmptyString(
			Map<String, Object> arguments,
			String key,
			String fieldLabel) throws ToolInputException {

		if (arguments == null) {
			throw new ToolInputException(fieldLabel + " is required");
		}

		Object value = arguments.get(key);
		if (value == null) {
			throw new ToolInputException(fieldLabel + " is required");
		}

		String text = value.toString().trim();
		if (text.isEmpty()) {
			throw new ToolInputException(fieldLabel + " cannot be empty");
		}

		return text;
	}

	public static final class ToolInputException extends Exception {
		public ToolInputException(String message) {
			super(message);
		}
	}
}
