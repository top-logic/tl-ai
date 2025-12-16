# TopLogic AI Integration

AI integration for TopLogic applications with LLM support, MCP server capabilities, and chat functionality.

## Modules

### [tl-ai-service](tl-ai-service/)
AI service integration providing LLM access through LangChain4j with support for OpenAI, Anthropic, and Mistral models. Includes TL-Script functions for chat, structured JSON output, and multimodal content.

### [tl-ai-mcp-server](tl-ai-mcp-server/)
Model Context Protocol (MCP) server implementation enabling AI agents to interact with TopLogic's model structure, metadata, and business logic through standardized resources and tools.

### [tl-chat](tl-chat/)
Chat UI components and model definitions for building conversational interfaces within TopLogic applications.

### [tl-ai-demo](tl-ai-demo/)
Demo application showcasing AI integration features including chat interactions, MCP server capabilities, and AI-powered business logic.

## Quick Start

### Prerequisites
- Java 17+
- Maven 3.8+
- TopLogic 7.10.0-SNAPSHOT
- API keys for AI providers (OpenAI, Anthropic, or Mistral)

### Build

```bash
# Build all modules
mvn clean install

# Skip tests
mvn clean install -DskipTests
```

### Configuration

Set your API keys as environment variables:

```bash
export OPENAI_API_KEY="sk-..."
export ANTHROPIC_API_KEY="sk-ant-..."
export MISTRAL_API_KEY="..."
```

Configure AI models in `tl-ai-service/src/main/webapp/WEB-INF/conf/tl-ai-service.config.xml`.

### Run Demo

```bash
cd tl-ai-demo
mvn jetty:run
```

Access at: http://localhost:8080/tl-ai-demo/

## Features

- **Multiple AI Providers**: OpenAI (GPT-4o, GPT-3.5), Anthropic (Claude), Mistral AI
- **Thread-Safe Pooling**: Automatic model lifecycle management
- **TL-Script Integration**: AI functions accessible from expressions
- **Structured Output**: JSON schemas for type-safe responses
- **Multimodal Support**: Text, images, audio, video, PDF documents
- **MCP Server**: Standard protocol for AI agent integration
- **Chat UI**: Ready-to-use conversational components

## Documentation

- [TL AI Service Documentation](tl-ai-service/README.md) - AI integration and TL-Script functions
- [CLAUDE.md](CLAUDE.md) - Development guidelines and project structure

## License

Copyright (c) 2025 My Company. All Rights Reserved
