# Steve AI - Autonomous AI Agent for Minecraft

We built Cursor for Minecraft. Instead of AI that helps you write code, you get AI agents that actually play the game with you.

https://github.com/user-attachments/assets/23f0ccdd-7a7a-4d49-9dd9-215ebf67265a

## What It Does

Steve acts as an Agent, or a series of Agents if you choose to employ all of them. You describe what you want, and he understands the context and executes. Same concept here, except instead of code editing, you get embodied Steves that operate in your Minecraft world.

The interface is simple: press K to open a panel, type what you need. The agents handle the interpretation, planning, and execution. Say "mine some iron" and the agent reasons about where iron spawns, navigates to the appropriate depth, locates ore veins, and extracts the resources. Ask for a house and it considers the available materials, generates an appropriate structure, and builds it block by block.

What makes this interesting is the multi-agent coordination. When multiple Steves work on the same task, they don't just independently execute, they actively coordinate to avoid conflicts and optimize workload distribution. Tell three agents to build a castle and they'll automatically partition the structure, divide sections among themselves, and parallelize the construction.

The agents aren't following predefined scripts. They're operating off natural language instructions, which means:
- **Resource extraction** where agents determine optimal mining locations and strategies
- **Autonomous building** with agents planning layouts and material usage
- **Combat and defense** where agents assess threats and coordinate responses
- **Exploration and gathering** with pathfinding and resource location
- **Collaborative execution** with automatic workload balancing and conflict resolution

## Quick Start

**You need:**
- Minecraft 1.20.1 with Forge
- Java 17
- An OpenAI API key (or Groq/Gemini if you prefer)

**Installation:**
1. Download the JAR from releases
2. Put it in your `mods` folder
3. Launch Minecraft
4. Copy `config/steve-common.toml.example` to `config/steve-common.toml`
5. Add your API key to the config

**Config example:**
```toml
[openai]
apiKey = "your-api-key-here"
model = "gpt-3.5-turbo"
maxTokens = 1000
temperature = 0.7
```

Then spawn a Steve with `/steve spawn Bob` and press K to start giving commands.

> **Steve names** may contain letters of any script (including Cyrillic), digits
> and `_ - . +` only — no spaces, quotes or other characters (quoted input still
> parses, but the name itself must match that set). If a Steve was created before
> this restriction with a name outside the set, it can no longer be addressed by
> command; respawn it under an allowed name.

## Usage Examples

```
"mine 20 iron ore"
"build a house near me"
"help Alex with the tower"
"defend me from zombies"
"follow me"
"gather wood from that forest"
"make a cobblestone platform here"
"attack that creeper"
```

The agents are pretty good at figuring out what you mean. You don't need to be super specific.

## Technical Architecture

### System Overview

Each Steve runs an autonomous agent loop that processes natural language commands through an LLM, converts them into structured actions, and executes them using Minecraft's game mechanics. The system uses a direct action execution model optimized for real-time gameplay rather than a traditional ReAct framework.

**Core execution flow:**
1. User input captured via GUI (press K)
2. Task sent to TaskPlanner with conversation context
3. LLM (Groq/OpenAI/Gemini) generates structured action plan
4. ResponseParser extracts actions from LLM response
5. ActionExecutor processes actions through specialized action classes
6. Actions execute tick-by-tick to avoid freezing the game
7. Results fed back into conversation memory for context

### Core Components

**LLM Integration** (`com.steve.ai.llm`)
- **GeminiClient, GroqClient, OpenAIClient**: Pluggable LLM providers for agent reasoning
- **TaskPlanner**: Orchestrates LLM calls with context (conversation history, world state, Steve capabilities)
- **PromptBuilder**: Constructs prompts with available actions, examples, and formatting instructions
- **ResponseParser**: Extracts structured action sequences from LLM responses

**Action System** (`com.steve.ai.action`)
- **ActionExecutor**: Tick-based action execution engine (prevents game freezing)
- **BaseAction**: Abstract class for all actions (mine, build, move, combat, etc.)
- **Task**: Data model for action parameters and metadata
- **Available Actions**:
  - MineBlockAction: Intelligent ore/block mining with pathfinding
  - BuildStructureAction: Procedural building
  - PlaceBlockAction: Single block placement with validation
  - MoveToAction: Pathfinding-based movement
  - AttackAction: Combat with target selection
  - FollowAction: Player/entity following
  - WaitAction: Controlled delays and synchronization

**Structure Generation** (`com.steve.ai.structure`)
- **StructureGenerators**: Procedural generation algorithms (houses, castles, towers, barns)
- **StructureLoader**: NBT file loading from resources
- **BlockPlacement**: Shared data structure for block positioning

**Multi-Agent Collaboration** (`com.steve.ai.action`)
- **CollaborativeBuildManager**: Server-side coordination for parallel building
- **Spatial partitioning**: Automatically divides structures into non-overlapping sections
- **Work distribution**: Assigns sections to available Steves
- **Conflict prevention**: Atomic block placement with position tracking
- **Dynamic rebalancing**: Reassigns work when agents finish early

**Memory & Context** (`com.steve.ai.memory`)
- **SteveMemory**: Per-agent conversation history and task context
- **WorldKnowledge**: Tracks discovered resources, landmarks, and spatial data
- **StructureRegistry**: Catalogs built structures for reference and avoidance

**Code Execution** (`com.steve.ai.execution`)
- **CodeExecutionEngine**: GraalVM JavaScript engine for LLM-generated scripts
- **SteveAPI**: Safe API bridge exposing Minecraft actions to scripts
- **Sandboxing**: Restricted environment preventing harmful operations

