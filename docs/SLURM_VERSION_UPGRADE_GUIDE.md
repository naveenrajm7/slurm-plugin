# SLURM Version Upgrade Guide

This document explains how to upgrade the Jenkins SLURM plugin to support newer versions of the SLURM REST API.

## Overview

The plugin uses version-independent OpenAPI-generated client code that can be easily upgraded to newer SLURM versions. This design follows the SLURM documentation's recommendation to "strip out the version tag from the struct names" to create maintainable client code.

## Architecture

The plugin uses a two-stage process for generating client code:

1. **Preprocessing**: A Python script (`scripts/preprocess-openapi-spec.py`) removes version prefixes from the OpenAPI specification
2. **Code Generation**: OpenAPI Generator creates version-independent Java client classes

### Version Prefixes Removed

The preprocessing removes version identifiers from:
- **Schema names**: `v0.0.42_job_desc_msg` → `job_desc_msg` → `JobDescMsg.java`
- **Operation IDs**: `slurm_v0042_post_job_submit` → `slurm_post_job_submit` → `slurmPostJobSubmit()`
- **All references**: All `$ref` pointers are updated to use the new non-versioned names

### Generated Classes

The build generates these key classes **without version prefixes**:
- `JobDescMsg`, `JobSubmitReq`, `OpenapiJobSubmitResponse`
- `Uint64NoValStruct`, `Uint32NoValStruct`
- `OpenapiPingArrayResp`, `ControllerPing`
- `OpenapiError`, `OpenapiKillJobResp`
- API methods: `slurmGetPing()`, `slurmPostJobSubmit()`, `slurmDeleteJob()`

## Upgrading to a New SLURM Version

### Step 1: Generate New OpenAPI Specification

On your SLURM controller, generate the OpenAPI spec for the new version:

```bash
# Example for SLURM v0.0.43 (hypothetical)
slurmrestd --generate-openapi-spec -s slurmctld,slurmdbd -d v0.0.43 > slurm-v0.0.43.json
```

### Step 2: Add Spec to Project

Copy the new specification to the project:

```bash
cp slurm-v0.0.43.json /path/to/slurm-plugin/src/main/resources/openapi/
```

### Step 3: Update pom.xml

Update the OpenAPI spec file reference in `pom.xml`:

```xml
<execution>
  <id>preprocess-openapi-spec</id>
  <phase>generate-sources</phase>
  <goals>
    <goal>exec</goal>
  </goals>
  <configuration>
    <executable>python</executable>
    <arguments>
      <argument>${project.basedir}/scripts/preprocess-openapi-spec.py</argument>
      <!-- Update this line to point to new spec file -->
      <argument>${project.basedir}/src/main/resources/openapi/slurm-v0.0.43.json</argument>
      <argument>${project.build.directory}/generated-resources/openapi/slurm-processed.json</argument>
    </arguments>
    ...
  </configuration>
</execution>
```

### Step 4: Regenerate Client Code

Run Maven to regenerate the client code:

```bash
mvn clean generate-sources
```

The preprocessing script will:
- Process 181+ schema definitions (number may vary)
- Process 38+ operation IDs (number may vary)
- Output version-independent class names
- Generate Java classes in `target/generated-sources/openapi/`

### Step 5: Review API Changes

Check if SLURM made any breaking changes in the new version:

1. **New fields**: Added fields will be automatically available
2. **Removed fields**: Will cause compilation errors that need to be addressed
3. **Changed field types**: Will cause compilation errors that need to be fixed
4. **New endpoints**: Will automatically become available as new methods
5. **Deprecated endpoints**: May need to update code if removed

### Step 6: Update Code if Needed

If there are breaking changes, update the affected files:

**Files Most Likely to Need Updates:**
- `src/main/java/io/jenkins/plugins/slurm/client/SlurmClient.java` - Core client wrapper
- `src/main/java/io/jenkins/plugins/slurm/SlurmJobBuilder.java` - Job construction
- `src/main/java/io/jenkins/plugins/slurm/SlurmCloud.java` - Job submission
- `src/main/java/io/jenkins/plugins/slurm/SlurmLauncher.java` - Agent launching

### Step 7: Test

1. **Compilation test**:
   ```bash
   mvn compile
   ```

2. **Unit tests**:
   ```bash
   mvn test
   ```

