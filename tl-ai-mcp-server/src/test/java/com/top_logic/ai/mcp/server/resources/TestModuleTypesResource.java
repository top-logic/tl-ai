/*
 * Copyright (c) 2025 My Company. All Rights Reserved
 */
package com.top_logic.ai.mcp.server.resources;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import junit.framework.TestCase;

/**
 * Test case for {@link ModuleTypesResource}.
 *
 * @author Bernhard Haumacher
 */
public class TestModuleTypesResource extends TestCase {

	/**
	 * Tests the URI pattern creation from template strings.
	 *
	 * <p>
	 * This test verifies that the pattern correctly extracts variable values from URIs that match
	 * the template format.
	 * </p>
	 */
	public void testCreateUriPattern() {
		// Test the actual URI template used by ModuleTypesResource
		String template = "toplogic://model/modules/{moduleName}/types";
		Pattern pattern = createUriPattern(template);

		// Test valid URIs with different module names
		assertMatches(pattern, "toplogic://model/modules/tl.core/types", "tl.core");
		assertMatches(pattern, "toplogic://model/modules/myapp.model/types", "myapp.model");
		assertMatches(pattern, "toplogic://model/modules/com.example.app/types", "com.example.app");

		// Test that invalid URIs don't match
		assertNoMatch(pattern, "toplogic://model/modules/types"); // Missing module name
		assertNoMatch(pattern, "toplogic://model/modules/tl.core"); // Missing /types
		assertNoMatch(pattern, "toplogic://model/tl.core/types"); // Missing /modules/
		assertNoMatch(pattern, "http://model/modules/tl.core/types"); // Wrong protocol
	}

	/**
	 * Tests pattern creation with multiple template variables.
	 */
	public void testMultipleVariables() {
		String template = "toplogic://model/{moduleName}/types/{typeName}";
		Pattern pattern = createUriPattern(template);

		Matcher matcher = pattern.matcher("toplogic://model/tl.core/types/MyType");
		assertTrue("URI should match pattern", matcher.matches());
		assertEquals("First variable", "tl.core", matcher.group(1));
		assertEquals("Second variable", "MyType", matcher.group(2));
	}

	/**
	 * Tests pattern creation with special regex characters in the template.
	 */
	public void testSpecialCharacters() {
		// Template contains dots and slashes which are special in regex
		String template = "toplogic://model.v2/modules/{moduleName}/types";
		Pattern pattern = createUriPattern(template);

		assertMatches(pattern, "toplogic://model.v2/modules/tl.core/types", "tl.core");

		// Should NOT match if the dot is replaced (dot should be literal, not regex wildcard)
		assertNoMatch(pattern, "toplogic://modelXv2/modules/tl.core/types");
	}

	/**
	 * Tests that module names with slashes are NOT captured (they should be rejected).
	 */
	public void testNoSlashesInVariables() {
		String template = "toplogic://model/modules/{moduleName}/types";
		Pattern pattern = createUriPattern(template);

		// Module names with slashes should not match
		assertNoMatch(pattern, "toplogic://model/modules/tl/core/types");
		assertNoMatch(pattern, "toplogic://model/modules/my/app/model/types");
	}

	/**
	 * Helper method to assert that a URI matches the pattern and extracts the expected value.
	 */
	private void assertMatches(Pattern pattern, String uri, String expectedValue) {
		Matcher matcher = pattern.matcher(uri);
		assertTrue("URI should match: " + uri, matcher.matches());
		assertEquals("Extracted value from: " + uri, expectedValue, matcher.group(1));
	}

	/**
	 * Helper method to assert that a URI does NOT match the pattern.
	 */
	private void assertNoMatch(Pattern pattern, String uri) {
		Matcher matcher = pattern.matcher(uri);
		assertFalse("URI should NOT match: " + uri, matcher.matches());
	}

	/**
	 * Delegates to the production code for pattern creation.
	 */
	private Pattern createUriPattern(String template) {
		return ModuleTypesResource.createUriPattern(template);
	}
}
