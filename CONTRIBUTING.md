# Contributing to FluxGate

Thank you for your interest in contributing to FluxGate! This document provides guidelines and instructions for
contributing.

## Table of Contents

- [Code of Conduct](#code-of-conduct)
- [Getting Started](#getting-started)
- [Development Setup](#development-setup)
- [Making Changes](#making-changes)
- [Coding Standards](#coding-standards)
- [Testing](#testing)
- [Submitting Changes](#submitting-changes)
- [Review Process](#review-process)

## Code of Conduct

By participating in this project, you agree to maintain a respectful and inclusive environment. Please:

- Be respectful and constructive in discussions
- Welcome newcomers and help them get started
- Focus on what is best for the community
- Show empathy towards other community members

## Getting Started

### Prerequisites

- **Java 11+** - Required for building and running the project
- **Maven 3.8+** - Build tool
- **Docker** - For running Redis and MongoDB during development
- **Git** - Version control

### Fork and Clone

1. Fork the repository on GitHub
2. Clone your fork locally:

```bash
git clone https://github.com/YOUR-USERNAME/fluxgate.git
cd fluxgate
```

3. Add the upstream repository:

```bash
git remote add upstream https://github.com/OpenFluxGate/fluxgate.git
```

## Development Setup

### 1. Start Infrastructure

We provide Docker Compose files for local development in the `docker/` directory:

| File                          | Description                                                          |
|-------------------------------|----------------------------------------------------------------------|
| `docker/full.yml`             | All services (Redis, MongoDB, ELK) - **Recommended for development** |
| `docker/redis-standalone.yml` | Redis standalone only                                                |
| `docker/redis-cluster.yml`    | Redis cluster (3 nodes)                                              |
| `docker/mongo.yml`            | MongoDB only                                                         |
| `docker/elk.yml`              | Elasticsearch, Logstash, Kibana                                      |

```bash
# Start all services (recommended)
docker compose -f docker/full.yml up -d

# Verify services are running
docker compose -f docker/full.yml ps

# Stop services
docker compose -f docker/full.yml down
```

### 2. Build the Project

```bash
# Build all modules
./mvnw clean install

# Build without tests (faster)
./mvnw clean install -DskipTests
```

### 3. Run Tests

```bash
# Run all tests and verify (required before PR)
./mvnw clean verify

# Run tests only
./mvnw test

# Run tests for specific module
./mvnw test -pl fluxgate-core
```

### 4. IDE Setup

#### IntelliJ IDEA (Recommended)

1. Open the project as a Maven project
2. Enable annotation processing: `Settings > Build > Compiler > Annotation Processors`
3. Import code style: `Settings > Editor > Code Style > Import Scheme`

#### VS Code

1. Install "Extension Pack for Java"
2. Open the project folder
3. Let Maven import complete

## Making Changes

### Branch Naming Convention

Create a branch from `main` with a descriptive name:

- `feature/` - New features (e.g., `feature/sliding-window-algorithm`)
- `development/` - New module (e.g., `development/fluxgate-sample-something`)
- `fix/` - Bug fixes (e.g., `fix/redis-connection-timeout`)
- `docs/` - Documentation changes (e.g., `docs/api-reference`)
- `refactor/` - Code refactoring (e.g., `refactor/cleanup-handlers`)
- `test/` - Test additions or modifications (e.g., `test/redis-integration`)

```bash
# Create a new branch
git checkout -b feature/your-feature-name

# Keep your branch updated
git fetch upstream
git rebase upstream/main
```

### Commit Messages

Follow the [Conventional Commits](https://www.conventionalcommits.org/) specification:

```
<type>(<scope>): <description>

[optional body]

[optional footer]
```

**Types:**

- `feat`: New feature
- `fix`: Bug fix
- `docs`: Documentation changes
- `style`: Code style changes (formatting, etc.)
- `refactor`: Code refactoring
- `test`: Adding or modifying tests
- `chore`: Build process or auxiliary tool changes

**Examples:**

```bash
feat(redis): add connection pool monitoring

fix(core): handle null key resolver gracefully

docs(readme): add quick start guide

test(mongo): add integration tests for rule store
```

## Coding Standards

### Java Style Guide

We follow the [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html) with some modifications:

1. **Indentation**: 4 spaces (not tabs)
2. **Line length**: 120 characters maximum
3. **Braces**: Always use braces for control statements

### Code Quality

- Write self-documenting code with clear variable/method names
- Add Javadoc for all public classes and methods
- Keep methods focused and small (< 30 lines preferred)
- Follow SOLID principles
- Avoid code duplication

### Javadoc Requirements

All public APIs must have Javadoc:

```java
/**
 * Attempts to consume tokens from the rate limiter.
 *
 * @param context the request context containing client information
 * @param ruleSet the rate limit rules to apply
 * @param tokens the number of tokens to consume
 * @return the result of the rate limit check
 * @throws IllegalArgumentException if tokens is less than 1
 */
public RateLimitResult tryConsume(RequestContext context, RateLimitRuleSet ruleSet, long tokens);
```

### Package Structure

```
org.fluxgate
├── core                 # Core abstractions and interfaces
│   ├── config          # Configuration classes
│   ├── context         # Request context
│   ├── handler         # Rate limit handlers
│   ├── key             # Key resolution
│   └── ratelimiter     # Rate limiter implementations
├── redis               # Redis-specific implementation
├── adapter.mongo       # MongoDB adapter
└── spring              # Spring Boot integration
```

## Testing

### Test Categories

1. **Unit Tests** - Test individual classes in isolation
2. **Integration Tests** - Test component interactions
3. **End-to-End Tests** - Test complete flows

### Writing Tests

- Use descriptive test method names
- Follow the Arrange-Act-Assert pattern
- Mock external dependencies in unit tests
- Use `@Tag` for test categorization

```java

@Test
@DisplayName("Should reject request when rate limit exceeded")
void shouldRejectWhenRateLimitExceeded() {
    // Arrange
    RateLimiter limiter = createLimiter(10);
    consumeTokens(limiter, 10);

    // Act
    RateLimitResult result = limiter.tryConsume(context, ruleSet, 1);

    // Assert
    assertThat(result.isAllowed()).isFalse();
    assertThat(result.getRemainingTokens()).isZero();
}
```

### Test Coverage

- Aim for at least 80% code coverage
- Focus on testing business logic and edge cases
- Don't test trivial getters/setters

## Submitting Changes

### Before Submitting

1. **Start infrastructure** (if not running):
   ```bash
   docker compose -f docker/full.yml up -d
   ```

2. **Update your branch**:
   ```bash
   git fetch upstream
   git rebase upstream/main
   ```

3. **Apply code formatting**:
   ```bash
   ./mvnw spotless:apply
   ```

4. **Run all tests and verify**:
   ```bash
   ./mvnw clean verify
   ```

   This command runs:
    - Code compilation
    - Unit tests
    - Integration tests
    - Code coverage checks (JaCoCo)
    - Code formatting checks (Spotless)

### Creating a Pull Request

1. Push your branch to your fork:
   ```bash
   git push origin feature/your-feature-name
   ```

2. Open a Pull Request on GitHub

3. Fill in the PR template with:
    - Clear description of changes
    - Related issue numbers
    - Testing performed
    - Screenshots (if UI changes)

### PR Checklist

- [ ] Code follows the project style guide
- [ ] All tests pass locally
- [ ] New code has appropriate test coverage
- [ ] Javadoc added for new public APIs
- [ ] README updated if needed
- [ ] CHANGELOG updated for notable changes

## Review Process

### What to Expect

1. **Automated checks** run first (CI/CD)
2. **Code review** by maintainers
3. **Feedback** provided as comments
4. **Approval** when all requirements met
5. **Merge** by maintainer

### Responding to Feedback

- Be open to suggestions
- Ask questions if feedback is unclear
- Make requested changes promptly
- Mark conversations as resolved when addressed

### After Merge

- Delete your feature branch
- Pull the latest changes:
  ```bash
  git checkout main
  git pull upstream main
  ```

## Getting Help

- **Questions/Bugs**: Open a [GitHub Issue](https://github.com/OpenFluxGate/fluxgate/issues)
- **Security**: Email security@openfluxgate.org (do not open public issues)

## Recognition

Contributors are recognized in:

- Release notes
- GitHub contributors list
- Project documentation

Thank you for contributing to FluxGate!