3. **Integration test**:
   - Deploy to test Jenkins instance
   - Configure SLURM cloud with new API version
   - Test job submission and agent provisioning

### Step 8: Update Documentation

Update version references in:
- `README.md` - Note the supported SLURM version
- `pom.xml` - Update any version comments
- This document - Add notes about version-specific considerations

## Version Compatibility

### SLURM API Versioning Policy

According to SLURM documentation:
- API versions are **forward compatible** within support window
- Example: v0.0.38 added in Slurm-22.05, usable until Slurm-24.05
- New features only appear in newer versions
- Security fixes backported to supported versions

### Plugin Version Support

This plugin currently supports:
- **Current**: SLURM v0.0.42 (Slurm 24.x)
- **Upgrade path**: Can upgrade to any newer v0.0.4x or v0.0.5x version
- **Breaking changes**: Rare, but possible between major version increments

## Troubleshooting

### Compilation Errors After Upgrade

**Problem**: Code won't compile after upgrading SLURM version

**Solutions**:
1. Check if SLURM removed or renamed fields you're using
2. Review the SLURM release notes for breaking changes
3. Use `git diff` to compare old vs. new generated API classes
4. Update affected code to use new field names or methods

### Missing Methods/Classes

**Problem**: Getting "cannot find symbol" errors

**Solutions**:
1. Verify the preprocessing step completed successfully
2. Check `target/generated-resources/openapi/slurm-processed.json` exists
3. Ensure all version prefixes were removed from schema names
4. Re-run `mvn generate-sources`

### Runtime API Errors

**Problem**: Plugin compiles but fails at runtime with API errors

**Solutions**:
1. Verify SLURM controller is running the matching version
2. Check slurmrestd is serving the correct API version
3. Test API endpoints directly with curl:
   ```bash
   curl -H "X-SLURM-USER-TOKEN: your-token" \
        http://your-controller:6820/slurm/v0.0.43/ping/
   ```

## Best Practices

1. **Keep version-specific specs**: Maintain the original OpenAPI spec files with version numbers in the filename (e.g., `slurm-v0.0.42.json`, `slurm-v0.0.43.json`) for reference and rollback

2. **Test in stages**: 
   - First test preprocessing: `python scripts/preprocess-openapi-spec.py ...`
   - Then test generation: `mvn generate-sources`
   - Then test compilation: `mvn compile`
   - Finally test functionality: Deploy and run

3. **Document changes**: Keep notes about what changed between SLURM versions, especially if you had to modify plugin code

4. **Backward compatibility**: The actual API endpoint paths still include the version (e.g., `/slurm/v0.0.42/job/submit`), so the plugin can only talk to the matching SLURM API version

## Files Modified During Upgrade

### Automatic Changes (from regeneration)
- `target/generated-sources/openapi/**` - All generated client code

### Manual Changes Required
- `pom.xml` - Update input spec file path
- Potentially `src/main/java/io/jenkins/plugins/slurm/**` - If API breaking changes occurred

### No Changes Needed
- `scripts/preprocess-openapi-spec.py` - Reusable across versions
- Most plugin logic - Thanks to version-independent design

## Example Upgrade Session

```bash
# 1. Get new spec from SLURM controller
slurmrestd --generate-openapi-spec -s slurmctld,slurmdbd -d v0.0.43 > slurm-v0.0.43.json

# 2. Copy to project
cp slurm-v0.0.43.json /path/to/slurm-plugin/src/main/resources/openapi/

# 3. Update pom.xml (edit manually)
#    Change: slurm-v0.0.42.json → slurm-v0.0.43.json

# 4. Test preprocessing
python scripts/preprocess-openapi-spec.py \
    src/main/resources/openapi/slurm-v0.0.43.json \
    target/generated-resources/openapi/slurm-processed.json

# 5. Regenerate and compile
mvn clean generate-sources
mvn compile

# 6. Run tests
mvn test

# 7. Build plugin
mvn package

# 8. Deploy and test in Jenkins
```

## Additional Resources

- [SLURM REST API Documentation](https://slurm.schedmd.com/rest_api.html)
- [OpenAPI Generator Documentation](https://openapi-generator.tech)
- [Plugin README](../README.md)
- [Quick Start Guide](QUICK_START.md)