### Key Design Decisions

**Tick-Based Execution**
Actions run incrementally across multiple game ticks rather than blocking. This prevents server freezes and maintains responsiveness. Each action's `tick()` method does minimal work per frame and tracks progress internally.

**Direct Action Execution (Not Traditional ReAct)**
While inspired by ReAct, we use direct action execution for real-time gameplay. The LLM generates complete action sequences upfront rather than iterative observe-think-act cycles. This reduces API calls and latency, critical for game responsiveness.

**Multi-Agent Coordination**
Collaborative builds use deterministic spatial partitioning. Structures are divided into rectangular sections based on agent count. Each Steve claims a section atomically, preventing conflicts. The manager is fully server-side using ConcurrentHashMap for thread safety.

**Memory Management**
Context windows are managed by pruning old messages while keeping recent exchanges and critical world state. Each LLM call includes: conversation history (last 10 exchanges), current task details, Steve's position/inventory, and known world features.

### Integration with Minecraft

**Entity Registration**
Steves are custom EntityType registered via Forge's deferred registry system. They extend PathfinderMob for vanilla pathfinding integration and implement custom goals for AI behavior.

**Event Hooks**
- ServerStarting: Initialize collaborative build manager
- ServerStopping: Cleanup active tasks and save state
- ClientTick: GUI rendering and input handling

**GUI Implementation**
Custom overlay GUI activated with K key. Uses Minecraft's Screen class with custom rendering. Text input forwarded to TaskPlanner on submission.

## Building from Source

Standard Gradle workflow:

```bash
git clone https://github.com/YuvDwi/Steve.git
cd Steve
./gradlew build
```

Output JAR will be in `build/libs/`. To test in development:

```bash
./gradlew runClient
```

**Project Structure:**
```
src/main/java/com/steve/ai/
├── entity/          # Steve entity, spawning, lifecycle
├── llm/             # LLM clients, prompt building, response parsing
├── action/          # Action classes and collaborative build manager
├── structure/       # Procedural generation and template loading
├── memory/          # Context management and world knowledge
├── execution/       # JavaScript code execution engine
├── client/          # GUI overlay
└── command/         # Minecraft commands (/steve spawn, etc)
```

## Contributing

We welcome contributions! Here's how to get started:

### Reporting Bugs

1. Check [existing issues](https://github.com/YuvDwi/Steve/issues) first
2. Include:
   - Minecraft/Forge/Steve AI versions
   - Steps to reproduce
   - Expected vs actual behavior
   - Logs from `logs/latest.log`

### Submitting Code

1. **Fork and clone**
   ```bash
   git clone https://github.com/YourUsername/Steve.git
   cd Steve
   ```

2. **Create feature branch**
   ```bash
   git checkout -b feature/your-feature-name
   ```

3. **Make changes**
   - Follow code style (4-space indent, JavaDoc for public APIs)
   - Test with `./gradlew build && ./gradlew runClient`

4. **Submit PR**
   - Clear commit messages
   - Describe changes and reasoning
   - Link related issues

### Code Style

- **Classes**: PascalCase
- **Methods/Variables**: camelCase
- **Constants**: UPPER_SNAKE_CASE
- **Indentation**: 4 spaces
- **Line length**: Max 120 characters
- **Comments**: JavaDoc for public methods

**Adding New Actions:**
1. Extend `BaseAction` in `com.steve.ai.action.actions`
2. Implement `tick()`, `isComplete()`, `onCancel()`
3. Update `PromptBuilder.java` to inform LLM about new action
4. Add example usage in prompt template

## Configuration

Edit `config/steve-common.toml`:

```toml
[llm]
provider = "groq"  # Options: openai, groq, gemini

[openai]
apiKey = "sk-..."
model = "gpt-3.5-turbo"
maxTokens = 1000
temperature = 0.7

[groq]
apiKey = "gsk_..."
model = "llama3-70b-8192"
maxTokens = 1000

[gemini]
apiKey = "AI..."
model = "gemini-1.5-flash"
maxTokens = 1000
```

**Performance Tips:**
- Use Groq for fastest inference (recommended for gameplay)
- GPT-4 for better planning but higher latency
- Lower temperature (0.5-0.7) for more deterministic actions

## Known Issues

**The agents are only as smart as the LLM.** GPT-3.5 works but makes occasional weird decisions. GPT-4 is noticeably better at multi-step planning.

**No crafting yet.** Agents can mine and place blocks but can't craft tools. We're working on it.

**Actions are synchronous.** If a Steve is mining, it can't do anything else until done. Planning to add proper async execution.

**Memory resets on restart.** Right now context only persists during a play session. We're adding persistent memory with a vector DB.

## What's Next

Planned features:
- Crafting system (agents make their own tools)
- Voice commands via Whisper API
- Vector database for long-term memory
- Async action execution for multitasking
- More building templates and procedural generation
- Enhanced pathfinding for complex terrain

Goal is to make this actually useful for survival gameplay, not just a tech demo.

## Why We Made This

We wanted to see if the Cursor model could work outside of coding. Turns out it translates pretty well. Same principles: deep environment integration, clear action primitives, persistent context.

Minecraft is actually a good testbed for agent research. Complex enough to be interesting, constrained enough that agents can actually succeed.

Plus it's just fun watching AIs build castles while you explore.

## Credits

- OpenAI/Groq/Google for LLM APIs
- Minecraft Forge for the modding framework
- LangChain/AutoGPT for agent architecture inspiration

## License

MIT

## Issues

Found a bug? Open an issue: https://github.com/YuvDwi/Steve/issues
